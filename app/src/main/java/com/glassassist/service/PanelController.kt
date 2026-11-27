package com.glassassist.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.Service
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import com.example.liquidglass.GlassAccessibilityMode
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassFab
import com.example.liquidglass.LiquidGlassView
import com.glassassist.R
import com.glassassist.core.Prefs
import com.glassassist.core.SystemActions
import com.glassassist.core.TorchController
import com.glassassist.core.VolumeController
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Conteúdo e óptica do painel. As duas páginas ficam infladas desde a instalação:
 * trocar de página nunca recria views, evitando travadas nas setas. O arraste lateral
 * move as páginas seguindo o dedo e só decide o destino no ACTION_UP.
 */
class PanelController(
    private val service: Service,
    private val panelView: View,
    private val ballGlass: LiquidGlassView,
    private val prefs: Prefs,
    private val host: Host
) {

    interface Host {
        fun closePanelNow()
        fun takeCleanScreenshot()
        fun toast(message: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val cropExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "glassassist-crop").apply { isDaemon = true }
    }
    private val panelGlass: LiquidGlassView = panelView.findViewById(R.id.panelGlass)
    private val pagesContainer: PagesContainer = panelView.findViewById(R.id.pagesContainer)
    private val pagePrev: ImageView = panelView.findViewById(R.id.pagePrev)
    private val pageNext: ImageView = panelView.findViewById(R.id.pageNext)
    private val pages = arrayOf<ViewGroup>(
        panelView.findViewById(R.id.pageZero),
        panelView.findViewById(R.id.pageOne)
    )
    private val tiles = arrayOf(mutableListOf<LiquidGlassFab>(), mutableListOf<LiquidGlassFab>())

    private var page = prefs.page.coerceIn(0, 1)
    private var lightBackdrop = false
    /** Só libera blur/shader no painel depois que existe um backdrop real (não 1x1). */
    private var fullOpticsReady = false
    private var screenWidth = 0
    private var screenHeight = 0
    private var panelRect = GlassRect(0, 0, 0, 0)
    private var disposed = false

    private val transparentSource = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.TRANSPARENT)
    }
    /**
     * Print de tela completo (amostrado): o recorte é feito na hora do draw.
     * NUNCA reciclamos este bitmap: a biblioteca guarda referência em
     * cachedBackdrop/cachedBlurred e desenha sobre ele em frames posteriores.
     */
    @Volatile private var backdrop: Bitmap? = null
    /**
     * Cópia do print já escurecida/desfocada/com dispersão aplicada. É recortada
     * no draw, então o efeito dos sliders aparece de fato e não depende do
     * pipeline interno da biblioteca (que é desligado quando nós capturamos o fundo).
     * Nunca reciclamos esta referência: a lib pode estar desenhando o frame anterior.
     */
    @Volatile private var processedBackdrop: Bitmap? = null
    private var processedKey = ""

    private var dragDirection = 0
    private var dragNeighbor: ViewGroup? = null
    private var suppressClickUntil = 0L
    private val repeatRunnables = mutableSetOf<Runnable>()
    private val holdAnimators = mutableMapOf<View, ValueAnimator>()
    private var edgeGlow: EdgeGlowView? = null
    private var edgeAnimator: ValueAnimator? = null

    init {
        buildPages()
        installEdgePress()
        // A biblioteca recicla o bitmap devolvido: sempre uma cópia nova.
        // Qualquer exceção aqui seria propagada para o onDraw e mataria o processo.
        ballGlass.setCustomBackdropCapture { _ -> safeTinyBitmap() }
        panelGlass.setCustomBackdropCapture { bounds -> safeCropBackdrop(bounds) }
        applyOptics()
        setPageImmediate(page)
    }

    private fun safeTinyBitmap(): Bitmap = try {
        if (!disposed && !transparentSource.isRecycled) {
            transparentSource.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            transparentBitmap(1, 1)
        }
    } catch (_: Throwable) {
        transparentBitmap(1, 1)
    }

    private fun safeCropBackdrop(bounds: RectF): Bitmap = try {
        cropBackdrop(bounds)
    } catch (_: Throwable) {
        val w = bounds.width().toInt().coerceIn(1, 4096)
        val h = bounds.height().toInt().coerceIn(1, 4096)
        transparentBitmap(w, h)
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Float = value * service.resources.displayMetrics.density

    // ------------------------------------------------------------------ óptica

    /**
     *Configurações são lidas aqui e aplicadas na hora. A bolinha usa um backdrop
     *transparente 1x1:Pipeline de blur/shader com um bitmap desse tamanho pode falhar no
     *primeiro draw, então ela fica só com borda especular e tint. O painel, que tem
     *backdrop real, usa o pipeline completo de refração/dispersão.
     */
    fun applyOptics() {
        if (disposed) return
        try {
        val amount = prefs.blurRadius.coerceIn(0, 100) / 100f
        val refraction = prefs.refractionStrength.coerceIn(0f, 0.3f)
        // Faixa bem mais larga para o slider realmente mudar o resultado.
        val panelBlur = (0.02f + amount * 0.56f).coerceIn(0.02f, 0.58f)
        val dispersion = (refraction * 3.2f).coerceIn(0f, 0.9f)

        // Bolinha: vidro seguro, sem captura de shader sobre bitmap 1x1.
        ballGlass.apply {
            material = GlassMaterial.CLEAR
            accessibilityMode = GlassAccessibilityMode.FORCE_FULL
            enableDynamicBackground = false
            enableBackdropBlur = false
            useShaderPipeline = false
            useHardwareBlurWhenPossible = false
            enableChromaticDispersion = false
            enableChromaticAberration = false
            enableAdaptiveTint = false
            enableSensorHighlight = false
            cornerRadius = dp(999f)
            bevelWidth = dp(14f)
            refractionHeight = dp(40f)
            edgeHighlightBorderWidth = dp(1.3f)
            edgeHighlightOpacity = 88f
            edgeSoftness = dp(0.6f)
            enableEdgeHighlight = true
            enableShadow = false
            elasticity = 0.22f
            enablePressEffect = true
            pressScale = 0.92f
            setGlassTint(Color.rgb(10, 16, 24), 0.20f)
            collectFrameStats = false
        }

        // Painel: óptica completa só com backdrop real. Antes disso, modo seguro
        // (sem shader/blur) para não desenhar sobre um bitmap 1x1 e travar a lib.
        panelGlass.apply {
            material = GlassMaterial.CLEAR
            accessibilityMode = GlassAccessibilityMode.FORCE_FULL
            enableDynamicBackground = false
            enableBackdropBlur = fullOpticsReady
            useShaderPipeline = fullOpticsReady
            useHardwareBlurWhenPossible = fullOpticsReady
            highQualityBlur = fullOpticsReady
            saturation = 112f + refraction * 40f
            enableChromaticDispersion = fullOpticsReady
            enableChromaticAberration = false
            enableAdaptiveTint = false
            enableSensorHighlight = false
            dispersionFactor = 1.6f
            dispersionThickness = 64f
            dispersionGain = 6f + refraction * 16f
            dispersionDownsample = 1f
            enableEdgeHighlight = true
            edgeHighlightOpacity = prefs.edgeOpacity.toFloat()
            edgeSoftness = dp(1.2f)
            edgeHighlightBorderWidth = dp(1.2f)
            enableShadow = false
            refractionOutward = true
            refractionNoFold = true
            refractionFalloff = 2.1f
            adaptiveLensScale = false
            elasticity = 0.16f
            enablePressEffect = false
            cornerRadius = dp(prefs.panelCornerDp.toFloat())
            bevelWidth = dp(20f)
            refractionHeight = dp(64f)
            blurAmount = if (fullOpticsReady) panelBlur else 0f
            dispersionStrength = if (fullOpticsReady) dispersion else 0f
            setGlassTint(
                if (lightBackdrop) Color.WHITE else Color.rgb(10, 16, 24),
                if (lightBackdrop) 0.05f else 0.18f
            )
            collectFrameStats = false
        }
        ballGlass.post {
            if (!disposed) {
                ballGlass.invalidate()
                panelGlass.invalidate()
            }
        }
        // Os sliders também são aplicados no próprio bitmap de fundo.
        scheduleBackdropProcessing()
        } catch (t: Throwable) {
            // Nunca deixa uma exceção de propriedade da lib chegar ao onDraw.
            Log.e("GlassAssist", "Falha ao aplicar óptica", t)
        }
    }

    // ------------------------------------------------------------------ backdrop

    fun setScreen(width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
    }

    fun setGeometry(rect: GlassRect) {
        panelRect = rect
    }

    /** Troca o print completo. O recorte é feito no draw, com a posição atual da view. */
    fun setBackdrop(bitmap: Bitmap?) {
        if (disposed) return
        val usable = bitmap != null && !bitmap.isRecycled && bitmap.width > 1 && bitmap.height > 1
        val clean = if (usable) bitmap else null
        val wasReady = fullOpticsReady
        // A referência antiga é apenas solta. Reciclar aqui derrubava o app
        // quando a biblioteca ainda desenhava um frame com o bitmap anterior.
        backdrop = clean
        processedBackdrop = null
        processedKey = ""
        fullOpticsReady = clean != null
        scheduleBackdropProcessing()
        // Só reconfigura a lib quando o estado óptico muda. Reaplicar a cada
        // captura recriava o pipeline de shader e causava instabilidade.
        if (wasReady != fullOpticsReady) applyOptics()
        val rect = panelRect
        try {
            cropExecutor.execute {
                val light = try {
                    clean?.takeIf { !it.isRecycled && !disposed }
                        ?.let { measureLight(it, rect.x, rect.y, rect.w, rect.h) } ?: false
                } catch (_: Throwable) {
                    false
                }
                handler.post {
                    if (disposed || backdrop !== clean) return@post
                    if (light != lightBackdrop) {
                        lightBackdrop = light
                        applyOptics()
                    }
                }
            }
        } catch (_: Throwable) {
            // O executor pode ter sido desligado durante o dispose.
        }
    }

    /**
     * Processa o print completo fora da main thread. O resultado é o que o recorte
     * do vidro usa, então desfoque, refração e escurecimento são sempre visíveis.
     */
    private fun scheduleBackdropProcessing() {
        val source = backdrop ?: return
        val key = listOf(
            prefs.blurRadius,
            prefs.refractionStrength,
            prefs.panelDarkness,
            source.width,
            source.height
        ).joinToString("|")
        if (key == processedKey && processedBackdrop != null) return
        processedKey = key
        try {
            cropExecutor.execute {
                val src = source.takeIf { !it.isRecycled && !disposed } ?: return@execute
                val result = try {
                    buildProcessedBackdrop(src)
                } catch (t: Throwable) {
                    Log.e("GlassAssist", "Falha ao processar o fundo do painel", t)
                    null
                }
                handler.post {
                    if (disposed || backdrop !== src) return@post
                    if (result != null) {
                        // A referência antiga é apenas solta, nunca reciclada.
                        processedBackdrop = result
                        if (!disposed) panelGlass.invalidate()
                    }
                }
            }
        } catch (_: Throwable) {
            // Executor já desligado no dispose.
        }
    }

    private fun buildProcessedBackdrop(src: Bitmap): Bitmap {
        val amount = prefs.blurRadius.coerceIn(0, 100) / 100f
        val refraction = prefs.refractionStrength.coerceIn(0f, 0.3f)
        val darkness = prefs.panelDarkness.coerceIn(0, 80) / 100f

        val blurred: Bitmap = if (amount <= 0.01f) {
            src.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            val scale = (1f - amount * 0.88f).coerceIn(0.10f, 1f)
            val smallW = max(1, (src.width * scale).toInt())
            val smallH = max(1, (src.height * scale).toInt())
            val small = Bitmap.createScaledBitmap(src, smallW, smallH, true)
            val big = Bitmap.createScaledBitmap(small, src.width, src.height, true)
            if (big !== small) small.recycle()
            big
        }

        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        try {
            val keepR = 1f - darkness
            val keepG = 1f - darkness * 0.94f
            val keepB = 1f - darkness * 0.86f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                isFilterBitmap = true
                colorFilter = ColorMatrixColorFilter(
                    ColorMatrix(
                        floatArrayOf(
                            keepR, 0f, 0f, 0f, 0f,
                            0f, keepG, 0f, 0f, 0f,
                            0f, 0f, keepB, 0f, 0f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
            }
            Canvas(out).drawBitmap(blurred, 0f, 0f, paint)
            applyRefraction(out, refraction)
        } finally {
            if (blurred !== src) blurred.recycle()
        }
        return out
    }

    /** Separa R/B perto das bordas: o slider de refração passa a ter efeito real. */
    private fun applyRefraction(bitmap: Bitmap, strength: Float) {
        val maxShift = 7f * (strength / 0.3f)
        if (maxShift < 0.2f) return
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val result = IntArray(pixels.size)
        val cx = w / 2f
        val cy = h / 2f
        for (y in 0 until h) {
            val ny = (y - cy) / cy
            for (x in 0 until w) {
                val nx = (x - cx) / cx
                val edge = min(1f, (abs(nx) + abs(ny)) * 0.75f)
                val shift = maxShift * edge
                val rx = (x + shift).toInt().coerceIn(0, w - 1)
                val bx = (x - shift).toInt().coerceIn(0, w - 1)
                val ry = (y + shift * 0.35f).toInt().coerceIn(0, h - 1)
                val by = (y - shift * 0.35f).toInt().coerceIn(0, h - 1)
                val idx = y * w + x
                result[idx] = Color.argb(
                    Color.alpha(pixels[idx]),
                    Color.red(pixels[ry * w + rx]),
                    Color.green(pixels[idx]),
                    Color.blue(pixels[by * w + bx])
                )
            }
        }
        bitmap.setPixels(result, 0, w, 0, 0, w, h)
    }

    private fun measureLight(source: Bitmap, x: Int, y: Int, w: Int, h: Int): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0 || w <= 0 || h <= 0) return false
        val sx = source.width.toFloat() / screenWidth
        val sy = source.height.toFloat() / screenHeight
        val x0 = (x * sx).toInt().coerceIn(0, source.width - 1)
        val y0 = (y * sy).toInt().coerceIn(0, source.height - 1)
        val x1 = ((x + w) * sx).toInt().coerceIn(x0 + 1, source.width)
        val y1 = ((y + h) * sy).toInt().coerceIn(y0 + 1, source.height)
        val stepX = ((x1 - x0) / 6).coerceAtLeast(1)
        val stepY = ((y1 - y0) / 6).coerceAtLeast(1)
        var sum = 0.0
        var count = 0
        var py = y0
        while (py < y1) {
            var px = x0
            while (px < x1) {
                val pixel = source.getPixel(px, py)
                sum += (0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) +
                    0.114 * Color.blue(pixel)) / 255.0
                count++
                px += stepX
            }
            py += stepY
        }
        return count > 0 && sum / count > 0.62
    }

    /**
     * Recorte direto do print de tela na posição real da janela. Usar a posição
     * atual (e não uma geometria congelada) elimina o deslocamento do fundo quando
     * a bolinha é movida ou quando o painel é reancorado.
     */
    private fun cropBackdrop(bounds: RectF): Bitmap {
        val w = bounds.width().toInt().coerceAtLeast(1)
        val h = bounds.height().toInt().coerceAtLeast(1)
        val processed = processedBackdrop
        val source = if (processed != null && !processed.isRecycled) processed else backdrop
        if (source == null || source.isRecycled || screenWidth <= 0 || screenHeight <= 0) {
            return transparentBitmap(w, h)
        }
        val location = IntArray(2)
        panelGlass.getLocationOnScreen(location)
        val sx = source.width.toFloat() / screenWidth
        val sy = source.height.toFloat() / screenHeight
        val x = ((location[0] + bounds.left) * sx).toInt()
            .coerceIn(0, (source.width - 1).coerceAtLeast(0))
        val y = ((location[1] + bounds.top) * sy).toInt()
            .coerceIn(0, (source.height - 1).coerceAtLeast(0))
        val bw = (w * sx).toInt().coerceIn(1, source.width - x)
        val bh = (h * sy).toInt().coerceIn(1, source.height - y)
        val cropped = Bitmap.createBitmap(source, x, y, bw, bh)
        val out = cropped.copy(Bitmap.Config.ARGB_8888, false)
        if (cropped !== source) cropped.recycle()
        return out
    }

    private fun transparentBitmap(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }

    // ------------------------------------------------------------------ páginas

    private fun buildPages() {
        for (index in 0..1) {
            val grid = pages[index]
            tiles[index].forEach { it.animate().cancel() }
            grid.removeAllViews()
            tiles[index].clear()
            val specs = if (index == 0) pageOne() else pageTwo()
            val size = dp(prefs.tileSizeDp)
            specs.forEachIndexed { i, tile ->
                val fab = makeTile(tile)
                tiles[index] += fab
                val lp = GridLayout.LayoutParams(
                    GridLayout.spec(i / 3, 1f),
                    GridLayout.spec(i % 3, 1f)
                ).apply {
                    width = size
                    height = size
                    setGravity(Gravity.CENTER)
                    setMargins(dp(2), dp(3), dp(2), dp(3))
                }
                grid.addView(fab, lp)
            }
        }
        installPager()
        applyAppearance()
    }

    /** Aplica tamanho/cor dos botões e a nova taxa de repetição sem recriar o painel. */
    fun rebuildTiles() {
        cancelTileRepeats()
        buildPages()
    }

    private fun cancelTileRepeats() {
        repeatRunnables.forEach { handler.removeCallbacks(it) }
        repeatRunnables.clear()
        holdAnimators.values.forEach { it.cancel() }
        holdAnimators.clear()
        tiles.forEach { list ->
            list.forEach { view ->
                view.animate().cancel()
                view.scaleX = 1f
                view.scaleY = 1f
                view.translationY = 0f
                view.rotation = 0f
            }
        }
    }

    /**
     * Animação diferente dos botões de aperto único: enquanto volume/brilho
     * estão sendo segurados, o botão respira e inclina levemente.
     */
    private fun startHoldAnimation(view: View) {
        holdAnimators[view]?.cancel()
        view.animate().cancel()
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 460L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = DecelerateInterpolator(1.1f)
            addUpdateListener { animation ->
                val t = animation.animatedValue as Float
                view.scaleX = 1f + 0.09f * t
                view.scaleY = 1f - 0.06f * t
                view.translationY = -dp(3f) * t
                view.rotation = 3f * t
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (holdAnimators[view] === this@apply) holdAnimators.remove(view)
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (holdAnimators[view] === this@apply) holdAnimators.remove(view)
                }
            })
        }
        holdAnimators[view] = animator
        animator.start()
    }

    private fun stopHoldAnimation(view: View) {
        val animator = holdAnimators.remove(view)
        animator?.cancel()
        view.animate().cancel()
        view.animate().scaleX(1f).scaleY(1f).translationY(0f).rotation(0f)
            .setDuration(150L).setInterpolator(DecelerateInterpolator(1.4f)).start()
    }

    private fun installPager() {
        pagePrev.setOnClickListener { view ->
            if (System.currentTimeMillis() < suppressClickUntil) return@setOnClickListener
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            switchPage(-1)
        }
        pageNext.setOnClickListener { view ->
            if (System.currentTimeMillis() < suppressClickUntil) return@setOnClickListener
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            switchPage(1)
        }
        pagesContainer.onPageDrag = { dx, finished, switch -> handlePageDrag(dx, finished, switch) }
    }

    // ------------------------------------------------------------------ borda 3D

    private fun installEdgePress() {
        val glow = EdgeGlowView(service).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = false
            isFocusable = false
            cornerRadius = dp(prefs.panelCornerDp.toFloat())
        }
        (panelView as? ViewGroup)?.addView(glow)
        edgeGlow = glow
        // Distância da câmera maior = perspectiva mais perceptível ao girar a borda.
        panelGlass.cameraDistance = dp(14f)
        // Retornar false preserva clips, setas, paginação e botões.
        panelGlass.setOnTouchListener { _, event ->
            handlePanelEdgeTouch(event)
            false
        }
    }

    private fun handlePanelEdgeTouch(event: MotionEvent) {
        if (disposed) return
        val w = panelGlass.width
        val h = panelGlass.height
        if (w <= 0 || h <= 0) return
        val x = event.x.coerceIn(0f, w.toFloat())
        val y = event.y.coerceIn(0f, h.toFloat())
        val distanceToEdge = minOf(x, y, w - x, h - y)
        val zone = dp(72f)
        val proximity = (1f - distanceToEdge / zone).coerceIn(0f, 1f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val glow = edgeGlow ?: return
                glow.touchX = x
                glow.touchY = y
                glow.cornerRadius = dp(prefs.panelCornerDp.toFloat())
                if (proximity > 0f) {
                    edgeAnimator?.cancel()
                    edgeAnimator = null
                    glow.intensity = 0.55f + 0.45f * proximity
                    panelGlass.rotationY = ((x / w) - 0.5f) * 15f * proximity
                    panelGlass.rotationX = -(((y / h) - 0.5f) * 15f * proximity)
                    panelGlass.translationZ = dp(11f) * proximity
                    panelGlass.invalidate()
                } else {
                    releaseEdgePress()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseEdgePress()
        }
    }

    private fun releaseEdgePress(animated: Boolean = true) {
        edgeAnimator?.cancel()
        edgeAnimator = null
        val glow = edgeGlow ?: return
        val fromIntensity = glow.intensity
        val fromX = panelGlass.rotationX
        val fromY = panelGlass.rotationY
        val fromZ = panelGlass.translationZ
        if (!animated || fromIntensity <= 0.001f) {
            glow.intensity = 0f
            panelGlass.rotationX = 0f
            panelGlass.rotationY = 0f
            panelGlass.translationZ = 0f
            return
        }
        edgeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 190L
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                glow.intensity = fromIntensity * (1f - t)
                panelGlass.rotationX = fromX * (1f - t)
                panelGlass.rotationY = fromY * (1f - t)
                panelGlass.translationZ = fromZ * (1f - t)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (edgeAnimator === this@apply) {
                        glow.intensity = 0f
                        panelGlass.rotationX = 0f
                        panelGlass.rotationY = 0f
                        panelGlass.translationZ = 0f
                        edgeAnimator = null
                    }
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (edgeAnimator === this@apply) edgeAnimator = null
                }
            })
            start()
        }
    }

    /** Chamado ao fechar o painel para nenhuma janela ficar torta. */
    fun resetEdgePress() = releaseEdgePress(false)

    private fun cancelPageAnimations() {
        pages.forEach { it.animate().cancel() }
    }

    private fun setPageImmediate(target: Int) {
        cancelPageAnimations()
        for (i in 0..1) {
            val view = pages[i]
            view.alpha = if (i == target) 1f else 0f
            view.translationX = if (i == target) 0f else {
                (if (i > target) 1 else -1) * pagesContainer.width.toFloat()
            }
            view.visibility = View.VISIBLE
            setChildrenEnabled(view, i == target)
        }
        page = target
    }

    private fun setChildrenEnabled(view: ViewGroup, enabled: Boolean) {
        for (i in 0 until view.childCount) view.getChildAt(i).isEnabled = enabled
    }

    /** Troca por seta: nada é recriado, apenas alfa/translação animados. */
    fun switchPage(direction: Int) {
        cancelTileRepeats()
        val target = (page + direction + 2) % 2
        if (target == page) return
        animateToPage(target, direction)
    }

    private fun animateToPage(target: Int, direction: Int) {
        val outgoing = pages[page]
        val incoming = pages[target]
        val width = pagesContainer.width.toFloat().coerceAtLeast(1f)
        cancelPageAnimations()
        incoming.visibility = View.VISIBLE
        setChildrenEnabled(incoming, true)
        setChildrenEnabled(outgoing, false)
        incoming.translationX = direction * width
        incoming.alpha = 1f

        suppressClickUntil = System.currentTimeMillis() + 140L
        val interpolator = DecelerateInterpolator(1.3f)
        outgoing.animate().translationX(-direction * width).alpha(0f)
            .setDuration(130L).setInterpolator(interpolator).start()
        incoming.animate().translationX(0f).alpha(1f)
            .setDuration(130L).setInterpolator(interpolator).start()
        page = target
        prefs.page = page
    }

    /** Arraste lateral: as páginas acompanham o dedo antes de decidir o destino. */
    private fun handlePageDrag(dx: Float, finished: Boolean, switch: Boolean) {
        if (disposed) return
        val width = pagesContainer.width.toFloat().coerceAtLeast(1f)
        if (!finished) {
            if (dragDirection == 0) {
                dragDirection = if (dx < 0) 1 else -1
                dragNeighbor = pages[(page + dragDirection + 2) % 2]
                cancelPageAnimations()
                dragNeighbor?.let { neighbor ->
                    neighbor.visibility = View.VISIBLE
                    neighbor.alpha = 1f
                    setChildrenEnabled(neighbor, true)
                }
                pagesContainer.performHapticFeedback(
                    android.view.HapticFeedbackConstants.CLOCK_TICK
                )
            }
            val bounded = dx.coerceIn(-width, width)
            pages[page].translationX = bounded
            dragNeighbor?.translationX = bounded + dragDirection * width
            return
        }

        val neighbor = dragNeighbor
        suppressClickUntil = System.currentTimeMillis() + 260L
        cancelTileRepeats()
        cancelPageAnimations()
        val outgoing = pages[page]
        val interpolator = DecelerateInterpolator(1.2f)

        if (switch && neighbor != null) {
            neighbor.animate().translationX(0f).alpha(1f)
                .setDuration(120L).setInterpolator(interpolator).start()
            outgoing.animate().translationX(-dragDirection * width).alpha(0f)
                .setDuration(120L).setInterpolator(interpolator).start()
            setChildrenEnabled(outgoing, false)
            page = (page + dragDirection + 2) % 2
            prefs.page = page
            pagesContainer.performHapticFeedback(
                android.view.HapticFeedbackConstants.CLOCK_TICK
            )
        } else {
            outgoing.animate().translationX(0f).alpha(1f)
                .setDuration(140L).setInterpolator(interpolator).start()
            neighbor?.animate()
                ?.translationX(dragDirection * width)?.alpha(0f)
                ?.setDuration(140L)?.setInterpolator(interpolator)?.start()
            setChildrenEnabled(neighbor ?: outgoing, false)
        }
        dragDirection = 0
        dragNeighbor = null
    }

    fun restorePage() {
        cancelTileRepeats()
        setPageImmediate(prefs.page.coerceIn(0, 1))
    }

    private data class Tile(
        val label: String,
        val icon: Int,
        val action: () -> Unit,
        val repeatMs: Long? = null
    )

    private fun pageOne(): List<Tile> = listOf(
        Tile("Power", R.drawable.ic_power, {
            host.closePanelNow()
            SystemActions.powerMenu()
        }),
        Tile(
            "Volume -", R.drawable.ic_volume_minus,
            { VolumeController.adjustMedia(service, -1) }, prefs.repeatDelayMs.toLong()
        ),
        Tile(
            "Volume +", R.drawable.ic_volume_plus,
            { VolumeController.adjustMedia(service, 1) }, prefs.repeatDelayMs.toLong()
        ),
        Tile(
            "Brilho -", R.drawable.ic_brightness_minus,
            { SystemActions.brightnessStep(-20) }, prefs.repeatDelayMs.toLong()
        ),
        Tile(
            "Brilho +", R.drawable.ic_brightness_plus,
            { SystemActions.brightnessStep(20) }, prefs.repeatDelayMs.toLong()
        ),
        Tile("Bloquear tela", R.drawable.ic_lock, {
            host.closePanelNow()
            SystemActions.screenOff()
        })
    )

    private fun pageTwo(): List<Tile> = listOf(
        Tile("Captura de tela", R.drawable.ic_screenshot, { host.takeCleanScreenshot() }),
        Tile("Lanterna", R.drawable.ic_torch, {
            val (_, message) = TorchController.toggle(service)
            host.toast(message)
        }),
        Tile("Trava de rotação", R.drawable.ic_rotation, {
            val auto = Settings.System.getInt(
                service.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1
            ) == 1
            SystemActions.setAutoRotation(!auto)
            host.toast(if (auto) "Rotação travada" else "Rotação automática ativada")
        }),
        Tile("Reiniciar sistema", R.drawable.ic_reboot, {
            confirmRootAction("Reiniciar o sistema?") { SystemActions.reboot() }
        }),
        Tile("Reiniciar em Recovery", R.drawable.ic_recovery, {
            confirmRootAction("Reiniciar em Recovery?") { SystemActions.reboot("recovery") }
        }),
        Tile("Reiniciar em Bootloader", R.drawable.ic_bootloader, {
            confirmRootAction("Reiniciar em Bootloader?") { SystemActions.reboot("bootloader") }
        })
    )

    private fun makeTile(tile: Tile): LiquidGlassFab = LiquidGlassFab(service).apply {
        setIconResource(tile.icon)
        setIconTint(Color.WHITE)
        minimumWidth = 0
        minimumHeight = 0
        contentDescription = tile.label
        material = GlassMaterial.CLEAR
        accessibilityMode = GlassAccessibilityMode.FORCE_FULL
        enableDynamicBackground = false
        enableBackdropBlur = false
        useShaderPipeline = false
        useHardwareBlurWhenPossible = false
        enableChromaticDispersion = false
        enableChromaticAberration = false
        enableEdgeHighlight = true
        edgeHighlightBorderWidth = dp(1f)
        edgeHighlightOpacity = 74f
        edgeSoftness = dp(0.5f)
        enableShadow = false
        cornerRadius = dp(999f)
        bevelWidth = dp(10f)
        refractionHeight = dp(28f)
        elasticity = 0.22f
        enablePressEffect = true
        pressScale = 0.90f
        setGlassTint(Color.BLACK, 0.22f)
        setCustomBackdropCapture { _ -> safeTinyBitmap() }
        val repeatMs = tile.repeatMs
        if (repeatMs != null) {
            // A animação é nossa, então desligamos a de pressão padrão da lib.
            enablePressEffect = false
            // Volume/brilho: primeiro passo no DOWN, depois repetição enquanto segura.
            val repeat = object : Runnable {
                override fun run() {
                    if (disposed) return
                    tile.action()
                    handler.postDelayed(this, repeatMs)
                }
            }
            isClickable = true
            setOnTouchListener { view, event ->
                if (System.currentTimeMillis() < suppressClickUntil) return@setOnTouchListener true
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        tile.action()
                        startHoldAnimation(view)
                        repeatRunnables += repeat
                        handler.postDelayed(repeat, 260L)
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(repeat)
                        repeatRunnables -= repeat
                        stopHoldAnimation(view)
                        true
                    }

                    else -> false
                }
            }
        } else {
            setOnClickListener { view ->
                if (System.currentTimeMillis() < suppressClickUntil) return@setOnClickListener
                view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                tile.action()
            }
        }
    }

    private fun applyAppearance() {
        val icon = if (lightBackdrop) 0xFF0A0E13.toInt() else Color.WHITE
        val tint = if (lightBackdrop) Color.WHITE else Color.BLACK
        val strength = if (lightBackdrop) 0.20f else 0.24f
        for (index in 0..1) {
            tiles[index].forEach { fab ->
                fab.setIconTint(icon)
                fab.setGlassTint(tint, strength)
                fab.invalidate()
            }
        }
        val arrow = if (lightBackdrop) 0xD90A0E13.toInt() else 0xD9FFFFFF.toInt()
        pagePrev.imageTintList = ColorStateList.valueOf(arrow)
        pageNext.imageTintList = ColorStateList.valueOf(arrow)
        edgeGlow?.cornerRadius = dp(prefs.panelCornerDp.toFloat())
    }

    private fun confirmRootAction(title: String, action: () -> Unit) {
        val dialog = AlertDialog.Builder(service)
            .setTitle(title)
            .setMessage("O comando será enviado pelo shell root persistente.")
            .setPositiveButton("Executar") { _, _ ->
                host.closePanelNow()
                action()
            }
            .setNegativeButton("Cancelar", null)
            .create()
        try {
            dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
        } catch (_: Exception) {
            host.closePanelNow()
            action()
        }
    }

    fun dispose() {
        disposed = true
        cancelTileRepeats()
        edgeAnimator?.cancel()
        edgeAnimator = null
        handler.removeCallbacksAndMessages(null)
        cropExecutor.shutdownNow()
        // Não reciclar nada aqui: a lib ainda pode estar desenhando o frame final.
        // Ela libera os próprios caches no onDetachedFromWindow; o GC faz o resto.
        backdrop = null
        processedBackdrop = null
        processedKey = ""
        edgeGlow = null
        fullOpticsReady = false
    }
}

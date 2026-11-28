package com.glassassist.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.Service
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import com.example.liquidglass.GlassAccessibilityMode
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView
import com.glassassist.R
import com.glassassist.core.Prefs
import com.glassassist.core.SystemActions
import com.glassassist.core.TorchController
import com.glassassist.core.VolumeController

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
    private val panelGlass: PanelGlassView = panelView.findViewById(R.id.panelGlass)
    private val pagesContainer: PagesContainer = panelView.findViewById(R.id.pagesContainer)
    private val pagePrev: ImageView = panelView.findViewById(R.id.pagePrev)
    private val pageNext: ImageView = panelView.findViewById(R.id.pageNext)
    private val pages = arrayOf<ViewGroup>(
        panelView.findViewById(R.id.pageZero),
        panelView.findViewById(R.id.pageOne)
    )
    private val tiles = arrayOf(mutableListOf<ImageButton>(), mutableListOf<ImageButton>())

    private var page = prefs.page.coerceIn(0, 1)
    private var disposed = false

    /** Borda sempre levemente visível: a refração do slider aparece sem segurar. */
    private val baseEdgeIntensity = 0.42f

    private var dragDirection = 0
    private var dragNeighbor: ViewGroup? = null
    private var suppressClickUntil = 0L
    private val repeatRunnables = mutableSetOf<Runnable>()
    private val holdAnimators = mutableMapOf<View, ValueAnimator>()
    private var edgeAnimator: ValueAnimator? = null
    private var confirmDialog: AlertDialog? = null

    init {
        buildPages()
        installEdgePress()
        // A biblioteca recicla o bitmap devolvido: sempre uma cópia nova.
        // Backdrop neutro apenas para a bolinha não desenhar o fallback branco.
        // Nenhuma captura de tela é feita: o fundo da janela continua ao vivo.
        ballGlass.setCustomBackdropCapture { _ -> safeTinyBitmap() }
        applyOptics()
        setPageImmediate(page)
    }

    /** A lib recicla o bitmap devolvido, então cada chamada cria um 1x1 transparente. */
    private fun safeTinyBitmap(): Bitmap = try {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }
    } catch (_: Throwable) {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Float = value * service.resources.displayMetrics.density

    /** Largura de uma página; nunca zero, mesmo antes do primeiro layout. */
    private fun pageWidth(): Float {
        val measured = pagesContainer.width.takeIf { it > 0 }
            ?: panelView.width.takeIf { it > 0 }?.minus(dp(48))
        return measured?.coerceAtLeast(dp(188))?.toFloat() ?: dp(228f)
    }

    // ------------------------------------------------------------------ óptica

    /**
     * Configurações lidas e aplicadas na hora. Nenhuma janela tira foto do fundo:
     * o desfoque é nativo da janela, o escurecimento é um overlay preto semitransparente
     * e a refração aparece apenas como franja na borda do vidro.
     */
    fun applyOptics() {
        if (disposed) return
        try {
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
            // Sem elasticidade: qualquer scale > 1 estourava a janela quadrada da bolinha.
            elasticity = 0f
            enablePressEffect = false
            pressScale = 0.94f
            setGlassTint(Color.rgb(10, 16, 24), 0.20f)
            collectFrameStats = false
        }

        // Painel: desenho próprio leve. Desfoque e escurecimento são da janela.
        panelGlass.darkness = prefs.panelDarkness.coerceIn(0, 80) / 100f
        panelGlass.edgeRefraction = prefs.refractionStrength.coerceIn(0f, 0.3f)
        panelGlass.edgeIntensity = baseEdgeIntensity
        panelGlass.cornerRadius = dp(prefs.panelCornerDp.toFloat())
        panelGlass.invalidate()
        ballGlass.invalidate()
        } catch (t: Throwable) {
            // Nunca deixa uma exceção de propriedade da lib chegar ao onDraw.
            Log.e("GlassAssist", "Falha ao aplicar óptica", t)
        }
    }

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
        // Retornar false preserva clips, setas, paginação e botões.
        panelGlass.setOnTouchListener { _, event ->
            handlePanelEdgeTouch(event)
            false
        }
    }

    /**
     * Só um brilho local na borda. Nada de rotação/translação 3D: com a janela no
     * tamanho exato do painel, qualquer inclinação fazia uma ponta ultrapassar a
     * superfície e aparecer "cortada" por uma barreira invisível.
     */
    private fun handlePanelEdgeTouch(event: MotionEvent) {
        if (disposed) return
        val w = panelGlass.width
        val h = panelGlass.height
        if (w <= 0 || h <= 0) return
        val x = event.x.coerceIn(0f, w.toFloat())
        val y = event.y.coerceIn(0f, h.toFloat())
        val distanceToEdge = minOf(x, y, w - x, h - y)
        val zone = dp(36f)
        val raw = (1f - distanceToEdge / zone).coerceIn(0f, 1f)
        val proximity = raw * raw * 0.55f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                panelGlass.edgeTouchX = x
                panelGlass.edgeTouchY = y
                if (proximity > 0f) {
                    edgeAnimator?.cancel()
                    edgeAnimator = null
                    panelGlass.edgeIntensity = (baseEdgeIntensity + 0.14f * proximity).coerceIn(0f, 1f)
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
        val fromIntensity = panelGlass.edgeIntensity
        panelGlass.edgeTouchX = panelGlass.width / 2f
        panelGlass.edgeTouchY = panelGlass.height / 2f
        if (!animated || fromIntensity <= baseEdgeIntensity + 0.001f) {
            panelGlass.edgeIntensity = baseEdgeIntensity
            return
        }
        edgeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 170L
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { animator ->
                val remain = 1f - animator.animatedFraction
                panelGlass.edgeIntensity = baseEdgeIntensity + (fromIntensity - baseEdgeIntensity) * remain
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (edgeAnimator === this@apply) {
                        panelGlass.edgeIntensity = baseEdgeIntensity
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
        val width = pageWidth()
        for (i in 0..1) {
            val view = pages[i]
            view.alpha = if (i == target) 1f else 0f
            view.translationX = if (i == target) 0f else (if (i > target) 1 else -1) * width
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
        val width = pageWidth()
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
        val width = pageWidth()
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

    /**
     * Botão leve: um círculo desenhado por GradientDrawable. Antes eram 12 views de
     * vidro da biblioteca, cada uma com pipeline próprio — isso travava o painel.
     */
    private fun makeTile(tile: Tile): ImageButton = ImageButton(service).apply {
        setImageResource(tile.icon)
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(14), dp(14), dp(14), dp(14))
        minimumWidth = 0
        minimumHeight = 0
        contentDescription = tile.label
        isClickable = true
        isFocusable = false
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(46, 10, 16, 24))
            setStroke(dp(1), Color.argb(150, 235, 245, 255))
        }

        val repeatMs = tile.repeatMs
        if (repeatMs != null) {
            val repeat = object : Runnable {
                override fun run() {
                    if (disposed) return
                    tile.action()
                    handler.postDelayed(this, repeatMs)
                }
            }
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

                    else -> true
                }
            }
        } else {
            setOnClickListener { view ->
                if (System.currentTimeMillis() < suppressClickUntil) return@setOnClickListener
                view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                pressTile(view)
                tile.action()
            }
        }
    }

    /** Feedback de aperto sem depender de nenhum pipeline de vidro. */
    private fun pressTile(view: View) {
        view.animate().cancel()
        view.animate()
            .scaleX(0.9f).scaleY(0.9f)
            .setDuration(70L)
            .withEndAction {
                if (disposed) return@withEndAction
                view.animate().scaleX(1f).scaleY(1f).setDuration(130L).start()
            }
            .start()
    }

    private fun applyAppearance() {
        val arrow = 0xD9FFFFFF.toInt()
        pagePrev.imageTintList = ColorStateList.valueOf(arrow)
        pageNext.imageTintList = ColorStateList.valueOf(arrow)
    }

    private fun confirmRootAction(title: String, action: () -> Unit) {
        if (disposed) return
        confirmDialog?.let {
            try {
                it.dismiss()
            } catch (_: Throwable) {
            }
        }
        confirmDialog = null
        val dialog = AlertDialog.Builder(service)
            .setTitle(title)
            .setMessage("O comando será enviado pelo shell root persistente.")
            .setPositiveButton("Executar") { _, _ ->
                if (disposed) return@setPositiveButton
                confirmDialog = null
                host.closePanelNow()
                action()
            }
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnDismissListener {
            if (confirmDialog == dialog) confirmDialog = null
        }
        confirmDialog = dialog
        try {
            dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
        } catch (t: Exception) {
            confirmDialog = null
            try {
                dialog.dismiss()
            } catch (_: Throwable) {
            }
            // Um erro de janela nunca pode virar confirmação implícita de reboot.
            Log.w("GlassAssist", "Falha ao mostrar confirmação root", t)
            host.toast("Não foi possível confirmar; comando cancelado")
        }
    }

    fun dispose() {
        disposed = true
        cancelTileRepeats()
        edgeAnimator?.cancel()
        edgeAnimator = null
        confirmDialog?.let {
            try {
                it.dismiss()
            } catch (_: Throwable) {
            }
        }
        confirmDialog = null
        handler.removeCallbacksAndMessages(null)
        // A lib libera os próprios caches no onDetachedFromWindow.
        panelGlass.darkness = 0f
        panelGlass.edgeIntensity = 0f
    }
}

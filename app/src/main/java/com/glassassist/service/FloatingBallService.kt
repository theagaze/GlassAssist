package com.glassassist.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.example.liquidglass.LiquidGlassView
import com.glassassist.MainActivity
import com.glassassist.R
import com.glassassist.ServiceRestartReceiver
import com.glassassist.core.Prefs
import com.glassassist.core.SystemActions
import com.glassassist.core.VolumeController
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

data class GlassRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    val centerX: Int get() = x + w / 2
    val centerY: Int get() = y + h / 2
}

/**
 * Três janelas transparentes empilhadas: catcher (fora), painel e bolinha.
 *
 * O painel é instalado uma única vez, já com óptica e backdrop aquecidos. No toque,
 * ele faz morph a partir do centro da bolinha (scaleX/scaleY/alpha) enquanto a
 * bolinha desaparece — não há "sumir e depois abrir". Nenhuma captura acontece no
 * clique: o backdrop é renovado em segundo plano enquanto o painel está fechado.
 */
class FloatingBallService : Service(), PanelController.Host {

    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var catcherView: View? = null
    private var panelView: View? = null
    private var ballView: View? = null
    private var ballGlass: LiquidGlassView? = null
    private var panelGlass: LiquidGlassView? = null
    private var catcherParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var controller: PanelController? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var ballSize = 0
    private var panelWidth = 0
    private var panelHeight = 0
    private var ballX = 0
    private var ballY = 0
    private var panelRectNow = GlassRect(0, 0, 0, 0)

    private var panelOpen = false
    private var morphing = false
    private var backdropBusy = false
    private var lastCaptureRequestAt = 0L
    private val morphSprings = mutableListOf<SpringAnimation>()
    private var exitAnimator: ValueAnimator? = null
    private var snapSpring: SpringAnimation? = null

    /** Propriedade animável que move a janela da bolinha sem expandi-la. */
    private val ballXProperty = object : FloatPropertyCompat<View>("ballX") {
        override fun getValue(view: View?): Float = ballX.toFloat()

        override fun setValue(view: View?, value: Float) {
            val target = view ?: ballView ?: return
            ballX = value.toInt().coerceIn(0, (screenWidth - ballSize).coerceAtLeast(0))
            val lp = ballParams ?: return
            lp.x = ballX
            try {
                wm.updateViewLayout(target, lp)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    private enum class Mode { NONE, DRAG, VOLUME }
    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var volumeAccumulator = 0f
    private var holdFired = false
    private var lastDragDx = 0f
    private val touchSlop: Int by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private val holdRunnable = Runnable {
        holdFired = true
        ballGlass?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        SystemActions.screenOff()
    }
    private val idleRunnable = Runnable {
        if (panelOpen || morphing) return@Runnable
        ballView?.animate()?.alpha(prefs.inactiveAlpha)?.setDuration(320L)?.start()
    }
    private val backdropRunnable = Runnable {
        prewarmBackdrop()
        scheduleBackdropRefresh()
    }

    /**
     * A janela de overlay não pode aparecer sobre o keyguard. O mesmo receiver
     * recupera a bolinha quando o usuário desbloqueia (inclusive ao abrir as
     * configurações, que era onde ela desaparecia).
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> hideForLockScreen()
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> showBallIfUnlocked()
            }
        }
    }

    private fun keyguardLocked(): Boolean = try {
        (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true
    } catch (_: Throwable) {
        false
    }

    companion object {
        // Canal novo: o antigo já foi criado com IMPORTANCE_MIN e aparecia na shade.
        private const val CHANNEL_ID = "glassassist_silent_v2"
        private const val NOTIFICATION_ID = 1001
        private const val HOLD_MS = 320L
        private const val ACTION_SHOW = "com.glassassist.SHOW_OVERLAY"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var instance: FloatingBallService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, FloatingBallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun refreshSettings() {
            instance?.applySettings()
        }

        /** Chamado ao abrir as configurações: garante que a bolinha continue visível. */
        fun ensureOverlayVisible(context: Context) {
            val intent = Intent(context, FloatingBallService::class.java).setAction(ACTION_SHOW)
            try {
                context.startService(intent)
            } catch (_: Throwable) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        prefs = Prefs.get(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val bounds = try {
            wm.currentWindowMetrics.bounds
        } catch (_: Throwable) {
            val m = resources.displayMetrics
            android.graphics.Rect(0, 0, m.widthPixels, m.heightPixels)
        }
        screenWidth = bounds.width()
        screenHeight = bounds.height()
        ballSize = dp(prefs.ballSizeDp)
        ballX = if (prefs.ballX == Int.MIN_VALUE) {
            screenWidth - ballSize - dp(12)
        } else {
            prefs.ballX.coerceIn(0, (screenWidth - ballSize).coerceAtLeast(0))
        }
        ballY = if (prefs.ballY == Int.MIN_VALUE) {
            screenHeight / 3
        } else {
            prefs.ballY.coerceIn(0, (screenHeight - ballSize).coerceAtLeast(0))
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        try {
            installSurfaces()
            prefs.commitServiceEnabled(true)
        } catch (t: Throwable) {
            // Nunca derruba o app por falta de permissão ou falha de WindowManager.
            Log.e("GlassAssist", "Falha ao instalar o overlay", t)
            removeSurfaces()
            prefs.commitServiceEnabled(false)
            toast("Não foi possível iniciar o overlay: ${t.message}")
            stopSelf()
            return
        }
        try {
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            })
        } catch (_: Throwable) {
        }
        scheduleKeepAlive()
        showBallIfUnlocked()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW) showBallIfUnlocked()
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    @Suppress("DEPRECATION")
    private fun realDisplaySize(): Point {
        val point = Point(screenWidth, screenHeight)
        try {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(point)
        } catch (_: Throwable) {
        }
        return point
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_NONE
            ).apply {
                description = getString(R.string.channel_description)
                enableVibration(false)
            }
        )
    }

    /**
     * O foreground service é obrigatório para o sistema não matar o overlay,
     * mas a notificação não precisa ficar aparecendo: ícone transparente,
     * prioridade mínima e visibilidade secreta.
     */
    private fun buildNotification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_hidden)
        .setContentTitle("")
        .setContentText("")
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .setOngoing(false)
        .setShowWhen(false)
        .setOnlyAlertOnce(true)
        .setAutoCancel(false)
        .setPriority(Notification.PRIORITY_MIN)
        .setVisibility(Notification.VISIBILITY_SECRET)
        .setCategory(Notification.CATEGORY_SERVICE)
        .build()

    // ------------------------------------------------------------------ instalação

    private fun installSurfaces() {
        if (!Settings.canDrawOverlays(this)) {
            throw SecurityException("Permissão de sobreposição não concedida. Conceda via root ou em Configurações.")
        }
        panelWidth = minOf(screenWidth - dp(20), dp(prefs.panelSizeDp)).coerceAtLeast(dp(188))

        // 1) camada de toque fora (fica atrás do painel e da bolinha)
        val catcher = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = false
            visibility = View.GONE
            setOnClickListener { closePanelNow() }
        }
        catcherView = catcher
        val catcherLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        catcherParams = catcherLp
        wm.addView(catcher, catcherLp)

        // 2) painel (instalado uma vez, invisível em repouso)
        val panel = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        panelView = panel
        panelGlass = panel.findViewById(R.id.panelGlass)
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(
                (screenHeight - dp(20)).coerceAtLeast(dp(160)), View.MeasureSpec.AT_MOST
            )
        )
        panelHeight = panel.measuredHeight.coerceAtLeast(dp(176))

        val ball = LayoutInflater.from(this).inflate(R.layout.overlay_ball, null)
        ballView = ball
        ballGlass = ball.findViewById(R.id.ballGlass)

        controller = PanelController(this, panel, ballGlass!!, prefs, this).also {
            // O screencap cobre o display inteiro; usar o tamanho real evita que o
            // recorte do backdrop fique deslocado (acima/abaixo) conforme a barra
            // de navegação ou o recorte da janela do app.
            val real = realDisplaySize()
            it.setScreen(real.x, real.y)
        }

        panelRectNow = computePanelRect()
        val panelLp = WindowManager.LayoutParams(
            panelWidth, panelHeight, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = panelRectNow.x
            y = panelRectNow.y
        }
        panelParams = panelLp
        wm.addView(panel, panelLp)
        parkPanel()

        // 3) bolinha por cima de tudo
        // true é obrigatório: com false a biblioteca consome o DOWN e este listener
        // nunca recebe MOVE/UP, matando volume, arraste e toque simples.
        ballGlass?.setOnTouchListener { v, event -> handleBallTouch(v, event) }
        val ballLp = WindowManager.LayoutParams(
            ballSize, ballSize, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ballX
            y = ballY
        }
        ballParams = ballLp
        wm.addView(ball, ballLp)

        afterFirstLayout(ball) {
            controller?.restorePage()
            controller?.setGeometry(panelRectNow)
            controller?.applyOptics()
            updatePanelPosition()
            prewarmBackdrop()
            scheduleBackdropRefresh()
        }
    }

    private fun afterFirstLayout(view: View, attempt: Int = 0, block: () -> Unit) {
        if (view.isAttachedToWindow && view.width > 0 && view.height > 0) {
            block()
            return
        }
        if (attempt >= 12) return
        view.postDelayed({ afterFirstLayout(view, attempt + 1, block) }, 40L)
    }

    // ------------------------------------------------------------------ geometria

    private fun ballRect(): GlassRect = GlassRect(ballX, ballY, ballSize, ballSize)

    private fun computePanelRect(): GlassRect {
        val margin = dp(8)
        val maxX = (screenWidth - panelWidth - margin).coerceAtLeast(margin)
        val maxY = (screenHeight - panelHeight - margin).coerceAtLeast(margin)
        // Centraliza no botão: o pivô do morph fica dentro do painel.
        val x = ballRect().centerX - panelWidth / 2
        val y = ballRect().centerY - panelHeight / 2
        return GlassRect(
            x.coerceIn(margin, maxX),
            y.coerceIn(margin, maxY),
            panelWidth,
            panelHeight
        )
    }

    /** A janela do painel é reancorada; o transform de repouso continua casando com a bolinha. */
    private fun updatePanelPosition() {
        val rect = computePanelRect()
        panelRectNow = rect
        val lp = panelParams ?: return
        val view = panelView ?: return
        lp.x = rect.x
        lp.y = rect.y
        lp.width = rect.w
        lp.height = rect.h
        try {
            wm.updateViewLayout(view, lp)
        } catch (_: IllegalArgumentException) {
        }
        setCollapsedTransform()
        controller?.setGeometry(rect)
    }

    private fun setCollapsedPivot() {
        val view = panelView ?: return
        val ball = ballRect()
        val rect = panelRectNow
        if (rect.w <= 0 || rect.h <= 0) return
        view.pivotX = (ball.centerX - rect.x).toFloat()
        view.pivotY = (ball.centerY - rect.y).toFloat()
    }

    private fun collapsedScaleX(): Float =
        if (panelRectNow.w > 0) (ballSize.toFloat() / panelRectNow.w.toFloat()).coerceIn(0.05f, 1f) else 0.16f

    private fun collapsedScaleY(): Float =
        if (panelRectNow.h > 0) (ballSize.toFloat() / panelRectNow.h.toFloat()).coerceIn(0.05f, 1f) else 0.16f

    private fun setCollapsedTransform() {
        val view = panelView ?: return
        setCollapsedPivot()
        view.scaleX = collapsedScaleX()
        view.scaleY = collapsedScaleY()
    }

    private fun parkPanel() {
        val view = panelView ?: return
        controller?.resetEdgePress()
        morphSprings.forEach { it.cancel() }
        morphSprings.clear()
        setCollapsedTransform()
        view.alpha = 0f
        view.visibility = View.GONE
        catcherView?.visibility = View.GONE
    }

    // ------------------------------------------------------------------ morph

    private fun spring(
        view: View,
        property: FloatPropertyCompat<View>,
        final: Float,
        damping: Float = 0.82f,
        stiffness: Float = 450f
    ): SpringAnimation = SpringAnimation(view, property, final).apply {
        setStartVelocity(0f)
        spring = SpringForce()
            .setFinalPosition(final)
            .setDampingRatio(damping)
            .setStiffness(stiffness)
    }

    private fun openPanel() {
        if (panelOpen || morphing) return
        val panel = panelView ?: return
        val ball = ballView ?: return
        morphing = true
        panelOpen = true
        handler.removeCallbacks(idleRunnable)
        val oldExit = exitAnimator
        exitAnimator = null
        oldExit?.cancel()

        updatePanelPosition()
        controller?.applyOptics()
        controller?.restorePage()

        catcherView?.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        setCollapsedTransform()
        panel.alpha = 0.01f
        ball.alpha = 1f
        ball.visibility = View.VISIBLE

        val sx = spring(panel, SpringAnimation.SCALE_X, 1f)
        val sy = spring(panel, SpringAnimation.SCALE_Y, 1f)
        val pa = spring(panel, SpringAnimation.ALPHA, 1f).apply {
            setMinValue(0f); setMaxValue(1f)
        }
        val ba = spring(ball, SpringAnimation.ALPHA, 0f).apply {
            setMinValue(0f); setMaxValue(1f)
        }
        sx.addEndListener { _, canceled, _, _ -> if (!canceled) finishOpen() }
        morphSprings += listOf(sx, sy, pa, ba)
        morphSprings.forEach { it.start() }
    }

    private fun finishOpen() {
        morphSprings.forEach { it.cancel() }
        morphSprings.clear()
        panelView?.alpha = 1f
        panelView?.scaleX = 1f
        panelView?.scaleY = 1f
        ballView?.alpha = 0f
        ballView?.visibility = View.GONE
        morphing = false
        panelOpen = true
    }

    override fun closePanelNow() = closePanelNow(immediate = false)

    /**
     * Saída espelha a entrada: o painel encolhe até a bolinha e só depois ela
     * reaparece. Antes, as duas animações de alfa corriam juntas e causavam a
     * piscada (painel semi-transparente por cima da bolinha já visível).
     */
    private fun closePanelNow(immediate: Boolean) {
        if (!panelOpen && !morphing) return
        if (morphing && exitAnimator?.isRunning == true) return
        val panel = panelView
        val ball = ballView
        morphSprings.forEach { it.cancel() }
        morphSprings.clear()
        val oldExit = exitAnimator
        exitAnimator = null
        oldExit?.cancel()

        if (immediate || panel == null || ball == null) {
            parkPanel()
            ball?.alpha = 1f
            ball?.visibility = View.VISIBLE
            panelOpen = false
            morphing = false
            controller?.restorePage()
            resetIdleTimer()
            return
        }

        morphing = true
        ball.visibility = View.VISIBLE
        setCollapsedPivot()
        val fromX = panel.scaleX
        val fromY = panel.scaleY
        val fromAlpha = panel.alpha
        val toX = collapsedScaleX()
        val toY = collapsedScaleY()

        exitAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { animator ->
                val t = animator.animatedFraction
                panel.scaleX = fromX + (toX - fromX) * t
                panel.scaleY = fromY + (toY - fromY) * t
                panel.alpha = fromAlpha * (1f - t)
                ball.alpha = ((t - 0.55f) / 0.45f).coerceIn(0f, 1f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (exitAnimator === this@apply) finishClose()
                }
            })
            start()
        }
    }

    private fun finishClose() {
        exitAnimator = null
        parkPanel()
        ballView?.alpha = 1f
        ballView?.visibility = View.VISIBLE
        panelOpen = false
        morphing = false
        controller?.restorePage()
        resetIdleTimer()
    }

    // ------------------------------------------------------------------ gestos

    /** Feedback de pressão feito por nós: o listener consome o toque antes da lib. */
    private fun pressBall(pressed: Boolean) {
        val view = ballGlass ?: return
        view.animate().cancel()
        val scale = if (pressed) 0.92f else 1f
        view.animate().scaleX(scale).scaleY(scale).setDuration(120L).start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleBallTouch(v: View, event: MotionEvent): Boolean {
        if (panelOpen || morphing) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(idleRunnable)
                handler.removeCallbacks(holdRunnable)
                v.animate().cancel()
                v.alpha = 1f
                pressBall(true)
                downX = event.rawX
                downY = event.rawY
                lastX = event.rawX
                lastY = event.rawY
                mode = Mode.NONE
                holdFired = false
                volumeAccumulator = 0f
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                handler.postDelayed(holdRunnable, HOLD_MS)
            }

            MotionEvent.ACTION_MOVE -> {
                val dxTotal = event.rawX - downX
                val dyTotal = event.rawY - downY
                if (mode == Mode.NONE && hypot(dxTotal, dyTotal) > touchSlop.toFloat()) {
                    handler.removeCallbacks(holdRunnable)
                    mode = if (abs(dyTotal) > abs(dxTotal)) Mode.VOLUME else Mode.DRAG
                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                when (mode) {
                    Mode.VOLUME -> {
                        volumeAccumulator += lastY - event.rawY
                        lastY = event.rawY
                        val step = prefs.volumeStepPx.toFloat().coerceAtLeast(8f)
                        while (abs(volumeAccumulator) >= step) {
                            val raise = volumeAccumulator > 0
                            // AudioManager direto: resposta imediata, sem fila de shell.
                            VolumeController.adjustMedia(this, if (raise) 1 else -1)
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            volumeAccumulator += if (raise) -step else step
                        }
                    }

                    Mode.DRAG -> {
                        val lp = ballParams ?: return true
                        lastDragDx = event.rawX - lastX
                        lp.x += lastDragDx.toInt()
                        lp.y += (event.rawY - lastY).toInt()
                        lastX = event.rawX
                        lastY = event.rawY
                        ballX = lp.x.coerceIn(0, (screenWidth - ballSize).coerceAtLeast(0))
                        ballY = lp.y.coerceIn(0, (screenHeight - ballSize).coerceAtLeast(0))
                        lp.x = ballX
                        lp.y = ballY
                        try {
                            wm.updateViewLayout(v, lp)
                        } catch (_: IllegalArgumentException) {
                        }
                        // Não recalcula o backdrop a cada MOVE: só ao soltar.
                    }

                    Mode.NONE -> Unit
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(holdRunnable)
                pressBall(false)
                if (!holdFired && event.actionMasked == MotionEvent.ACTION_UP && mode == Mode.NONE) {
                    // Print novo começa antes do painel aparecer: a morph usa o
                    // backdrop aquecido e troca para o fresco sem delay visual.
                    prewarmBackdrop(force = true)
                    openPanel()
                }
                if (mode == Mode.DRAG) {
                    prefs.savePosition(ballX, ballY)
                    snapToEdge(event.actionMasked == MotionEvent.ACTION_UP)
                }
                mode = Mode.NONE
                volumeAccumulator = 0f
                resetIdleTimer()
            }
        }
        return true
    }

    /** Ao soltar, a bolinha vai obrigatoriamente para a borda para a qual foi empurrada. */
    private fun snapToEdge(animate: Boolean) {
        val view = ballView ?: return
        val margin = dp(10)
        val maxX = (screenWidth - ballSize - margin).coerceAtLeast(margin)
        val toRight = lastDragDx >= 0f
        val target = (if (toRight) maxX else margin).toFloat()
        prefs.ballEdge = if (toRight) 1 else 0

        val oldSnap = snapSpring
        snapSpring = null
        oldSnap?.cancel()
        if (!animate) {
            ballXProperty.setValue(view, target)
            prefs.savePosition(ballX, ballY)
            updatePanelPosition()
            return
        }
        val springAnim = SpringAnimation(view, ballXProperty, target).apply {
            spring = SpringForce()
                .setFinalPosition(target)
                .setDampingRatio(0.58f)
                .setStiffness(360f)
        }
        snapSpring = springAnim
        springAnim.addEndListener { _, canceled, _, _ ->
            if (snapSpring !== springAnim) return@addEndListener
            if (!canceled) {
                prefs.savePosition(ballX, ballY)
                updatePanelPosition()
            }
            snapSpring = null
        }
        springAnim.start()
    }

    private fun resetIdleTimer() {
        handler.removeCallbacks(idleRunnable)
        handler.postDelayed(idleRunnable, 3000L)
    }

    /** Nenhuma janela do overlay pode ser vista com a tela bloqueada. */
    private fun hideForLockScreen() {
        handler.removeCallbacks(idleRunnable)
        handler.removeCallbacks(holdRunnable)
        catcherView?.visibility = View.GONE
        panelView?.visibility = View.GONE
        ballView?.visibility = View.GONE
    }

    private fun showBallIfUnlocked() {
        if (keyguardLocked() || ballView == null) {
            hideForLockScreen()
            return
        }
        if (panelOpen || morphing) {
            // O painel já está no meio da animação; não mexe nas três janelas.
            catcherView?.visibility = View.VISIBLE
            panelView?.visibility = View.VISIBLE
            return
        }
        catcherView?.visibility = View.GONE
        panelView?.visibility = View.GONE
        ballView?.visibility = View.VISIBLE
        ballView?.alpha = 1f
        resetIdleTimer()
    }

    // ------------------------------------------------------------------ configurações

    private fun applySettings() {
        ballSize = dp(prefs.ballSizeDp)
        ballX = ballX.coerceIn(0, (screenWidth - ballSize).coerceAtLeast(0))
        ballY = ballY.coerceIn(0, (screenHeight - ballSize).coerceAtLeast(0))
        val oldSnap = snapSpring
        snapSpring = null
        oldSnap?.cancel()
        val lp = ballParams ?: return
        val view = ballView ?: return
        lp.width = ballSize
        lp.height = ballSize
        lp.x = ballX
        lp.y = ballY
        try {
            wm.updateViewLayout(view, lp)
        } catch (_: IllegalArgumentException) {
        }

        panelWidth = minOf(screenWidth - dp(20), dp(prefs.panelSizeDp)).coerceAtLeast(dp(188))
        panelView?.measure(
            View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(
                (screenHeight - dp(20)).coerceAtLeast(dp(160)), View.MeasureSpec.AT_MOST
            )
        )
        panelHeight = panelView?.measuredHeight?.coerceAtLeast(dp(176)) ?: panelHeight
        updatePanelPosition()
        controller?.rebuildTiles()
        controller?.applyOptics()
        prewarmBackdrop(force = true)
        toast("Configurações aplicadas")
    }

    // ------------------------------------------------------------------ backdrop

    /**
     * Captura em segundo plano: o clique nunca espera pelo screencap. `force` é
     * usado na abertura para substituir o print antigo assim que ele chegar.
     * Com tela desligada não há motivo para capturar — economia de bateria.
     */
    private fun prewarmBackdrop(force: Boolean = false) {
        if (backdropBusy || panelOpen || morphing) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastCaptureRequestAt < if (force) 900L else 5000L) return
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power != null && !power.isInteractive) return
        if (keyguardLocked()) return
        backdropBusy = true
        lastCaptureRequestAt = now
        Thread {
            var bitmap: Bitmap? = null
            try {
                val file = File(cacheDir, "glass_backdrop.png")
                val result = SystemActions.captureScreenTo(file)
                if (result.ok) {
                    bitmap = BitmapFactory.decodeFile(
                        file.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 }
                    )
                }
                file.delete()
                // A própria bolinha aparece no screencap; substitui a região por um
                // trecho vizinho para o vidro não refratar uma cópia de si mesmo.
                bitmap?.let { eraseBallFromBackdrop(it) }
            } catch (t: Throwable) {
                Log.e("GlassAssist", "Falha na captura do backdrop", t)
                bitmap?.let { if (!it.isRecycled) it.recycle() }
                bitmap = null
            } finally {
                handler.post {
                    backdropBusy = false
                    val safe = bitmap
                    if (safe != null && !safe.isRecycled) {
                        val current = controller
                        if (current != null) current.setBackdrop(safe)
                        else safe.recycle()
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** Remove a bolinha do print usando um trecho vizinho da mesma imagem. */
    private fun eraseBallFromBackdrop(bitmap: Bitmap) {
        val real = realDisplaySize()
        if (real.x <= 0 || real.y <= 0) return
        val sx = bitmap.width.toFloat() / real.x
        val sy = bitmap.height.toFloat() / real.y
        val pad = dp(6)
        val x = ((ballX - pad) * sx).toInt().coerceIn(0, bitmap.width - 1)
        val y = ((ballY - pad) * sy).toInt().coerceIn(0, bitmap.height - 1)
        val w = ((ballSize + pad * 2) * sx).toInt().coerceIn(1, (bitmap.width - x).coerceAtLeast(1))
        val h = ((ballSize + pad * 2) * sy).toInt().coerceIn(1, (bitmap.height - y).coerceAtLeast(1))

        val sxSrc: Int
        val sySrc: Int
        when {
            y >= h + 4 -> { sxSrc = x; sySrc = y - h - 4 }
            y + h + 4 <= bitmap.height - h -> { sxSrc = x; sySrc = y + h + 4 }
            x >= w + 4 -> { sxSrc = x - w - 4; sySrc = y }
            x + w + 4 <= bitmap.width - w -> { sxSrc = x + w + 4; sySrc = y }
            else -> return
        }
        val region = try {
            Bitmap.createBitmap(bitmap, sxSrc, sySrc, w, h)
        } catch (_: Throwable) {
            return
        }
        // createBitmap() pode compartilhar os pixels com a origem; copia para
        // não desenhar um bitmap sobre si mesmo (causava artefatos no recorte).
        val patch = try {
            region.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Throwable) {
            region.recycle()
            return
        }
        region.recycle()
        try {
            Canvas(bitmap).drawBitmap(
                patch, Rect(0, 0, w, h),
                RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat()), null
            )
        } finally {
            patch.recycle()
        }
    }

    private fun scheduleBackdropRefresh() {
        handler.removeCallbacks(backdropRunnable)
        handler.postDelayed(backdropRunnable, 6000L)
    }

    override fun takeCleanScreenshot() {
        val wasOpen = panelOpen
        closePanelNow(immediate = true)
        catcherView?.visibility = View.GONE
        panelView?.visibility = View.GONE
        ballView?.visibility = View.GONE
        handler.postDelayed({
            Thread {
                var ok = false
                var message = "Falha inesperada na captura"
                try {
                    val result = SystemActions.captureToGallery(this)
                    ok = result.first
                    message = result.second
                } catch (t: Throwable) {
                    Log.e("GlassAssist", "Falha na captura da galeria", t)
                    message = t.message ?: message
                } finally {
                    handler.post {
                        toast(if (ok) "Captura salva na galeria" else "Falha na captura: $message")
                        ballView?.visibility = View.VISIBLE
                        ballView?.alpha = 1f
                        if (wasOpen) openPanel() else parkPanel()
                        resetIdleTimer()
                    }
                }
            }.apply { isDaemon = true }.start()
        }, 120L)
    }

    override fun toast(message: String) {
        handler.post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun scheduleKeepAlive() {
        val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ServiceRestartReceiver::class.java).setAction(ServiceRestartReceiver.ACTION)
        val pending = PendingIntent.getBroadcast(
            this, 7, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarm.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 300_000L,
            300_000L,
            pending
        )
    }

    /** Remove as três janelas com segurança, mesmo se uma delas nunca foi adicionada. */
    private fun removeSurfaces() {
        handler.removeCallbacksAndMessages(null)
        morphSprings.forEach { it.cancel() }
        morphSprings.clear()
        val oldExit = exitAnimator
        exitAnimator = null
        oldExit?.cancel()
        val oldSnap = snapSpring
        snapSpring = null
        oldSnap?.cancel()
        ballView?.animate()?.cancel()
        panelView?.animate()?.cancel()
        // Primeiro remove as janelas: a lib pode liberar caches no detach.
        // Só depois descarta o controlador, para nenhum draw chamar um bitmap
        // já descartado.
        val surfaces = listOf(ballView, panelView, catcherView)
        surfaces.forEach { view ->
            view?.let {
                try {
                    wm.removeViewImmediate(it)
                } catch (_: Exception) {
                }
            }
        }
        controller?.dispose()
        controller = null
        catcherView = null
        panelView = null
        ballView = null
        ballGlass = null
        panelGlass = null
        ballParams = null
        panelParams = null
        catcherParams = null
        panelOpen = false
        morphing = false
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Throwable) {
        }
        removeSurfaces()
        isRunning = false
        instance = null
        super.onDestroy()
    }
}

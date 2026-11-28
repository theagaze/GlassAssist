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
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.example.liquidglass.LiquidGlassView
import com.glassassist.MainActivity
import com.glassassist.R
import com.glassassist.ServiceRestartReceiver
import com.glassassist.core.Prefs
import com.glassassist.core.RootShellManager
import com.glassassist.core.SystemActions
import com.glassassist.core.VolumeController
import java.util.function.Consumer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

data class GlassRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    val centerX: Int get() = x + w / 2
    val centerY: Int get() = y + h / 2
}

/**
 * Janelas de overlay: catcher, painel, bolinha e o X inferior.
 *
 * A janela do painel tem um overscan transparente: o vidro fica no meio, então
 * brilho de borda, refração e a bolinha em escala reduzida nunca são cortados.
 * O morph é coordenado entre painel e bolinha para parecer um único objeto.
 */
class FloatingBallService : Service(), PanelController.Host {

    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var catcherView: View? = null
    private var panelRoot: View? = null
    private var panelGlass: PanelGlassView? = null
    private var ballView: View? = null
    private var ballGlass: LiquidGlassView? = null
    private var ballLogo: View? = null
    private var dismissView: View? = null

    private var catcherParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var dismissParams: WindowManager.LayoutParams? = null
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
    private var closing = false

    /** Um único progresso comanda geometria, canto, conteúdo e efeitos. */
    private val morphProgress = FloatValueHolder(0f)
    private var morphAnimator: ValueAnimator? = null
    private var morphStartRect = GlassRect(0, 0, 0, 0)
    private var morphEndRect = GlassRect(0, 0, 0, 0)

    private val snapSprings = mutableListOf<SpringAnimation>()
    private var snapRemaining = 0

    /** Move a janela da bolinha sem alterar o tamanho durante o snap. */
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

    private val ballYProperty = object : FloatPropertyCompat<View>("ballY") {
        override fun getValue(view: View?): Float = ballY.toFloat()

        override fun setValue(view: View?, value: Float) {
            val target = view ?: ballView ?: return
            ballY = value.toInt().coerceIn(0, (screenHeight - ballSize).coerceAtLeast(0))
            val lp = ballParams ?: return
            lp.y = ballY
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
    private var lastDragDy = 0f
    private var dragBaseX = 0
    private var dragBaseY = 0
    private val touchSlop: Int by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private val holdRunnable = Runnable {
        holdFired = true
        ballGlass?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        SystemActions.screenOff()
    }

    private val idleRunnable = Runnable {
        if (panelOpen || morphing || hiddenByApp != null) return@Runnable
        ballView?.animate()?.alpha(prefs.inactiveAlpha)?.setDuration(320L)?.start()
    }

    private var dismissCenterX = 0
    private var dismissCenterY = 0
    private var magnetActive = false

    private val configRunnable = Runnable { handleConfigurationChanged() }

    /** Lista em memória: ao reiniciar o serviço/app ela volta a ficar vazia. */
    private val blockedApps = mutableSetOf<String>()
    private var hiddenByApp: String? = null
    private var lowPowerMode = false
    private var appMonitorWanted = false
    private var appMonitorScheduled = false
    private var appMonitorInFlight = false

    private val appMonitorRunnable = Runnable {
        appMonitorScheduled = false
        if (!appMonitorWanted || !isRunning || keyguardLocked() || blockedApps.isEmpty()) return@Runnable
        if (appMonitorInFlight) {
            scheduleAppMonitor()
            return@Runnable
        }
        appMonitorInFlight = true
        RootShellManager.executeAsync(FOREGROUND_LOOKUP, 2500) { result ->
            handler.post {
                appMonitorInFlight = false
                if (!appMonitorWanted || !isRunning) return@post
                val pkg = if (result.ok) parseForegroundPackage(result.output) else null
                if (pkg != null) {
                    if (pkg in blockedApps) {
                        if (hiddenByApp != pkg) enterHiddenState(pkg, showMessage = false)
                    } else if (hiddenByApp != null) {
                        hiddenByApp = null
                        lowPowerMode = false
                        showBallIfUnlocked()
                    }
                }
                scheduleAppMonitor()
            }
        }
    }

    private var blurSupported = false
    private var blurListener: Consumer<Boolean>? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> hideForLockScreen()
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> showBallIfUnlocked()
                Intent.ACTION_CONFIGURATION_CHANGED -> {
                    handler.removeCallbacks(configRunnable)
                    handler.postDelayed(configRunnable, 160L)
                }
            }
        }
    }

    private fun keyguardLocked(): Boolean = try {
        (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true
    } catch (_: Throwable) {
        false
    }

    private fun isLandscape(): Boolean =
        screenWidth > screenHeight ||
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    companion object {
        private const val CHANNEL_ID = "glassassist_silent_v2"
        private const val NOTIFICATION_ID = 1001
        private const val HOLD_MS = 320L
        private const val ACTION_SHOW = "com.glassassist.SHOW_OVERLAY"
        private const val OVERSCAN_DP = 24
        private const val APP_MONITOR_MS = 2800L
        private const val FOREGROUND_LOOKUP =
            "dumpsys window | grep -m 1 mCurrentFocus; " +
                "dumpsys activity activities | grep -m 1 mResumedActivity"
        private val FOREGROUND_PACKAGE = Regex("([A-Za-z0-9_.$]+)/[A-Za-z0-9_.$]+")

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

        refreshMetrics()
        ballSize = dp(prefs.ballSizeDp)
        ballX = if (prefs.ballX == Int.MIN_VALUE) {
            screenWidth - ballSize - dp(12)
        } else {
            prefs.ballX.coerceIn(0, (screenWidth - ballSize).coerceAtLeast(0))
        }
        // Ao iniciar, a bolinha sempre volta para o topo — nunca herda a posição
        // em que o X ficou na sessão anterior.
        ballY = verticalMargins().first
        restoreBallAnchor()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        installBlurListener()
        try {
            installSurfaces()
            // Persiste a âncora superior já no primeiro start.
            parkBallAtTop()
            prefs.commitServiceEnabled(true)
        } catch (t: Throwable) {
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
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
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

    private fun refreshMetrics() {
        val bounds = try {
            wm.currentWindowMetrics.bounds
        } catch (_: Throwable) {
            val m = resources.displayMetrics
            android.graphics.Rect(0, 0, m.widthPixels, m.heightPixels)
        }
        screenWidth = bounds.width().coerceAtLeast(1)
        screenHeight = bounds.height().coerceAtLeast(1)
    }

    /** Nunca deixa a largura escolhida cortar colunas de botões. */
    private fun computePanelWidth(): Int {
        val neededForTiles = dp(prefs.tileSizeDp) * 3 + dp(44)
        val chosen = maxOf(dp(prefs.panelSizeDp), neededForTiles)
        return minOf(screenWidth - dp(20), chosen).coerceAtLeast(dp(188))
    }

    private fun edgeMargins(): Pair<Int, Int> {
        val margin = dp(10)
        return margin to (screenWidth - ballSize - margin).coerceAtLeast(margin)
    }

    private fun verticalMargins(): Pair<Int, Int> {
        val margin = dp(10)
        return margin to (screenHeight - ballSize - margin).coerceAtLeast(margin)
    }

    /** 0=superior esquerda, 1=superior direita, 2=inferior esquerda, 3=inferior direita. */
    private fun cornerFrom(xRight: Boolean, yBottom: Boolean): Int =
        (if (xRight) 1 else 0) + (if (yBottom) 2 else 0)

    private fun restoreBallAnchor() {
        val (minX, maxX) = edgeMargins()
        val (minY, maxY) = verticalMargins()
        val corner = prefs.ballCorner
        if (isLandscape() && corner != Int.MIN_VALUE) {
            val value = corner.coerceIn(0, 3)
            ballX = if (value == 1 || value == 3) maxX else minX
            ballY = if (value >= 2) maxY else minY
            return
        }
        ballX = when (prefs.ballEdge) {
            0 -> minX
            1 -> maxX
            else -> ballX.coerceIn(minX, maxX)
        }
        ballY = ballY.coerceIn(minY, maxY)
    }

    // ------------------------------------------------------------------ notificação

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
        panelWidth = computePanelWidth()

        // 1) camada de toque fora do painel
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

        // 2) painel com overscan: a superfície é maior que o vidro visível.
        val overscan = dp(OVERSCAN_DP)
        val panel = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        panel.setPadding(overscan, overscan, overscan, overscan)
        panelRoot = panel
        panelGlass = panel.findViewById(R.id.panelGlass)
        measurePanelContent()

        val ball = LayoutInflater.from(this).inflate(R.layout.overlay_ball, null)
        ballView = ball
        ballGlass = ball.findViewById(R.id.ballGlass)
        ballLogo = ball.findViewById(R.id.ballLogo)
        controller = PanelController(this, panel, ballGlass!!, prefs, this)

        panelRectNow = computePanelRect()
        val panelLp = WindowManager.LayoutParams(
            panelWidth + overscan * 2,
            panelHeight + overscan * 2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = panelRectNow.x - overscan
            y = panelRectNow.y - overscan
        }
        panelParams = panelLp
        wm.addView(panel, panelLp)
        parkPanel()

        // 3) bolinha por cima do painel
        // A lib não pode consumir o DOWN, senão MOVE/UP nunca chegam aqui.
        ballGlass?.setOnTouchListener { v, event -> handleBallTouch(v, event) }
        val ballLp = WindowManager.LayoutParams(
            ballSize, ballSize, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ballX
            y = ballY
        }
        ballParams = ballLp
        wm.addView(ball, ballLp)

        installDismissButton()

        afterFirstLayout(ball) {
            controller?.restorePage()
            controller?.applyOptics()
            updatePanelPosition()
            applyPanelWindowBlur(false)
        }
    }

    private fun installDismissButton() {
        val size = dp(46)
        // Container com altura extra só dá espaço para o "puxão" para baixo;
        // o círculo permanece quadrado, então nunca vira elipse/losango.
        val container = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            visibility = View.GONE
        }
        val x = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(158, 10, 16, 24))
                setStroke(dp(1), Color.argb(190, 235, 245, 255))
            }
            alpha = 0.92f
            isClickable = true
            isFocusable = false
            setOnClickListener { hideCurrentApp() }
        }
        container.addView(
            x,
            FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        )
        dismissView = container
        val lp = WindowManager.LayoutParams(
            size, size + dp(20), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        dismissParams = lp
        wm.addView(container, lp)
        positionDismissButton()
    }

    private fun positionDismissButton() {
        val lp = dismissParams ?: return
        val view = dismissView ?: return
        val size = lp.width.coerceAtLeast(1)
        lp.x = (screenWidth - size) / 2
        lp.y = (screenHeight - size - dp(34)).coerceAtLeast(0)
        try {
            wm.updateViewLayout(view, lp)
        } catch (_: IllegalArgumentException) {
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

    private fun measurePanelContent() {
        val content = panelRoot?.findViewById<View>(R.id.panelContent) ?: return
        content.measure(
            View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(
                (screenHeight - dp(20)).coerceAtLeast(dp(160)), View.MeasureSpec.AT_MOST
            )
        )
        panelHeight = content.measuredHeight.coerceAtLeast(dp(176))
    }

    // ------------------------------------------------------------------ geometria

    private fun ballRect(): GlassRect = GlassRect(ballX, ballY, ballSize, ballSize)

    private fun computePanelRect(): GlassRect {
        val margin = dp(8)
        val maxX = (screenWidth - panelWidth - margin).coerceAtLeast(margin)
        val maxY = (screenHeight - panelHeight - margin).coerceAtLeast(margin)
        val x = ballRect().centerX - panelWidth / 2
        val y = ballRect().centerY - panelHeight / 2
        return GlassRect(
            x.coerceIn(margin, maxX),
            y.coerceIn(margin, maxY),
            panelWidth,
            panelHeight
        )
    }

    /** Aplica a geometria atual da janela do painel (rect visível + overscan). */
    private fun layoutPanel(rect: GlassRect) {
        panelRectNow = rect
        val lp = panelParams ?: return
        val view = panelRoot ?: return
        val overscan = dp(OVERSCAN_DP)
        lp.x = rect.x - overscan
        lp.y = rect.y - overscan
        lp.width = rect.w + overscan * 2
        lp.height = rect.h + overscan * 2
        try {
            wm.updateViewLayout(view, lp)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun updatePanelPosition() {
        // Durante o morph quem manda é applyMorphProgress(); fora dele o alvo é o rect final.
        if (!morphing) {
            val rect = computePanelRect()
            panelRectNow = rect
            layoutPanel(rect)
            if (panelOpen) {
                panelGlass?.morphRect = null
                panelGlass?.morphRadius = null
                panelRoot?.post {
                    if (isRunning && panelOpen && !morphing) controller?.restorePage()
                }
            }
        }
        if (!panelOpen && !morphing) setCollapsedTransform()
    }

    /**
     * Blur nativo do painel: intensidade própria (1..100). O raio acompanha o
     * progresso do morph, então o efeito abre e fecha junto com o vidro.
     */
    private fun applyPanelWindowBlur(active: Boolean) =
        applyPanelBlurProgress(if (active) 1f else 0f)

    private fun applyPanelBlurProgress(progress: Float) {
        val lp = panelParams ?: return
        val view = panelRoot ?: return
        val amount = prefs.blurRadius.coerceIn(1, 100) / 100f
        val full = dp(2f + 14f * amount).toInt()
        val radius =
            if (blurSupported && prefs.nativeBlurPanel) {
                (full * progress.coerceIn(0f, 1f)).toInt()
            } else 0
        setWindowBlur(lp, radius)
        try {
            wm.updateViewLayout(view, lp)
        } catch (_: IllegalArgumentException) {
        }
    }

    /**
     * Blur de fundo da bolinha: controle separado, limitado a 1..10. Antes ele
     * dividia o slider do painel e, em 100, desfocava a tela inteira.
     */
    private fun applyBallWindowBlur() {
        val lp = ballParams ?: return
        val view = ballView ?: return
        val visible = view.visibility == View.VISIBLE && !lowPowerMode &&
            hiddenByApp == null && !morphing
        val radius =
            if (visible && blurSupported && prefs.nativeBlurBall) {
                dp(prefs.screenBlurRadius.coerceIn(1, 10).toFloat()).toInt()
            } else 0
        setWindowBlur(lp, radius)
        try {
            wm.updateViewLayout(view, lp)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun setWindowBlur(lp: WindowManager.LayoutParams, radius: Int) {
        lp.setBlurBehindRadius(radius)
        lp.flags = if (radius > 0) {
            lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        } else {
            lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
        }
    }

    private fun installBlurListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        blurSupported = try {
            wm.isCrossWindowBlurEnabled
        } catch (_: Throwable) {
            false
        }
        val listener = Consumer<Boolean> { enabled ->
            blurSupported = enabled
            handler.post {
                if (isRunning) {
                    applyPanelWindowBlur(panelOpen && !lowPowerMode)
                    applyBallWindowBlur()
                }
            }
        }
        try {
            wm.addCrossWindowBlurEnabledListener(listener)
            blurListener = listener
        } catch (_: Throwable) {
            blurListener = null
        }
    }

    /** Rect da bolinha em coordenadas locais do vidro do painel. */
    private fun localRect(rect: GlassRect, anchor: GlassRect): RectF = RectF(
        (rect.x - anchor.x).toFloat(),
        (rect.y - anchor.y).toFloat(),
        (rect.x + rect.w - anchor.x).toFloat(),
        (rect.y + rect.h - anchor.y).toFloat()
    )

    /** Estado recolhido: só a região desenhada vira bolinha; a view não é redimensionada. */
    private fun setCollapsedTransform() {
        val view = panelGlass ?: return
        val anchor = panelRectNow.takeIf { it.w > 0 && it.h > 0 }
            ?: computePanelRect().also { panelRectNow = it }
        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = 1f
        view.cornerRadius = dp(prefs.panelCornerDp.toFloat()).coerceAtLeast(1f)
        view.morphRect = localRect(ballRect(), anchor)
        view.morphRadius = (ballSize / 2f).coerceAtLeast(1f)
        view.effectsAlpha = 0f
        panelRoot?.findViewById<View>(R.id.panelContent)?.alpha = 0f
    }

    private fun parkPanel() {
        controller?.resetEdgePress()
        cancelMorphAnimators()
        setCollapsedTransform()
        panelRoot?.visibility = View.GONE
        catcherView?.visibility = View.GONE
    }

    private fun setBallWindowSize(size: Int, centerX: Int, centerY: Int) {
        val lp = ballParams ?: return
        val view = ballView ?: return
        val s = size.coerceAtLeast(dp(24))
        lp.width = s
        lp.height = s
        lp.x = (centerX - s / 2).coerceIn(0, (screenWidth - s).coerceAtLeast(0))
        lp.y = (centerY - s / 2).coerceIn(0, (screenHeight - s).coerceAtLeast(0))
        try {
            wm.updateViewLayout(view, lp)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun restoreBallWindow() {
        setBallWindowSize(ballSize, ballX + ballSize / 2, ballY + ballSize / 2)
    }

    /**
     * Rede de segurança: se a animação não terminar, as janelas voltam ao repouso.
     */
    private val morphWatchdog = Runnable {
        if (!isRunning || !morphing) return@Runnable
        Log.w("GlassAssist", "Morph não finalizou; restaurando janelas")
        cancelToParkedState()
        hideDismissButton()
        showBallIfUnlocked()
    }

    private fun scheduleMorphWatchdog() {
        handler.removeCallbacks(morphWatchdog)
        handler.postDelayed(morphWatchdog, 2400L)
    }

    // ------------------------------------------------------------------ morph

    private fun <T> spring(
        target: T,
        property: FloatPropertyCompat<T>,
        final: Float,
        damping: Float = 0.76f,
        stiffness: Float = 250f,
        velocity: Float = 0f
    ): SpringAnimation = SpringAnimation(target, property, final).apply {
        setStartVelocity(velocity)
        spring = SpringForce()
            .setFinalPosition(final)
            .setDampingRatio(damping)
            .setStiffness(stiffness)
    }

    private fun cancelMorphAnimators() {
        handler.removeCallbacks(morphWatchdog)
        morphAnimator?.cancel()
        morphAnimator = null
    }

    private fun lerp(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).toInt()

    /**
     * Uma única superfície: a janela do painel fica no retângulo final e apenas a
     * região desenhada pelo vidro vai da bolinha até o painel. Nenhuma view muda de
     * tamanho, então o GridLayout dos botões não é medido de novo por frame.
     */
    private fun applyMorphProgress(raw: Float) {
        val glass = panelGlass ?: return
        val s = raw.coerceIn(0f, 1f)
        val anchor = panelRectNow.takeIf { it.w > 0 && it.h > 0 }
            ?: computePanelRect().also { panelRectNow = it }
        val rect = GlassRect(
            lerp(morphStartRect.x, morphEndRect.x, s),
            lerp(morphStartRect.y, morphEndRect.y, s),
            lerp(morphStartRect.w, morphEndRect.w, s).coerceAtLeast(1),
            lerp(morphStartRect.h, morphEndRect.h, s).coerceAtLeast(1)
        )
        glass.morphRect = localRect(rect, anchor)

        val ballRadius = (ballSize / 2f).coerceAtLeast(1f)
        val panelRadius = dp(prefs.panelCornerDp.toFloat()).coerceAtLeast(1f)
        glass.scaleX = 1f
        glass.scaleY = 1f
        glass.alpha = 1f
        glass.cornerRadius = panelRadius
        glass.morphRadius = ballRadius + (panelRadius - ballRadius) * s
        glass.effectsAlpha = s
        panelRoot?.findViewById<View>(R.id.panelContent)?.alpha =
            ((s - 0.45f) / 0.55f).coerceIn(0f, 1f)

        // A janela da bolinha só carrega a logo; o vidro visível é o do painel.
        ballGlass?.alpha = 0f
        val logoAlpha = ((0.32f - s) / 0.32f).coerceIn(0f, 1f)
        ballLogo?.alpha = logoAlpha
        val logoScale = 1f - 0.12f * s
        ballLogo?.scaleX = logoScale
        ballLogo?.scaleY = logoScale
    }

    /** Animação curta e sem overshoot: entrada/saída previsíveis, sem saltos. */
    private fun startMorph(target: Float, onEnd: () -> Unit) {
        morphAnimator?.cancel()
        val from = morphProgress.value.coerceIn(0f, 1f)
        morphProgress.value = from
        val opening = target > from
        // Progresso absoluto: 0 = bolinha, 1 = painel. Reverter no meio retoma o quadro atual.
        morphStartRect = ballRect()
        morphEndRect = panelRectNow.takeIf { it.w > 0 && it.h > 0 }
            ?: computePanelRect().also { panelRectNow = it }
        applyMorphProgress(from)
        val anim = ValueAnimator.ofFloat(from, target).apply {
            duration = if (opening) 300L else 250L
            interpolator = DecelerateInterpolator(1.2f)
            addUpdateListener { animation ->
                val value = animation.animatedValue as Float
                morphProgress.value = value
                applyMorphProgress(value)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (morphAnimator == this@apply) onEnd()
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (morphAnimator == this@apply) morphAnimator = null
                }
            })
        }
        morphAnimator = anim
        anim.start()
    }

    private fun openPanel() {
        if (lowPowerMode || hiddenByApp != null) return
        if (panelOpen && !morphing) return
        val root = panelRoot ?: return
        if (panelGlass == null || ballView == null || ballLogo == null) return

        morphing = true
        closing = false
        panelOpen = true
        handler.removeCallbacks(idleRunnable)
        hideDismissButton()
        cancelSnapSprings()
        cancelMorphAnimators()
        scheduleMorphWatchdog()

        controller?.applyOptics()
        // Janela fixa no retângulo final: a animação só mexe na região desenhada.
        val panelRect = computePanelRect()
        panelRectNow = panelRect
        layoutPanel(panelRect)
        // Corrige a paginação antes de o conteúdo começar a aparecer no morph.
        panelRoot?.post {
            if (isRunning && panelOpen && !closing) controller?.restorePage()
        }

        ballGlass?.animate()?.cancel()
        ballLogo?.animate()?.cancel()
        root.visibility = View.VISIBLE
        catcherView?.visibility = View.VISIBLE
        ballView?.visibility = View.VISIBLE
        ballView?.alpha = 1f
        ballView?.scaleX = 1f
        ballView?.scaleY = 1f
        restoreBallWindow()
        applyPanelWindowBlur(true)
        applyBallWindowBlur()

        startMorph(1f) { if (morphing && panelOpen && !closing) finishOpen() }
    }

    private fun finishOpen() {
        if (!morphing || !panelOpen || closing) return
        cancelMorphAnimators()
        morphProgress.value = 1f
        applyMorphProgress(1f)
        panelGlass?.morphRect = null
        panelGlass?.morphRadius = null
        // A view já está no tamanho final desde a instalação; só precisa reposicionar
        // as páginas depois de um frame, para pagesContainer.width estar correto.
        panelRoot?.post {
            if (isRunning && panelOpen && !morphing && !closing) controller?.restorePage()
        }
        panelRoot?.findViewById<View>(R.id.panelContent)?.alpha = 1f
        panelGlass?.effectsAlpha = 1f
        ballView?.visibility = View.GONE
        ballView?.alpha = 1f
        ballView?.scaleX = 1f
        ballView?.scaleY = 1f
        ballGlass?.alpha = 1f
        ballGlass?.scaleX = 1f
        ballGlass?.scaleY = 1f
        ballLogo?.alpha = 1f
        ballLogo?.scaleX = 1f
        ballLogo?.scaleY = 1f
        panelGlass?.scaleX = 1f
        panelGlass?.scaleY = 1f
        morphing = false
        panelOpen = true
        closing = false
        applyPanelWindowBlur(true)
        applyBallWindowBlur()
    }

    override fun closePanelNow() = closePanelNow(immediate = false)

    private fun closePanelNow(immediate: Boolean) {
        if (!panelOpen && !morphing) return
        if (closing) return
        closing = true
        morphing = true
        cancelMorphAnimators()
        scheduleMorphWatchdog()

        if (immediate || panelGlass == null || ballView == null) {
            cancelToParkedState()
            showBallIfUnlocked()
            return
        }

        catcherView?.visibility = View.GONE
        hideDismissButton()
        ballView?.visibility = View.VISIBLE
        ballView?.alpha = 1f
        ballView?.scaleX = 1f
        ballView?.scaleY = 1f
        restoreBallWindow()
        // Desfoque e escurecimento saem junto com o início do fechamento.
        applyPanelWindowBlur(false)
        applyBallWindowBlur()
        startMorph(0f) { if (closing) finishClose() }
    }

    /**
     * A bolinha visível no fim do morph é a do painel. Fazemos a janela da bolinha
     * aparecer exatamente no mesmo rect e só então escondemos o painel, com um
     * crossfade curto — sem isso a troca de superfície parecia um salto para baixo.
     */
    private fun finishClose() {
        val finalRect = morphStartRect.takeIf { it.w > 0 && it.h > 0 } ?: ballRect()
        ballX = finalRect.x.coerceIn(0, (screenWidth - ballSize).coerceAtLeast(0))
        ballY = finalRect.y.coerceIn(0, (screenHeight - ballSize).coerceAtLeast(0))
        restoreBallWindow()

        val ball = ballView
        if (ball == null || ballGlass == null) {
            cancelToParkedState()
            showBallIfUnlocked()
            return
        }
        ball.visibility = View.VISIBLE
        ball.alpha = 1f
        ballGlass?.alpha = 0f
        ballGlass?.animate()?.cancel()
        ballGlass?.animate()?.alpha(1f)?.setDuration(90L)?.withEndAction {
            if (!isRunning) return@withEndAction
            cancelToParkedState()
            showBallIfUnlocked()
        }?.start()
    }

    /** Cancela qualquer animação e deixa as janelas em estado de repouso. */
    private fun cancelToParkedState() {
        cancelMorphAnimators()
        morphProgress.value = 0f
        applyMorphProgress(0f)
        parkPanel()
        // A bolinha aparece exatamente onde o morph terminou.
        restoreBallWindow()
        ballView?.alpha = 1f
        ballView?.visibility = View.GONE
        ballView?.scaleX = 1f
        ballView?.scaleY = 1f
        ballGlass?.alpha = 1f
        ballGlass?.scaleX = 1f
        ballGlass?.scaleY = 1f
        ballLogo?.alpha = 1f
        ballLogo?.scaleX = 1f
        ballLogo?.scaleY = 1f
        panelOpen = false
        morphing = false
        closing = false
        applyPanelWindowBlur(false)
        applyBallWindowBlur()
    }

    // ------------------------------------------------------------------ gestos

    private fun pressBall(pressed: Boolean) {
        val view = ballGlass ?: return
        view.animate().cancel()
        ballLogo?.animate()?.cancel()
        if (pressed) {
            view.animate()
                .scaleX(0.94f).scaleY(0.94f)
                .setDuration(70L)
                .setInterpolator(DecelerateInterpolator(1.4f))
                .start()
            ballLogo?.animate()?.scaleX(0.94f)?.scaleY(0.94f)?.setDuration(70L)?.start()
            view.edgeHighlightOpacity = 110f
        } else {
            view.animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(160L)
                .setInterpolator(DecelerateInterpolator(1.2f))
                .start()
            ballLogo?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(160L)?.start()
            view.edgeHighlightOpacity = prefs.edgeOpacity.toFloat().coerceIn(30f, 100f)
        }
        view.invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleBallTouch(v: View, event: MotionEvent): Boolean {
        // O botão continua respondendo durante qualquer morph: toque na bolinha
        // inverte a animação (abre durante o fechamento e fecha durante a abertura).
        if (morphing || closing) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                if (closing) openPanel() else closePanelNow()
            }
            return true
        }
        if (panelOpen) return false
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
                lastDragDx = 0f
                lastDragDy = 0f
                dragBaseX = ballX
                dragBaseY = ballY
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                handler.postDelayed(holdRunnable, HOLD_MS)
            }

            MotionEvent.ACTION_MOVE -> {
                val dxTotal = event.rawX - downX
                val dyTotal = event.rawY - downY
                if (mode == Mode.NONE && hypot(dxTotal, dyTotal) > touchSlop.toFloat()) {
                    handler.removeCallbacks(holdRunnable)
                    // Deitado não há gesto de volume: qualquer direção move a bolinha.
                    mode = if (!isLandscape() && abs(dyTotal) > abs(dxTotal)) {
                        Mode.VOLUME
                    } else {
                        Mode.DRAG
                    }
                    ballGlass?.animate()?.cancel()
                    ballLogo?.animate()?.cancel()
                    if (mode == Mode.DRAG) showDismissButton()
                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                when (mode) {
                    Mode.VOLUME -> {
                        volumeAccumulator += lastY - event.rawY
                        lastY = event.rawY
                        val step = prefs.volumeStepPx.toFloat().coerceAtLeast(8f)
                        while (abs(volumeAccumulator) >= step) {
                            val raise = volumeAccumulator > 0
                            VolumeController.adjustMedia(this, if (raise) 1 else -1)
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            volumeAccumulator += if (raise) -step else step
                        }
                    }

                    Mode.DRAG -> {
                        val lp = ballParams ?: return true
                        lastDragDx = event.rawX - lastX
                        lastDragDy = event.rawY - lastY
                        lastX = event.rawX
                        lastY = event.rawY
                        val rawX = dragBaseX + dxTotal.toInt()
                        val rawY = dragBaseY + dyTotal.toInt()
                        applyDismissMagnet(lp, rawX, rawY)
                        ballX = lp.x.coerceIn(0, (screenWidth - ballSize).coerceAtLeast(0))
                        ballY = lp.y.coerceIn(0, (screenHeight - ballSize).coerceAtLeast(0))
                        lp.x = ballX
                        lp.y = ballY
                        try {
                            wm.updateViewLayout(v, lp)
                        } catch (_: IllegalArgumentException) {
                        }
                    }

                    Mode.NONE -> Unit
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(holdRunnable)
                pressBall(false)
                if (!holdFired && event.actionMasked == MotionEvent.ACTION_UP && mode == Mode.NONE) {
                    openPanel()
                }
                if (mode == Mode.DRAG) {
                    if (event.actionMasked == MotionEvent.ACTION_UP && droppedOnDismiss()) {
                        dropIntoDismiss()
                    } else {
                        hideDismissButton()
                        snapToEdge(event.actionMasked == MotionEvent.ACTION_UP)
                    }
                }
                mode = Mode.NONE
                volumeAccumulator = 0f
                resetIdleTimer()
            }
        }
        return true
    }

    private fun showDismissButton() {
        if (hiddenByApp != null || keyguardLocked()) return
        val view = dismissView ?: return
        val lp = dismissParams ?: return
        dismissCenterX = lp.x + lp.width / 2
        dismissCenterY = lp.y + lp.width / 2
        magnetActive = false
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationY = 0f
        view.animate().alpha(0.92f).setDuration(140L).start()
    }

    private fun hideDismissButton() {
        magnetActive = false
        dismissView?.animate()?.cancel()
        dismissView?.visibility = View.GONE
        dismissView?.alpha = 0.92f
        dismissView?.scaleX = 1f
        dismissView?.scaleY = 1f
        dismissView?.translationY = 0f
    }

    /**
     * Atração suave e não cumulativa: a posição base é sempre a do dedo; o X só
     * acrescenta um deslocamento limitado, então não "puxa com força".
     */
    private fun applyDismissMagnet(
        lp: WindowManager.LayoutParams,
        baseX: Int,
        baseY: Int
    ) {
        val view = dismissView
        val maxX = (screenWidth - ballSize).coerceAtLeast(0)
        val maxY = (screenHeight - ballSize).coerceAtLeast(0)
        var x = baseX.coerceIn(0, maxX)
        var y = baseY.coerceIn(0, maxY)
        var strength = 0f
        if (view != null && view.visibility == View.VISIBLE && hiddenByApp == null) {
            val cx = x + ballSize / 2f
            val cy = y + ballSize / 2f
            val dx = dismissCenterX - cx
            val dy = dismissCenterY - cy
            val distance = hypot(dx, dy)
            val radius = dp(112f).toFloat()
            if (distance < radius) {
                strength = (1f - distance / radius).coerceIn(0f, 1f)
                val limit = dp(26f).toFloat()
                val pullX = (dx * 0.26f * strength).coerceIn(-limit, limit)
                val pullY = (dy * 0.26f * strength).coerceIn(-limit, limit)
                x = (x + pullX).toInt().coerceIn(0, maxX)
                y = (y + pullY).toInt().coerceIn(0, maxY)
            }
        }
        lp.x = x
        lp.y = y
        magnetActive = strength > 0.3f
        view?.let { updateDismissPull(it, strength) }
    }

    /** Enquanto o dedo segura perto, o X só é puxado para baixo; nunca escala > 1. */
    private fun updateDismissPull(view: View, strength: Float) {
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationY = dp(8f).toFloat() * strength
        view.alpha = (0.92f + 0.08f * strength).coerceAtMost(1f)
    }

    private fun droppedOnDismiss(): Boolean {
        val view = dismissView ?: return false
        if (view.visibility != View.VISIBLE) return false
        val cx = ballX + ballSize / 2f
        val cy = ballY + ballSize / 2f
        return hypot(dismissCenterX - cx, dismissCenterY - cy) <= dp(34f).toFloat()
    }

    /** A bolinha entra no X (posição + encolhimento) antes de sumir do app atual. */
    private fun dropIntoDismiss() {
        cancelSnapSprings()
        val ball = ballView ?: run {
            parkBallAtTop()
            hideCurrentApp()
            return
        }
        val lp = ballParams ?: run {
            parkBallAtTop()
            hideCurrentApp()
            return
        }
        ball.animate().cancel()
        val startX = lp.x.toFloat()
        val startY = lp.y.toFloat()
        // O X continua visível e "engole" a bolinha; o alvo é o centro visual dele,
        // já deslocado para baixo enquanto o dedo estava segurando.
        val xView = dismissView?.takeIf { it.visibility == View.VISIBLE }
        val xStartScaleX = xView?.scaleX ?: 1f
        val xStartScaleY = xView?.scaleY ?: 1f
        val xStartTranslationY = xView?.translationY ?: 0f
        val xStartAlpha = xView?.alpha ?: 0.92f
        val visualCenterY = dismissCenterY + xStartTranslationY
        val targetX = (dismissCenterX - ballSize / 2f)
            .toInt().coerceIn(0, (screenWidth - ballSize).coerceAtLeast(0)).toFloat()
        val targetY = (visualCenterY - ballSize / 2f)
            .toInt().coerceIn(0, (screenHeight - ballSize).coerceAtLeast(0)).toFloat()
        ball.animate()
            .scaleX(0.16f).scaleY(0.16f).alpha(0f)
            .setDuration(210L)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 210L
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { animation ->
                val t = animation.animatedValue as Float
                lp.x = (startX + (targetX - startX) * t).toInt()
                lp.y = (startY + (targetY - startY) * t).toInt()
                ballX = lp.x
                ballY = lp.y
                try {
                    wm.updateViewLayout(ball, lp)
                } catch (_: IllegalArgumentException) {
                }
                xView?.let { view ->
                    val swallow = ((t - 0.55f) / 0.45f).coerceIn(0f, 1f)
                    view.scaleX = xStartScaleX + (0.35f - xStartScaleX) * swallow
                    view.scaleY = xStartScaleY + (0.35f - xStartScaleY) * swallow
                    view.alpha = xStartAlpha * (1f - swallow)
                    view.translationY = xStartTranslationY * (1f - swallow)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    ball.scaleX = 1f
                    ball.scaleY = 1f
                    ball.alpha = 1f
                    hideDismissButton()
                    parkBallAtTop()
                    hideCurrentApp()
                }
            })
        }.start()
    }

    /** Volta sempre para um canto superior; usado ao sair de um app oculto. */
    private fun parkBallAtTop() {
        val (minX, maxX) = edgeMargins()
        val topY = verticalMargins().first
        if (isLandscape()) {
            val right = ballX >= screenWidth / 2
            ballX = if (right) maxX else minX
            ballY = topY
            prefs.ballCorner = cornerFrom(right, false)
            prefs.ballEdge = if (right) 1 else 0
        } else {
            val right = when (prefs.ballEdge) {
                0 -> false
                1 -> true
                else -> ballX >= screenWidth / 2
            }
            ballX = if (right) maxX else minX
            ballY = topY
            prefs.ballEdge = if (right) 1 else 0
            prefs.clearCorner()
        }
        prefs.savePosition(ballX, ballY)
        restoreBallWindow()
    }

    private fun cancelSnapSprings() {
        snapSprings.forEach { it.cancel() }
        snapSprings.clear()
        snapRemaining = 0
    }

    /** Em pé ancora nas laterais; deitado escolhe o canto para onde foi jogada. */
    private fun snapToEdge(animate: Boolean) {
        val view = ballView ?: return
        val (minX, maxX) = edgeMargins()
        val (minY, maxY) = verticalMargins()
        val slop = dp(5).toFloat()
        val targetX: Float
        val targetY: Float

        if (isLandscape()) {
            val toRight = if (abs(lastDragDx) > slop) lastDragDx >= 0f else ballX >= screenWidth / 2
            val toBottom = if (abs(lastDragDy) > slop) lastDragDy >= 0f else ballY >= screenHeight / 2
            targetX = (if (toRight) maxX else minX).toFloat()
            targetY = (if (toBottom) maxY else minY).toFloat()
            val corner = cornerFrom(toRight, toBottom)
            prefs.ballCorner = corner
            prefs.ballEdge = if (toRight) 1 else 0
        } else {
            val toRight = if (abs(lastDragDx) > slop) lastDragDx >= 0f else ballX >= screenWidth / 2
            targetX = (if (toRight) maxX else minX).toFloat()
            targetY = ballY.coerceIn(minY, maxY).toFloat()
            prefs.ballEdge = if (toRight) 1 else 0
        }

        cancelSnapSprings()
        if (!animate) {
            ballXProperty.setValue(view, targetX)
            ballYProperty.setValue(view, targetY)
            prefs.savePosition(ballX, ballY)
            updatePanelPosition()
            return
        }

        fun addSpring(property: FloatPropertyCompat<View>, target: Float) {
            if (abs(property.getValue(view) - target) <= 0.5f) return
            snapRemaining += 1
            val anim = spring(view, property, target, 0.62f, 340f)
            anim.addEndListener { _, canceled, _, _ ->
                if (snapSprings.contains(anim) && !canceled) {
                    snapRemaining -= 1
                    if (snapRemaining <= 0) {
                        prefs.savePosition(ballX, ballY)
                        updatePanelPosition()
                        snapSprings.clear()
                    }
                }
            }
            snapSprings += anim
        }

        addSpring(ballXProperty, targetX)
        addSpring(ballYProperty, targetY)
        if (snapRemaining == 0) {
            prefs.savePosition(ballX, ballY)
            updatePanelPosition()
        } else {
            snapSprings.forEach { it.start() }
        }
    }

    private fun resetIdleTimer() {
        handler.removeCallbacks(idleRunnable)
        handler.postDelayed(idleRunnable, 3000L)
    }

    // ------------------------------------------------------------------ X / blocklist por app

    private fun parseForegroundPackage(output: String): String? {
        output.lines().forEach { line ->
            FOREGROUND_PACKAGE.findAll(line).forEach { match ->
                val pkg = match.groupValues[1]
                if (pkg != packageName && pkg.isNotBlank()) return pkg
            }
        }
        return null
    }

    private fun hideCurrentApp() {
        if (hiddenByApp != null || lowPowerMode) return
        hideDismissButton()
        RootShellManager.executeAsync(FOREGROUND_LOOKUP, 2500) { result ->
            handler.post {
                if (!isRunning) return@post
                val pkg = if (result.ok) parseForegroundPackage(result.output) else null
                if (pkg == null) {
                    toast("Não foi possível identificar o app atual")
                } else {
                    enterHiddenState(pkg, showMessage = true)
                }
            }
        }
    }

    private fun enterHiddenState(pkg: String, showMessage: Boolean) {
        blockedApps += pkg
        hiddenByApp = pkg
        lowPowerMode = true
        cancelToParkedState()
        parkBallAtTop()
        hideAllOverlayViews()
        startAppMonitor()
        if (showMessage) toast("Oculto neste app · economia de bateria")
    }

    private fun scheduleAppMonitor() {
        if (!appMonitorWanted || appMonitorScheduled || blockedApps.isEmpty()) return
        appMonitorScheduled = true
        handler.postDelayed(appMonitorRunnable, APP_MONITOR_MS)
    }

    private fun startAppMonitor() {
        if (blockedApps.isEmpty() || keyguardLocked()) return
        appMonitorWanted = true
        scheduleAppMonitor()
    }

    private fun stopAppMonitor() {
        appMonitorWanted = false
        appMonitorScheduled = false
        handler.removeCallbacks(appMonitorRunnable)
    }

    private fun hideAllOverlayViews() {
        catcherView?.visibility = View.GONE
        panelRoot?.visibility = View.GONE
        ballView?.visibility = View.GONE
        hideDismissButton()
        applyBallWindowBlur()
    }

    // ------------------------------------------------------------------ ciclo de tela / rotação

    private fun hideForLockScreen() {
        handler.removeCallbacks(idleRunnable)
        handler.removeCallbacks(holdRunnable)
        stopAppMonitor()
        cancelToParkedState()
        hideAllOverlayViews()
    }

    private fun showBallIfUnlocked() {
        if (hiddenByApp != null || lowPowerMode) {
            hideAllOverlayViews()
            startAppMonitor()
            return
        }
        if (keyguardLocked() || ballView == null) {
            hideForLockScreen()
            return
        }
        if (panelOpen || morphing) {
            catcherView?.visibility = if (closing) View.GONE else View.VISIBLE
            panelRoot?.visibility = View.VISIBLE
            hideDismissButton()
            return
        }
        catcherView?.visibility = View.GONE
        panelRoot?.visibility = View.GONE
        ballView?.visibility = View.VISIBLE
        ballView?.alpha = 1f
        ballView?.scaleX = 1f
        ballView?.scaleY = 1f
        ballView?.animate()?.cancel()
        ballGlass?.animate()?.cancel()
        ballGlass?.alpha = 1f
        ballGlass?.scaleX = 1f
        ballGlass?.scaleY = 1f
        ballGlass?.edgeHighlightOpacity = prefs.edgeOpacity.toFloat().coerceIn(30f, 100f)
        ballLogo?.animate()?.cancel()
        ballLogo?.alpha = 1f
        ballLogo?.scaleX = 1f
        ballLogo?.scaleY = 1f
        applyBallWindowBlur()
        resetIdleTimer()
        startAppMonitor()
    }

    private fun handleConfigurationChanged() {
        if (!isRunning) return
        // Geometria mudando no meio do morph deixaria pivô e alvos das molas errados.
        if (morphing) cancelToParkedState()
        refreshMetrics()
        ballSize = dp(prefs.ballSizeDp)
        restoreBallAnchor()
        val ball = ballView
        val lp = ballParams
        if (ball != null && lp != null) {
            lp.width = ballSize
            lp.height = ballSize
            lp.x = ballX
            lp.y = ballY
            try {
                wm.updateViewLayout(ball, lp)
            } catch (_: IllegalArgumentException) {
            }
            restoreBallWindow()
        }
        panelWidth = computePanelWidth()
        measurePanelContent()
        controller?.applyOptics()
        updatePanelPosition()
        applyPanelWindowBlur(panelOpen && !lowPowerMode)
        applyBallWindowBlur()
        positionDismissButton()
    }

    // ------------------------------------------------------------------ configurações

    private fun applySettings() {
        if (morphing) cancelToParkedState()
        refreshMetrics()
        ballSize = dp(prefs.ballSizeDp)
        restoreBallAnchor()
        cancelSnapSprings()
        controller?.resetEdgePress()
        controller?.rebuildTiles()

        val lp = ballParams
        val view = ballView
        if (lp != null && view != null) {
            lp.width = ballSize
            lp.height = ballSize
            lp.x = ballX
            lp.y = ballY
            try {
                wm.updateViewLayout(view, lp)
            } catch (_: IllegalArgumentException) {
            }
            restoreBallWindow()
        }

        panelWidth = computePanelWidth()
        measurePanelContent()
        controller?.applyOptics()
        updatePanelPosition()
        applyPanelWindowBlur(panelOpen && !lowPowerMode)
        applyBallWindowBlur()
        positionDismissButton()
        showBallIfUnlocked()
        toast("Configurações aplicadas")
    }

    override fun takeCleanScreenshot() {
        val wasOpen = panelOpen
        cancelToParkedState()
        hideAllOverlayViews()
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
                        if (!isRunning) return@post
                        toast(if (ok) "Captura salva na galeria" else "Falha na captura: $message")
                        if (wasOpen && hiddenByApp == null && !lowPowerMode) {
                            showBallIfUnlocked()
                            openPanel()
                        } else {
                            showBallIfUnlocked()
                        }
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
        // Não acorda o dispositivo: o app está na whitelist e o serviço é STICKY.
        alarm.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 900_000L,
            900_000L,
            pending
        )
    }

    private fun removeSurfaces() {
        handler.removeCallbacksAndMessages(null)
        stopAppMonitor()
        cancelMorphAnimators()
        cancelSnapSprings()
        blurListener?.let {
            try {
                wm.removeCrossWindowBlurEnabledListener(it)
            } catch (_: Throwable) {
            }
        }
        blurListener = null
        ballView?.animate()?.cancel()
        panelRoot?.animate()?.cancel()
        dismissView?.animate()?.cancel()

        val surfaces = listOf(dismissView, ballView, panelRoot, catcherView)
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
        panelRoot = null
        panelGlass = null
        ballView = null
        ballGlass = null
        ballLogo = null
        dismissView = null
        catcherParams = null
        panelParams = null
        ballParams = null
        dismissParams = null
        blockedApps.clear()
        hiddenByApp = null
        lowPowerMode = false
        panelOpen = false
        morphing = false
        closing = false
        appMonitorInFlight = false
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

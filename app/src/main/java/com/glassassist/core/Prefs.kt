package com.glassassist.core

import android.content.Context

class Prefs private constructor(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("glassassist_prefs", Context.MODE_PRIVATE)

    init {
        // Uma única migração: a refração antiga (0.12) ficava forte demais.
        if (!sp.contains(KEY_REFRACTION_MIGRATED)) {
            sp.edit()
                .putFloat(KEY_REFRACTION, DEFAULT_REFRACTION)
                .putBoolean(KEY_REFRACTION_MIGRATED, true)
                .apply()
        }
    }

    var ballSizeDp: Int
        get() = sp.getInt(KEY_BALL_SIZE, 64)
        set(value) = sp.edit().putInt(KEY_BALL_SIZE, value.coerceIn(48, 96)).apply()

    var inactiveAlpha: Float
        get() = sp.getFloat(KEY_INACTIVE_ALPHA, 0.45f)
        set(value) = sp.edit().putFloat(KEY_INACTIVE_ALPHA, value.coerceIn(0.2f, 1f)).apply()

    var blurRadius: Int
        get() = sp.getInt(KEY_BLUR, 40)
        set(value) = sp.edit().putInt(KEY_BLUR, value.coerceIn(0, 100)).apply()

    var refractionStrength: Float
        get() = sp.getFloat(KEY_REFRACTION, DEFAULT_REFRACTION)
        set(value) = sp.edit().putFloat(KEY_REFRACTION, value.coerceIn(0f, 0.3f)).apply()

    /**
     * 0 = tenta o blur nativo e usa vidro fosco se o sistema desligar o blur;
     * 1 = somente nativo; 2 = somente vidro fosco (mais barato).
     */
    var blurMode: Int
        get() = sp.getInt(KEY_BLUR_MODE, BLUR_MODE_AUTO)
        set(value) = sp.edit().putInt(KEY_BLUR_MODE, value.coerceIn(0, 2)).apply()

    var volumeStepPx: Int
        get() = sp.getInt(KEY_VOLUME_STEP, 34)
        set(value) = sp.edit().putInt(KEY_VOLUME_STEP, value.coerceIn(16, 80)).apply()

    /** Largura do painel em dp. */
    var panelSizeDp: Int
        get() = sp.getInt(KEY_PANEL_SIZE, 228)
        set(value) = sp.edit().putInt(KEY_PANEL_SIZE, value.coerceIn(188, 280)).apply()

    /** Diâmetro dos botões circulares dentro do painel, em dp. */
    var tileSizeDp: Int
        get() = sp.getInt(KEY_TILE_SIZE, 56)
        set(value) = sp.edit().putInt(KEY_TILE_SIZE, value.coerceIn(44, 72)).apply()

    /** Raio de borda do painel em dp. */
    var panelCornerDp: Int
        get() = sp.getInt(KEY_PANEL_CORNER, 34)
        set(value) = sp.edit().putInt(KEY_PANEL_CORNER, value.coerceIn(16, 48)).apply()

    /** Opacidade do brilho de borda (0-100). */
    var edgeOpacity: Int
        get() = sp.getInt(KEY_EDGE_OPACITY, 84)
        set(value) = sp.edit().putInt(KEY_EDGE_OPACITY, value.coerceIn(30, 100)).apply()

    /** Quanto escurecer o fundo capturado atrás do vidro do painel (0-80%). */
    var panelDarkness: Int
        get() = sp.getInt(KEY_PANEL_DARKNESS, 35)
        set(value) = sp.edit().putInt(KEY_PANEL_DARKNESS, value.coerceIn(0, 80)).apply()

    /** Intervalo de repetição ao segurar volume/brilho no painel. */
    var repeatDelayMs: Int
        get() = sp.getInt(KEY_REPEAT_DELAY, 130)
        set(value) = sp.edit().putInt(KEY_REPEAT_DELAY, value.coerceIn(60, 300)).apply()

    var ballX: Int
        get() = sp.getInt(KEY_BALL_X, Int.MIN_VALUE)
        set(value) = sp.edit().putInt(KEY_BALL_X, value).apply()

    var ballY: Int
        get() = sp.getInt(KEY_BALL_Y, Int.MIN_VALUE)
        set(value) = sp.edit().putInt(KEY_BALL_Y, value).apply()

    var gameModeUntil: Long
        get() = sp.getLong(KEY_GAME_MODE, 0L)
        set(value) = sp.edit().putLong(KEY_GAME_MODE, value).apply()

    var rootProvisioned: Boolean
        get() = sp.getBoolean(KEY_ROOT_PROVISIONED, false)
        set(value) = commitRootProvisioned(value)

    /** Só é true depois que WindowManager.addView() terminou sem exceções. */
    var serviceEnabled: Boolean
        get() = sp.getBoolean(KEY_SERVICE_ENABLED, false)
        set(value) = commitServiceEnabled(value)

    /** commit(): flags de inicialização precisam sobreviver a um crash imediato. */
    fun commitRootProvisioned(value: Boolean) {
        sp.edit().putBoolean(KEY_ROOT_PROVISIONED, value).commit()
    }

    fun commitServiceEnabled(value: Boolean) {
        sp.edit().putBoolean(KEY_SERVICE_ENABLED, value).commit()
    }

    var page: Int
        get() = sp.getInt(KEY_PAGE, 0).coerceIn(0, 1)
        set(value) = sp.edit().putInt(KEY_PAGE, value.coerceIn(0, 1)).apply()

    /** Borda de ancoragem: 0=esquerda, 1=direita. O Y continua livre. */
    var ballEdge: Int
        get() = sp.getInt(KEY_BALL_EDGE, Int.MIN_VALUE)
        set(value) = sp.edit().putInt(KEY_BALL_EDGE, value.coerceIn(0, 1)).apply()

    /** Legado: mantido para compatibilidade, mas ignorado na restauração. */
    var ballCorner: Int
        get() = sp.getInt(KEY_BALL_CORNER, Int.MIN_VALUE)
        set(value) = sp.edit().putInt(KEY_BALL_CORNER, value.coerceIn(0, 3)).apply()

    fun savePosition(x: Int, y: Int) {
        sp.edit().putInt(KEY_BALL_X, x).putInt(KEY_BALL_Y, y).apply()
    }

    fun saveCorner(corner: Int) {
        sp.edit().putInt(KEY_BALL_CORNER, corner.coerceIn(0, 3)).apply()
    }

    fun clearCorner() {
        sp.edit().remove(KEY_BALL_CORNER).apply()
    }

    companion object {
        const val BLUR_MODE_AUTO = 0
        const val BLUR_MODE_NATIVE = 1
        const val BLUR_MODE_VISUAL = 2

        private const val DEFAULT_REFRACTION = 0.05f

        private const val KEY_BALL_SIZE = "ball_size_dp"
        private const val KEY_INACTIVE_ALPHA = "inactive_alpha"
        private const val KEY_BLUR = "blur_radius"
        private const val KEY_REFRACTION = "refraction"
        private const val KEY_REFRACTION_MIGRATED = "refraction_migrated_05"
        private const val KEY_BLUR_MODE = "blur_mode"
        private const val KEY_VOLUME_STEP = "volume_step_px"
        private const val KEY_PANEL_SIZE = "panel_size_dp"
        private const val KEY_TILE_SIZE = "tile_size_dp"
        private const val KEY_PANEL_CORNER = "panel_corner_dp"
        private const val KEY_EDGE_OPACITY = "edge_opacity"
        private const val KEY_PANEL_DARKNESS = "panel_darkness"
        private const val KEY_REPEAT_DELAY = "repeat_delay_ms"
        private const val KEY_BALL_X = "ball_x"
        private const val KEY_BALL_Y = "ball_y"
        private const val KEY_GAME_MODE = "game_mode_until"
        private const val KEY_ROOT_PROVISIONED = "root_provisioned"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_PAGE = "panel_page"
        private const val KEY_BALL_CORNER = "ball_corner"
        private const val KEY_BALL_EDGE = "ball_edge"

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context).also { instance = it }
            }
    }
}

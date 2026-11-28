package com.glassassist.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * Vidro do painel desenhado diretamente, sem pipeline da biblioteca e sem captura.
 *
 * O morph não altera o tamanho desta view: [morphRect] é apenas a região desenhada
 * (em coordenadas locais). Assim o GridLayout dos botões é medido uma única vez,
 * no tamanho final, e a animação não faz requestLayout por frame.
 */
class PanelGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        // FrameLayout sem background tem WILL_NOT_DRAW ligado por padrão.
        setWillNotDraw(false)
    }

    var darkness: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 0.8f)
            invalidate()
        }

    var edgeRefraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 0.3f)
            invalidate()
        }

    var edgeIntensity: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var edgeTouchX: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var edgeTouchY: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** Fade dos efeitos junto com o morph: 0 = bolinha, 1 = painel. */
    var effectsAlpha: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var cornerRadius: Float = 0f
        set(value) {
            val next = value.coerceAtLeast(1f)
            if (field == next) return
            field = next
            invalidate()
        }

    /** Região visível do vidro em coordenadas locais; null = view inteira. */
    private val morphRectLocal = RectF()
    private var hasMorphRect = false

    var morphRect: RectF?
        get() = if (hasMorphRect) morphRectLocal else null
        set(value) {
            if (value == null) {
                if (!hasMorphRect) return
                hasMorphRect = false
            } else {
                // Reaproveita o RectF: o morph roda a cada frame.
                morphRectLocal.set(value)
                hasMorphRect = true
            }
            invalidate()
        }

    /** Raio da região do morph; null = [cornerRadius]. */
    var morphRadius: Float? = null
        set(value) {
            field = value
            invalidate()
        }

    private val shapeRect = RectF()
    private val clipPath = Path()
    private val contentClipPath = Path()
    private val glowPath = Path()
    private val glowRect = RectF()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(30, 226, 242, 252)
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(96, 235, 245, 255)
        style = Paint.Style.STROKE
    }
    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val depthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(60, 0, 0, 0)
    }
    private val hairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(120, 255, 255, 255)
    }
    private val fringePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var cachedGradient: RadialGradient? = null
    private var cachedGradientX = Float.NaN
    private var cachedGradientY = Float.NaN
    private var cachedGradientRadius = -1f
    private var cachedGradientAlpha = -1f

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (edgeTouchX == 0f && edgeTouchY == 0f) {
            edgeTouchX = w / 2f
            edgeTouchY = h / 2f
        }
        if (cornerRadius <= 1f) cornerRadius = dp(34f)
        strokePaint.strokeWidth = dp(1.2f)
    }

    private fun currentRect(): RectF {
        val morph = morphRect
        if (morph != null) {
            shapeRect.set(morph)
        } else {
            shapeRect.set(0f, 0f, width.toFloat(), height.toFloat())
        }
        return shapeRect
    }

    private fun currentRadius(rect: RectF): Float {
        val wanted = morphRadius ?: cornerRadius
        return wanted.coerceAtMost(min(rect.width(), rect.height()) / 2f).coerceAtLeast(1f)
    }

    override fun onDraw(canvas: Canvas) {
        val rect = currentRect()
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val radius = currentRadius(rect)

        clipPath.reset()
        clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.drawPath(clipPath, fillPaint)
        canvas.drawPath(clipPath, strokePaint)

        if (darkness > 0.001f && effectsAlpha > 0.001f) {
            darkPaint.alpha = (darkness * 255f * effectsAlpha).toInt().coerceIn(0, 204)
            canvas.drawPath(clipPath, darkPaint)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val rect = currentRect()
        if (rect.width() <= 0f || rect.height() <= 0f) {
            super.dispatchDraw(canvas)
            return
        }
        drawEdgeGlow(canvas, rect)

        val radius = currentRadius(rect)
        val save = canvas.save()
        contentClipPath.reset()
        contentClipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.clipPath(contentClipPath)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    private fun drawEdgeGlow(canvas: Canvas, rect: RectF) {
        val a = edgeIntensity * effectsAlpha
        if (a <= 0.001f || rect.width() <= 2f || rect.height() <= 2f) return

        val inset = dp(1f)
        glowRect.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
        val radius = currentRadius(rect)
        glowPath.reset()
        glowPath.addRoundRect(glowRect, radius, radius, Path.Direction.CW)

        val gx = edgeTouchX.coerceIn(rect.left, rect.right)
        val gy = edgeTouchY.coerceIn(rect.top, rect.bottom)
        val glowRadius = max(dp(44f), min(rect.width(), rect.height()) * 0.34f)

        depthPaint.alpha = (60 * a).toInt().coerceIn(0, 255)
        depthPaint.strokeWidth = dp(3f)
        canvas.save()
        canvas.translate(0f, dp(1.2f) * a)
        canvas.drawPath(glowPath, depthPaint)
        canvas.restore()

        glowPaint.strokeWidth = dp(1.8f + 1.2f * a)
        if (gx != cachedGradientX || gy != cachedGradientY ||
            glowRadius != cachedGradientRadius || a != cachedGradientAlpha
        ) {
            cachedGradient = RadialGradient(
                gx, gy, glowRadius,
                intArrayOf(
                    Color.argb((170 * a).toInt().coerceIn(0, 255), 255, 255, 255),
                    Color.argb((100 * a).toInt().coerceIn(0, 255), 190, 225, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.32f, 1f),
                Shader.TileMode.CLAMP
            )
            cachedGradientX = gx
            cachedGradientY = gy
            cachedGradientRadius = glowRadius
            cachedGradientAlpha = a
        }
        glowPaint.shader = cachedGradient
        canvas.drawPath(glowPath, glowPaint)
        glowPaint.shader = null

        hairlinePaint.alpha = (120 * a).toInt().coerceIn(0, 255)
        hairlinePaint.strokeWidth = dp(0.8f)
        canvas.drawPath(glowPath, hairlinePaint)

        val refraction = (edgeRefraction / 0.3f).coerceIn(0f, 1f)
        if (refraction > 0.001f) {
            val shift = dp(1.5f + 9f * refraction)
            val alpha = (30f + 95f * refraction) * a
            fringePaint.strokeWidth = dp(0.8f + 0.8f * refraction)
            fringePaint.color = Color.argb(alpha.toInt().coerceIn(0, 255), 255, 90, 90)
            canvas.save()
            canvas.translate(shift, 0f)
            canvas.drawPath(glowPath, fringePaint)
            canvas.restore()
            fringePaint.color = Color.argb(alpha.toInt().coerceIn(0, 255), 90, 150, 255)
            canvas.save()
            canvas.translate(-shift, 0f)
            canvas.drawPath(glowPath, fringePaint)
            canvas.restore()
        }
    }
}

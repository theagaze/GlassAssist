package com.glassassist.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Realce da borda que acompanha o dedo.
 *
 * Fica por cima do vidro, não intercepta toque e só aparece quando o dedo está
 * perto da borda. O brilho radial junto com a sombra deslocada dá a sensação
 * de que a borda tem espessura e está sendo pressionada de verdade.
 */
class EdgeGlowView(context: Context) : View(context) {

    var intensity: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var touchX: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var touchY: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var cornerRadius: Float = dp(34f)
        set(value) {
            field = value
            invalidate()
        }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val depthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(110, 0, 0, 0)
    }
    private val hairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(150, 255, 255, 255)
    }
    private val path = Path()
    private val rect = RectF()

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (intensity <= 0.001f || width <= 2 || height <= 2) return

        val inset = dp(1.5f)
        rect.set(inset, inset, width - inset, height - inset)
        path.reset()
        path.addRoundRect(
            rect,
            cornerRadius.coerceAtMost(min(width, height) / 2f),
            cornerRadius.coerceAtMost(min(width, height) / 2f),
            Path.Direction.CW
        )

        val a = intensity
        val gx = touchX.coerceIn(0f, width.toFloat())
        val gy = touchY.coerceIn(0f, height.toFloat())
        val radius = max(dp(56f), min(width, height) * 0.38f)

        // Sombra deslocada: dá volume à borda antes do brilho especular.
        depthPaint.alpha = (95 * a).toInt().coerceIn(0, 255)
        depthPaint.strokeWidth = dp(4.5f)
        canvas.save()
        canvas.translate(0f, dp(2.5f) * a)
        canvas.drawPath(path, depthPaint)
        canvas.restore()

        glowPaint.strokeWidth = dp(2.6f + 2f * a)
        glowPaint.shader = RadialGradient(
            gx, gy, radius,
            intArrayOf(
                Color.argb((230 * a).toInt().coerceIn(0, 255), 255, 255, 255),
                Color.argb((150 * a).toInt().coerceIn(0, 255), 190, 225, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.32f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, glowPaint)

        hairlinePaint.alpha = (170 * a).toInt().coerceIn(0, 255)
        hairlinePaint.strokeWidth = dp(1f)
        canvas.drawPath(path, hairlinePaint)
    }
}

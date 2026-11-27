package com.glassassist.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import com.example.liquidglass.LiquidGlassView
import kotlin.math.max
import kotlin.math.min

/**
 * Vidro do painel com duas responsabilidades próprias:
 *
 * 1) desenhar o fundo já processado (desfoque, refração e escurecimento) antes
 *    da camada de vidro da biblioteca, garantindo que os sliders tenham efeito;
 * 2) desenhar o realce 3D da borda por cima dos botões, seguindo o dedo.
 *
 * Não adiciona filhos, então não altera a altura medida do painel.
 */
class PanelGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LiquidGlassView(context, attrs, defStyleAttr) {

    /** Devolve um bitmap de propriedade do chamador; esta classe apenas desenha. */
    var backdropProvider: ((RectF) -> Bitmap?)? = null

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

    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val clipPath = Path()
    private val dstRect = RectF()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val depthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(110, 0, 0, 0)
    }
    private val hairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(150, 255, 255, 255)
    }
    private val glowPath = Path()
    private val glowRect = RectF()

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        drawProcessedBackdrop(canvas)
        super.onDraw(canvas)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawEdgeGlow(canvas)
    }

    private fun drawProcessedBackdrop(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val bitmap = try {
            backdropProvider?.invoke(RectF(0f, 0f, width.toFloat(), height.toFloat()))
        } catch (_: Throwable) {
            null
        } ?: return
        try {
            val r = cornerRadius.coerceAtMost(min(width, height) / 2f)
            clipPath.reset()
            clipPath.addRoundRect(
                0f, 0f, width.toFloat(), height.toFloat(), r, r, Path.Direction.CW
            )
            val save = canvas.save()
            canvas.clipPath(clipPath)
            dstRect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(bitmap, null, dstRect, backdropPaint)
            canvas.restoreToCount(save)
        } catch (_: Throwable) {
            // Um clipPath em canvas de hardware pode falhar em alguns devices;
            // nesse caso o painel continua desenhando sem o fundo processado.
        }
    }

    private fun drawEdgeGlow(canvas: Canvas) {
        val a = edgeIntensity
        if (a <= 0.001f || width <= 2 || height <= 2) return

        val inset = dp(1.5f)
        glowRect.set(inset, inset, width - inset, height - inset)
        val r = cornerRadius.coerceAtMost(min(width, height) / 2f)
        glowPath.reset()
        glowPath.addRoundRect(glowRect, r, r, Path.Direction.CW)

        val gx = edgeTouchX.coerceIn(0f, width.toFloat())
        val gy = edgeTouchY.coerceIn(0f, height.toFloat())
        val radius = max(dp(56f), min(width, height) * 0.38f)

        depthPaint.alpha = (95 * a).toInt().coerceIn(0, 255)
        depthPaint.strokeWidth = dp(4.5f)
        canvas.save()
        canvas.translate(0f, dp(2.5f) * a)
        canvas.drawPath(glowPath, depthPaint)
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
        canvas.drawPath(glowPath, glowPaint)

        hairlinePaint.alpha = (170 * a).toInt().coerceIn(0, 255)
        hairlinePaint.strokeWidth = dp(1f)
        canvas.drawPath(glowPath, hairlinePaint)
    }
}

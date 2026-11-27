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
import com.example.liquidglass.LiquidGlassView
import kotlin.math.max
import kotlin.math.min

/**
 * Vidro do painel sem captura de tela: o fundo visto é sempre o fundo real,
 * composto ao vivo pela janela transparente.
 *
 * - o escurecimento é um retângulo preto semi-transparente desenhado atrás do vidro;
 * - o desfoque é aplicado pela própria janela (backgroundBlurRadius);
 * - a refração atua só na borda, nunca distorcendo o conteúdo atrás do painel;
 * - o brilho 3D da borda é desenhado por cima, com intensidade baixa.
 */
class PanelGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LiquidGlassView(context, attrs, defStyleAttr) {

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

    private val clipPath = Path()
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
    private val glowPath = Path()
    private val glowRect = RectF()

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (edgeTouchX == 0f && edgeTouchY == 0f) {
            edgeTouchX = w / 2f
            edgeTouchY = h / 2f
        }
    }

    override fun onDraw(canvas: Canvas) {
        drawDarkness(canvas)
        super.onDraw(canvas)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawEdgeGlow(canvas)
    }

    /** Escurece ao vivo o que está atrás da janela — sem print e sem recorte. */
    private fun drawDarkness(canvas: Canvas) {
        if (darkness <= 0.001f || width <= 0 || height <= 0) return
        val r = cornerRadius.coerceAtMost(min(width, height) / 2f)
        clipPath.reset()
        clipPath.addRoundRect(
            0f, 0f, width.toFloat(), height.toFloat(), r, r, Path.Direction.CW
        )
        darkPaint.alpha = (darkness * 255f).toInt().coerceIn(0, 204)
        canvas.drawPath(clipPath, darkPaint)
    }

    private fun drawEdgeGlow(canvas: Canvas) {
        val a = edgeIntensity
        if (a <= 0.001f || width <= 2 || height <= 2) return

        val inset = dp(1f)
        glowRect.set(inset, inset, width - inset, height - inset)
        val r = cornerRadius.coerceAtMost(min(width, height) / 2f)
        glowPath.reset()
        glowPath.addRoundRect(glowRect, r, r, Path.Direction.CW)

        val gx = edgeTouchX.coerceIn(0f, width.toFloat())
        val gy = edgeTouchY.coerceIn(0f, height.toFloat())
        val radius = max(dp(44f), min(width, height) * 0.34f)

        depthPaint.alpha = (60 * a).toInt().coerceIn(0, 255)
        depthPaint.strokeWidth = dp(3f)
        canvas.save()
        canvas.translate(0f, dp(1.2f) * a)
        canvas.drawPath(glowPath, depthPaint)
        canvas.restore()

        glowPaint.strokeWidth = dp(1.8f + 1.2f * a)
        glowPaint.shader = RadialGradient(
            gx, gy, radius,
            intArrayOf(
                Color.argb((170 * a).toInt().coerceIn(0, 255), 255, 255, 255),
                Color.argb((100 * a).toInt().coerceIn(0, 255), 190, 225, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.32f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(glowPath, glowPaint)

        hairlinePaint.alpha = (120 * a).toInt().coerceIn(0, 255)
        hairlinePaint.strokeWidth = dp(0.8f)
        canvas.drawPath(glowPath, hairlinePaint)

        // Refração apenas como franja colorida na borda: o fundo continua intacto.
        // O slider muda tanto o deslocamento quanto a intensidade, então 0 e 100
        // têm diferença visível sem distorcer o conteúdo atrás da janela.
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

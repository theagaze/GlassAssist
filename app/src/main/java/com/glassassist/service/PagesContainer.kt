package com.glassassist.service

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Contêiner das páginas do painel.
 *
 * Um toque normal continua indo para os botões. Quando o movimento é lateral e passa
 * do slop, o contêiner assume o gesto e entrega o deslocamento ao controlador, que
 * move as páginas seguindo o dedo. O destino só é decidido no ACTION_UP.
 */
class PagesContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** dx em pixels; `finished` indica ACTION_UP/CANCEL; `switch` autoriza a troca. */
    var onPageDrag: ((dx: Float, finished: Boolean, switch: Boolean) -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var startX = 0f
    private var startY = 0f
    private var dragging = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.rawX
                startY = ev.rawY
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dx = ev.rawX - startX
                    val dy = ev.rawY - startY
                    if (abs(dx) > slop && abs(dx) > abs(dy)) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        onPageDrag?.invoke(ev.rawX - startX, false, false)
                        return true
                    }
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> onPageDrag?.invoke(ev.rawX - startX, false, false)
            MotionEvent.ACTION_UP -> {
                val dx = ev.rawX - startX
                val switch = abs(dx) > width * 0.22f
                onPageDrag?.invoke(dx, true, switch)
                dragging = false
            }

            MotionEvent.ACTION_CANCEL -> {
                onPageDrag?.invoke(ev.rawX - startX, true, false)
                dragging = false
            }
        }
        return true
    }
}

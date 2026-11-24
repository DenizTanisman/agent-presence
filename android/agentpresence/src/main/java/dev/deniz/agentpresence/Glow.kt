package dev.deniz.agentpresence

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.widget.LinearLayout

/**
 * The overlay's root. It draws the alert glow (SPEC §4 "Glow") behind its alerting children: a shadow of the
 * child's shape in its accent, radius [radius], 45 % opacity, or pulsing 30 % ↔ 60 % over 1.6 s while the agent waits
 * for the owner. The glow lives in this view's padding, so the window does not clip it. The pulse animator runs only
 * while a pulsing glow is on screen.
 */
internal class GlowRoot(c: Context) : LinearLayout(c) {
    /** [view] glows in [color]; [corner] < 0 means a circle (the minimal chip). */
    class Glow(val view: View, val color: Int, val pulse: Boolean, val corner: Float)

    var radius = 0f

    var glows: List<Glow> = emptyList()
        set(v) {
            field = v
            if (v.any { it.pulse }) {
                if (!pulse.isStarted) pulse.start()
            } else if (pulse.isStarted) {
                pulse.cancel()
            }
            invalidate()
        }

    private var level = 0.45f
    private val pulse = ValueAnimator.ofFloat(0.30f, 0.60f).apply {
        duration = 800
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            level = it.animatedValue as Float
            invalidate()
        }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = Rect()
    private val box = RectF()

    override fun onDetachedFromWindow() {
        pulse.cancel()
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: Canvas) {
        for (g in glows) {
            val v = g.view
            if (!v.isAttachedToWindow || v.width == 0 || v.height == 0) continue
            v.getDrawingRect(rect)
            offsetDescendantRectToMyCoords(v, rect)
            box.set(rect)
            // Follow the entrance fade of the shape, so the glow never appears before the card it belongs to.
            var fade = 1f
            var p: View? = v
            while (p != null && p !== this) {
                fade *= p.alpha
                p = p.parent as? View
            }
            if (fade <= 0.01f) continue
            val share = (if (g.pulse) level else 0.45f) * fade
            paint.color = Palette.alpha(Palette.SURFACE, fade)
            paint.setShadowLayer(radius, 0f, 0f, Palette.alpha(g.color, share))
            val r = if (g.corner < 0) box.height() / 2f else g.corner
            canvas.drawRoundRect(box, r, r, paint)
        }
        paint.clearShadowLayer()
        super.dispatchDraw(canvas)
    }
}

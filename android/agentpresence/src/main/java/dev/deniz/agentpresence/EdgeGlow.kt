package dev.deniz.agentpresence

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.RoundedCorner
import android.view.View

/**
 * SPEC "Edge glow while hidden": while the agent has hidden the island to see or tap what is under it, the screen
 * edges glow red instead, like Samsung's edge lighting, so the owner still knows not to touch. An 18 dp band follows
 * the display's rounded corners, `#FF3B30` fading inward under a bright 3 dp rim, breathing 70 % ↔ 100 % over 1.6 s.
 * The animator runs only while this view is on screen.
 */
internal class EdgeGlowView(c: Context) : View(c) {
    private val dp = resources.displayMetrics.density
    // Wide and bright enough to read as Samsung's edge lighting, not a thin line (owner, 2026-11-24).
    private val band = 18f * dp
    private val core = 3f * dp
    private val rings = 24
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val box = RectF()
    private var level = 1f
    private val breathe = ValueAnimator.ofFloat(0.7f, 1f).apply {
        duration = 800
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            level = it.animatedValue as Float
            invalidate()
        }
    }

    /** The screen's corner radius, from the display (0 on a square screen). */
    private fun corner(): Float {
        val d = display ?: return 0f
        val r = listOf(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
            RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT)
            .mapNotNull { d.getRoundedCorner(it)?.radius }
        return (r.maxOrNull() ?: 0).toFloat()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        breathe.start()
    }

    override fun onDetachedFromWindow() {
        breathe.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val radius = corner()
        val step = band / rings
        paint.strokeWidth = step + 0.5f
        for (i in 0 until rings) {
            // A soft glow fading inward (k^1.6), the whole band breathing.
            val k = 1f - i / rings.toFloat()
            paint.color = Palette.alpha(EDGE_RED, level * Math.pow(k.toDouble(), 1.6).toFloat() * 0.85f)
            val inset = i * step + step / 2f
            box.set(inset, inset, width - inset, height - inset)
            val r = (radius - inset).coerceAtLeast(0f)
            c.drawRoundRect(box, r, r, paint)
        }
        // A bright line right on the edge, like the lit rim of edge lighting.
        paint.strokeWidth = core
        paint.color = Palette.alpha(EDGE_CORE, level)
        val inset = core / 2f
        box.set(inset, inset, width - inset, height - inset)
        val r = (radius - inset).coerceAtLeast(0f)
        c.drawRoundRect(box, r, r, paint)
    }

    companion object {
        const val EDGE_RED = 0xFFFF3B30.toInt()
        const val EDGE_CORE = 0xFFFF5A4F.toInt()
    }
}

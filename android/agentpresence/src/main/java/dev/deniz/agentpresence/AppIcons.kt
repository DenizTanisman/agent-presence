package dev.deniz.agentpresence

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable

/**
 * The real icon of the app the agent drives (SPEC §4 "App identity"): a launcher app whose label matches the
 * session's `app`, case-insensitively ("instagram" finds Instagram). Looked up once per name; the manifest asks
 * only for launcher activities (`<queries>` MAIN/LAUNCHER), not for every package.
 */
internal object AppIcons {
    private val cache = HashMap<String, Drawable.ConstantState?>()

    fun icon(c: Context, name: String?): Drawable? {
        val key = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val state = synchronized(cache) {
            if (cache.containsKey(key.lowercase())) cache[key.lowercase()]
            else lookup(c, key).also { cache[key.lowercase()] = it }
        }
        return state?.newDrawable(c.resources)?.mutate()
    }

    private fun lookup(c: Context, name: String): Drawable.ConstantState? = runCatching {
        val pm = c.packageManager
        val all = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val hit = all.firstOrNull { it.loadLabel(pm).toString().trim().equals(name, ignoreCase = true) }
        hit?.loadIcon(pm)?.constantState
    }.getOrNull()

    /** The neutral glyph when the app is not on the phone: a rounded square in #2C2C2E (SPEC §4). */
    fun neutral(c: Context, size: Int): Drawable = GradientDrawable().apply {
        cornerRadius = size * 0.28f
        setColor(Palette.GLYPH)
        setSize(size, size)
    }
}

/**
 * The directive capsule's body (SPEC §4): near-black capsule, a hairline in the accent at 40 % and a soft glow of the
 * accent around it. The body is drawn inset by [glow] so the glow has room inside the view.
 */
internal class GlowCapsuleDrawable(
    private val fill: Int,
    private val border: Int,
    private val glowColor: Int,
    private val glow: Float,
    private val stroke: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    override fun draw(canvas: Canvas) {
        box.set(bounds)
        box.inset(glow, glow)
        val r = box.height() / 2f
        paint.style = Paint.Style.FILL
        paint.color = fill
        paint.setShadowLayer(glow, 0f, 0f, glowColor)
        canvas.drawRoundRect(box, r, r, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = border
        box.inset(stroke / 2f, stroke / 2f)
        canvas.drawRoundRect(box, r - stroke / 2f, r - stroke / 2f, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

package dev.deniz.agentpresence

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The window drawn over other apps (SPEC §2, Android column; look from SPEC §4 v1.2).
 *
 * - **Expanded** (top centre, under the status bar): the task list, one task under another. 5 s after start,
 *   intervene, a new directive, done or fail; 8 s after a tap; sticky while the agent waits for the owner.
 * - **Compact** (top centre): circle, app, the one datum. The resting size while a directive is active, with the
 *   directives in a separate capsule under it; also 3 s after a step change.
 * - **Minimal** (right under the camera hole): just the circle. The resting size of everything else, done/failed too,
 *   until the owner taps it.
 *
 * A second session is its own small circle next to the first. Main thread only.
 */
internal object Overlay {
    enum class Mode { EXPANDED, COMPACT, MINIMAL }

    const val ANNOUNCE_MS = 5_000L
    const val FLASH_MS = 3_000L
    const val TAP_MS = 8_000L
    private const val HERE = "phone"

    /**
     * Where the minimal circle sits. A window drawn by an app lies under the status bar, and on One UI the
     * status bar takes the touches in its band, so a circle inside the band could not be tapped open.
     * false (default, owner 2026-11-24: "kameranın yanına hizalanamıyorsa tam altında kalsın") = right under the
     * camera hole, centred on it, just below the band, where it can be tapped; true = inside the band, right of the
     * hole (looks like the Mac, but only the notification opens it).
     */
    var minimalInStatusBar = false

    private val main = Handler(Looper.getMainLooper())

    /** A size set for a while (announce, flash, tap, sticky attention). Absent = the session's resting size. */
    private val explicit = HashMap<String, Mode>()
    private val timers = HashMap<String, Runnable>()
    private val circles = HashMap<String, StepCircleView>()
    private var focus: String? = null
    private var app: Context? = null
    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var shape: String? = null

    /** Where the window is on screen, in pixels (null when nothing is shown): agents can avoid tapping there. */
    @Volatile var bounds: Rect? = null
        private set

    fun cue(ctx: Context, id: String, cue: Cue) {
        app = ctx.applicationContext
        when (cue) {
            Cue.ANNOUNCE -> show(id, Mode.EXPANDED, ANNOUNCE_MS, focus = true)
            Cue.STICKY -> show(id, Mode.EXPANDED, 0, focus = true)
            Cue.EXPAND -> show(id, Mode.EXPANDED, TAP_MS, focus = true)
            Cue.FLASH -> if (explicit[id] != Mode.EXPANDED) show(id, Mode.COMPACT, FLASH_MS, focus = false)
            Cue.COLLAPSE -> rest(id)
            Cue.REMOVE -> forget(id)
            Cue.NONE -> {}
        }
    }

    /** The owner tapped a circle or the pill. */
    fun open(id: String) = show(id, Mode.EXPANDED, TAP_MS, focus = true)

    /** Back to the resting size (Küçült, Tamam, a tap on the open card). */
    fun rest(id: String) {
        explicit.remove(id)
        cancel(id)
        if (focus == id) focus = null
    }

    fun forget(id: String) {
        rest(id)
        circles.remove(id)
    }

    private fun show(id: String, mode: Mode, ms: Long, focus: Boolean) {
        explicit[id] = mode
        if (focus) this.focus = id
        cancel(id)
        if (ms > 0) {
            val r = Runnable {
                timers.remove(id)
                rest(id)
                app?.let { render(it) }
            }
            timers[id] = r
            main.postDelayed(r, ms)
        }
    }

    private fun cancel(id: String) {
        timers.remove(id)?.let { main.removeCallbacks(it) }
    }

    /**
     * Who gets the big island (SPEC §2): an unseen attention, else the session whose announcement or tap is
     * showing, else a live session with an active directive (it must stay readable), else the newest live one.
     */
    fun primaryOf(list: List<Session>): Session? =
        list.filter { it.phase == Phase.WAITING && !it.acknowledged }.maxByOrNull { it.updated }
            ?: focus?.let { f -> list.firstOrNull { it.id == f && explicit[f] == Mode.EXPANDED } }
            ?: list.filter { it.hasDirectivesOn(HERE) }.maxByOrNull { it.updated }
            ?: list.filter { it.live }.maxByOrNull { it.updated }
            ?: list.maxByOrNull { it.updated }

    private fun restOf(s: Session) = if (s.hasDirectivesOn(HERE)) Mode.COMPACT else Mode.MINIMAL

    private fun modeOf(s: Session): Mode {
        if (s.phase == Phase.WAITING && !s.acknowledged) return Mode.EXPANDED
        val m = explicit[s.id] ?: restOf(s)
        // A directive never leaves the screen: never smaller than compact while one is active.
        return if (m == Mode.MINIMAL && s.hasDirectivesOn(HERE)) Mode.COMPACT else m
    }

    fun render(ctx: Context) {
        app = ctx.applicationContext
        val list = AgentPresence.sessions()
        if (list.isEmpty() || !Settings.canDrawOverlays(ctx)) {
            removeWindow()
            return
        }
        val c = ctx.applicationContext
        val r = ensureWindow(c) ?: return
        val p = primaryOf(list) ?: return
        val others = list.filter { it.id != p.id }
        val mode = modeOf(p)
        val chip = if (mode == Mode.MINIMAL) dp(c, 32) else dp(c, 44)

        r.removeAllViews()
        // The other sessions' circles sit beside the main one, unless that would squeeze it: the open card always
        // keeps its full width, and a compact pill shares its line with one circle at most. Then they get their
        // own row under it.
        val beside = when (mode) {
            Mode.MINIMAL -> others
            Mode.COMPACT -> if (others.size <= 1) others else emptyList()
            Mode.EXPANDED -> emptyList()
        }
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (mode == Mode.EXPANDED) Gravity.TOP else Gravity.CENTER_VERTICAL
        }
        row.addView(
            when (mode) {
                Mode.EXPANDED -> expanded(c, p)
                Mode.COMPACT -> compact(c, p, beside.size)
                Mode.MINIMAL -> circleChip(c, p, chip)
            },
        )
        for (o in beside) {
            row.addView(circleChip(c, o, chip), LinearLayout.LayoutParams(chip, chip).apply {
                marginStart = dp(c, if (mode == Mode.MINIMAL) 6 else 8)
            })
        }
        // Minimal: the first circle is the window's left edge, so place() can centre it under the camera.
        r.addView(row, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = if (mode == Mode.MINIMAL) Gravity.START else Gravity.CENTER_HORIZONTAL
        })
        val below = others - beside.toSet()
        if (below.isNotEmpty()) {
            val small = dp(c, 40)
            val line = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            for ((k, o) in below.withIndex()) {
                line.addView(circleChip(c, o, small), LinearLayout.LayoutParams(small, small).apply {
                    if (k > 0) marginStart = dp(c, 8)
                })
            }
            r.addView(line, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(c, 8)
            })
        }

        // Every active directive stays on screen: in the capsule under the pill. The expanded card is the task list
        // and never shows them, so while it is open the capsule of any other session still shows.
        val held = list.filter { it.hasDirectivesOn(HERE) && !(it.id == p.id && mode == Mode.EXPANDED) }
            .sortedBy { if (it.id == p.id) 0 else 1 }
        // Each capsule carries its glow inside its own bounds, so the margins pull it up by the glow: 8 dp of air
        // under the pill, 6 dp between two capsules.
        val caps = ArrayList<View>()
        for (s in held) for (v in capsules(c, s, named = s.id != p.id)) {
            r.addView(v, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = if (caps.isEmpty()) dp(c, 8) - glow(c) else dp(c, 6) - 2 * glow(c)
            })
            caps += v
        }

        place(c, r, mode, chip)

        val now = "${p.id}/$mode/${others.size}/${held.joinToString { it.id }}"
        if (now != shape) {
            row.alpha = 0f
            row.scaleX = 0.94f
            row.scaleY = 0.94f
            row.pivotY = 0f
            row.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, rr: Int, b: Int, ol: Int, ot: Int, orr: Int, ob: Int) {
                    v.removeOnLayoutChangeListener(this)
                    v.pivotX = if (mode == Mode.MINIMAL) 0f else v.width / 2f
                }
            })
            // A spring-like settle (SPEC §4 motion: response 0.4, damping 0.82).
            row.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(0.7f)).start()
            for (v in caps) {
                v.alpha = 0f
                v.scaleX = 0.92f
                v.scaleY = 0.92f
                v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(60).setDuration(320)
                    .setInterpolator(OvershootInterpolator(0.7f)).start()
            }
        }
        shape = now

        // Bind after the views are in the window, so state changes play their transition.
        for (s in list) circles[s.id]?.bind(lookOf(s), s.step, s.device?.takeIf { s.mirroredOn(HERE) })
        r.post { updateBounds() }
    }

    private fun lookOf(s: Session) = when (s.phase) {
        Phase.RUNNING -> if (s.total == 0) StepCircleView.Look.SPINNER else StepCircleView.Look.ACTIVE
        Phase.WAITING -> StepCircleView.Look.WAITING
        Phase.DONE -> StepCircleView.Look.DONE
        Phase.FAILED -> StepCircleView.Look.FAILED
    }

    // ------------------------------------------------------------------ window

    private fun insetsTop(c: Context): Int = (wm ?: c.getSystemService(WindowManager::class.java)).currentWindowMetrics
        .windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top

    /** The camera hole's bounding box at the top of the screen, if the phone has one. */
    private fun camera(c: Context): Rect? {
        val cut = (wm ?: c.getSystemService(WindowManager::class.java)).currentWindowMetrics.windowInsets.displayCutout
        return cut?.boundingRectTop?.takeIf { !it.isEmpty }
    }

    private fun ensureWindow(c: Context): LinearLayout? {
        root?.let { return it }
        val manager = c.getSystemService(WindowManager::class.java)
        val lp = WindowManager.LayoutParams(
            WRAP, WRAP,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "AgentPresence"
        }
        val v = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            clipToPadding = false
            clipChildren = false
        }
        return runCatching {
            manager.addView(v, lp)
            wm = manager
            root = v
            v
        }.getOrNull()
    }

    /**
     * Minimal: right under the camera hole, centred on it (or, with [minimalInStatusBar], beside it in the band).
     * Expanded and compact: top centre, under the status bar. Without a camera hole: top centre.
     */
    private fun place(c: Context, r: LinearLayout, mode: Mode, chip: Int) {
        val lp = r.layoutParams as? WindowManager.LayoutParams ?: return
        val top = insetsTop(c)
        val gx: Int
        val gy: Int
        val gravity: Int
        if (mode == Mode.MINIMAL) {
            r.setPadding(0, 0, 0, dp(c, 2))
            val cam = camera(c)
            val gap = dp(c, 8)
            gravity = Gravity.TOP or Gravity.START
            val centreX = cam?.centerX() ?: (c.resources.displayMetrics.widthPixels / 2)
            if (minimalInStatusBar) {
                gx = if (cam != null) cam.right + gap else centreX + dp(c, 24)
                gy = if (cam != null) (cam.centerY() - chip / 2).coerceAtLeast(0) else ((top - chip) / 2).coerceAtLeast(0)
            } else {
                gx = centreX - chip / 2
                gy = top + dp(c, 4)
            }
        } else {
            val pad = dp(c, 6)
            r.setPadding(pad, pad / 2, pad, pad)
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            gx = 0
            gy = top + dp(c, 4) - pad / 2
        }
        // An app window that wraps its content is first measured at the system's preferred dialog width (about
        // 320 dp), and single-line text inside it then ellipsizes early instead of asking for more. So the width is
        // measured here, against the whole screen, and set exactly.
        val screen = c.resources.displayMetrics
        r.measure(
            View.MeasureSpec.makeMeasureSpec(screen.widthPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screen.heightPixels, View.MeasureSpec.AT_MOST),
        )
        val w = r.measuredWidth.coerceAtMost(screen.widthPixels)
        if (lp.gravity != gravity || lp.x != gx || lp.y != gy || lp.width != w) {
            lp.gravity = gravity
            lp.x = gx
            lp.y = gy
            lp.width = w
            runCatching { wm?.updateViewLayout(r, lp) }
        }
    }

    private fun removeWindow() {
        root?.let { v -> runCatching { wm?.removeView(v) } }
        root = null
        shape = null
        bounds = null
    }

    /** True while the window is on screen: the foreground service may only start after this (Android 15+). */
    fun isShowing() = root != null

    private fun updateBounds() {
        val v = root ?: return
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        bounds = Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    // ------------------------------------------------------------------ pieces (SPEC §4 look)

    private fun circleFor(c: Context, id: String): StepCircleView {
        val v = circles.getOrPut(id) { StepCircleView(c) }
        (v.parent as? ViewGroup)?.removeView(v)
        return v
    }

    /** The card surface: #0E0E10, 1 px hairline, given radius. */
    private fun card(c: Context, radius: Int) = GradientDrawable().apply {
        cornerRadius = dp(c, radius).toFloat()
        setColor(Palette.SURFACE)
        setStroke(hair(c), Palette.BORDER)
    }

    private fun pillBg(c: Context, color: Int, radius: Float) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    private fun hair(c: Context) = maxOf(1, (c.resources.displayMetrics.density * 0.75f).toInt())

    private fun maxWidth(c: Context) = (c.resources.displayMetrics.widthPixels * 0.92f).toInt()

    private fun text(c: Context, t: CharSequence?, sp: Float, color: Int, semibold: Boolean = false) = TextView(c).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = if (semibold) Type.semibold else Type.regular
        includeFontPadding = false
    }

    /** An icon view of exactly [sizeDp] (an ImageView would otherwise draw the vector at its own 24 dp). */
    private fun iconView(c: Context, res: Int, sizeDp: Float, tint: Int): View {
        val px = dpf(c, sizeDp).toInt()
        return android.widget.ImageView(c).apply {
            setImageDrawable(c.getDrawable(res)!!.mutate().apply { setTint(tint) })
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(px, px, Gravity.CENTER)
        }
    }

    private fun icon(c: Context, res: Int, sizeDp: Float, tint: Int): android.graphics.drawable.Drawable =
        c.getDrawable(res)!!.mutate().apply {
            setTint(tint)
            val px = dpf(c, sizeDp).toInt()
            setBounds(0, 0, px, px)
        }

    /** The target app's real icon (or the neutral glyph), [sizeDp] square. */
    private fun appIcon(c: Context, s: Session, sizeDp: Int): View {
        val px = dp(c, sizeDp)
        return android.widget.ImageView(c).apply {
            setImageDrawable(AppIcons.icon(c, s.app) ?: AppIcons.neutral(c, px))
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(px, px)
            contentDescription = s.app
        }
    }

    /** A session as just its circle on a dark disc: minimal, or a second session next to the main one. */
    private fun circleChip(c: Context, s: Session, size: Int): View {
        val small = size < dp(c, 36)
        val f = FrameLayout(c).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Palette.CHIP)
                setStroke(hair(c), Palette.BORDER)
            }
            elevation = dp(c, if (small) 3 else 6).toFloat()
            contentDescription = listOfNotNull(s.agent, s.app).joinToString(" · ")
            setOnClickListener { act { AgentPresence.userExpand(c, s.id) } }
        }
        val circle = circleFor(c, s.id).apply { fill = if (small) 0.66f else 0.55f }
        f.addView(circle, FrameLayout.LayoutParams(size, size))
        return f
    }

    /** `app` in the title style and the step (or message) in the secondary style, on one line. */
    private fun titleLine(c: Context, main: String, second: String?): CharSequence {
        val sb = SpannableStringBuilder()
        span(c, sb, main, Palette.TEXT, 15f, Type.semibold)
        if (!second.isNullOrBlank()) {
            sb.append("  ")
            span(c, sb, second, Palette.TEXT2, 13f, Type.regular)
        }
        return sb
    }

    private fun compact(c: Context, s: Session, others: Int): View {
        val pill = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = card(c, 24)
            elevation = dp(c, 8).toFloat()
            minimumWidth = dp(c, 180)
            setPadding(dp(c, 2), 0, dp(c, 10), 0)
            setOnClickListener { act { AgentPresence.userExpand(c, s.id) } }
        }
        pill.addView(circleFor(c, s.id).apply { fill = 0.55f }, LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)))
        pill.addView(appIcon(c, s, 18))

        val closable = s.phase == Phase.DONE || s.phase == Phase.FAILED
        val right = rightSide(c, s)
        // The label gets whatever the circle, the icon, the right side, the close button and the other circles leave.
        val rightWidth = right?.let {
            it.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            it.measuredWidth
        } ?: 0
        val reserve = dp(c, 48) + dp(c, 18) + dp(c, 22) + rightWidth + (if (closable) dp(c, 36) else 0) + others * dp(c, 52)

        val second = when (s.phase) {
            Phase.RUNNING -> s.stepLabel ?: s.title
            else -> s.message ?: s.title
        }
        pill.addView(TextView(c).apply {
            text = titleLine(c, s.app ?: s.agent, second)
            letterSpacing = -0.013f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = maxWidth(c) - reserve
            includeFontPadding = false
            setPadding(dp(c, 8), 0, 0, 0)
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        right?.let { pill.addView(it, LinearLayout.LayoutParams(WRAP, WRAP)) }
        if (closable) {
            pill.addView(FrameLayout(c).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Palette.alpha(0xFFFFFFFF.toInt(), 0.10f))
                }
                contentDescription = Strings.close
                setOnClickListener { act { AgentPresence.userClose(c, s.id) } }
                addView(iconView(c, R.drawable.agentpresence_ic_close, 15f, Palette.TEXT))
            }, LinearLayout.LayoutParams(dp(c, 28), dp(c, 28)).apply { marginStart = dp(c, 6) })
        }
        return pill
    }

    /**
     * The one critical datum on the compact right side (SPEC §2): `detail`, else `step/total`; a status word in its
     * accent when the session waits, is over or was asked to stop.
     */
    private fun rightSide(c: Context, s: Session): TextView? {
        val (t, color) = when {
            s.live && s.stopRequested -> Strings.stopping to Palette.RED_TEXT
            s.phase == Phase.WAITING -> Strings.waiting to Palette.AMBER_TEXT
            s.phase == Phase.DONE -> Strings.done to Palette.GREEN_TEXT
            s.phase == Phase.FAILED -> Strings.failed to Palette.RED_TEXT
            !s.detail.isNullOrBlank() -> s.detail to Palette.TEXT
            s.total > 0 -> "${s.step}/${s.total}" to Palette.TEXT2
            s.directivesOn(HERE).any { !it.positive } -> Strings.handsOff to Palette.RED_TEXT
            else -> return null
        }
        return text(c, t, 13.5f, color, semibold = true).apply {
            isSingleLine = true
            setPadding(dp(c, 12), 0, dp(c, 4), 0)
            if (t == Strings.handsOff) {
                setCompoundDrawablesRelative(icon(c, R.drawable.agentpresence_ic_hand, 14f, Palette.RED_TEXT), null, null, null)
                compoundDrawablePadding = dp(c, 5)
            }
        }
    }

    /**
     * The directive capsules under the pill (SPEC §4): one per kind, the "don't"s with a red badge and a raised hand,
     * the "do"s with a green badge and a check, joined with " · ". A session that is not the main one is named.
     */
    private fun capsules(c: Context, s: Session, named: Boolean): List<View> {
        val who = if (named) (s.app ?: s.agent) + ": " else ""
        val (donts, dos) = s.directivesOn(HERE).partition { !it.positive }
        val out = ArrayList<View>()
        if (donts.isNotEmpty()) out += capsule(c, who + donts.joinToString(" · ") { it.text }, positive = false, s)
        if (dos.isNotEmpty()) out += capsule(c, who + dos.joinToString(" · ") { it.text }, positive = true, s)
        return out
    }

    /** Extra room around a capsule for its glow; [render] pulls the capsule up by it so the visible gap stays small. */
    private fun glow(c: Context) = dp(c, 10)

    private fun capsule(c: Context, t: String, positive: Boolean, s: Session): View {
        val accent = if (positive) Palette.GREEN else Palette.RED
        val g = glow(c)
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GlowCapsuleDrawable(
                fill = Palette.CAPSULE,
                border = Palette.alpha(accent, 0.40f),
                glowColor = Palette.alpha(accent, 0.25f),
                glow = g.toFloat(),
                stroke = hair(c).toFloat(),
            )
            minimumHeight = dp(c, 36) + 2 * g
            setPadding(g + dp(c, 7), g, g + dp(c, 15), g)
            setOnClickListener { act { AgentPresence.userExpand(c, s.id) } }
        }
        val badge = FrameLayout(c).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                if (positive) Palette.DO_GRADIENT else Palette.DONT_GRADIENT,
            ).apply { shape = GradientDrawable.OVAL }
        }
        badge.addView(iconView(c, if (positive) R.drawable.agentpresence_ic_check else R.drawable.agentpresence_ic_hand, 12f, 0xFFFFFFFF.toInt()))
        box.addView(badge, LinearLayout.LayoutParams(dp(c, 22), dp(c, 22)))
        box.addView(text(c, t, 13.5f, Palette.TEXT, semibold = true).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = maxWidth(c) - 2 * g - dp(c, 22 + 8 + 22)
            setPadding(dp(c, 8), 0, 0, 0)
        })
        return box
    }

    // ------------------------------------------------------------------ expanded: the task list

    private enum class RowState { DONE, ACTIVE, PENDING, WAITING, FAILED }

    private fun rowState(s: Session, i: Int): RowState = when {
        s.phase == Phase.DONE || i < s.finished -> RowState.DONE
        i == s.step - 1 || s.total == 0 -> when (s.phase) {
            Phase.WAITING -> RowState.WAITING
            Phase.FAILED -> RowState.FAILED
            else -> RowState.ACTIVE
        }
        else -> RowState.PENDING
    }

    private fun accentOf(st: RowState) = when (st) {
        RowState.DONE -> Palette.GREEN
        RowState.ACTIVE -> Palette.BLUE
        RowState.PENDING -> Palette.PENDING
        RowState.WAITING -> Palette.AMBER
        RowState.FAILED -> Palette.RED
    }

    private fun accentTextOf(st: RowState) = when (st) {
        RowState.DONE -> Palette.GREEN_TEXT
        RowState.ACTIVE -> Palette.BLUE_TEXT
        RowState.PENDING -> Palette.PENDING
        RowState.WAITING -> Palette.AMBER_TEXT
        RowState.FAILED -> Palette.RED_TEXT
    }

    private fun wordOf(st: RowState) = when (st) {
        RowState.DONE -> Strings.done
        RowState.ACTIVE -> Strings.running
        RowState.PENDING -> Strings.pending
        RowState.WAITING -> Strings.waiting
        RowState.FAILED -> Strings.failed
    }

    /** A status badge (SPEC §4): capsule, 11.5 semibold, accent at 16 % behind the accent text color. */
    private fun badge(c: Context, word: String, accent: Int, fg: Int) = text(c, word, 11.5f, fg, semibold = true).apply {
        isSingleLine = true
        background = pillBg(c, Palette.alpha(accent, 0.16f), dpf(c, 12f))
        setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3))
    }

    private fun expanded(c: Context, s: Session): View {
        val width = minOf(maxWidth(c), dp(c, 420))
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = card(c, 28)
            elevation = dp(c, 12).toFloat()
            setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 14))
            layoutParams = LinearLayout.LayoutParams(width, WRAP)
            setOnClickListener {
                act {
                    if (s.phase == Phase.WAITING && !s.acknowledged) AgentPresence.acknowledge(c, s.id)
                    else AgentPresence.userCollapse(c, s.id)
                }
            }
        }

        // Header: app icon · "agent · app" over the title · "Adım x/n", or the status as a badge.
        val head = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(appIcon(c, s, 22))
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 10), 0, dp(c, 8), 0)
        }
        val name = SpannableStringBuilder()
        span(c, name, listOfNotNull(s.agent, s.app).joinToString(" · "), Palette.TEXT, 15f, Type.semibold)
        if (s.mirroredOn(HERE)) {
            name.append("  ")
            span(c, name, if (s.device == "mac") Strings.onMac else Strings.onPhone, Palette.TEXT2, 11.5f, Type.semibold)
        }
        col.addView(TextView(c).apply {
            text = name
            letterSpacing = -0.013f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        })
        // Without steps the title is the one task row below, not repeated here.
        if (s.total > 0 && !s.title.isNullOrBlank()) {
            col.addView(text(c, s.title, 13f, Palette.TEXT2).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(c, 4), 0, 0)
            })
        }
        head.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        when {
            s.live && s.stopRequested -> head.addView(badge(c, Strings.stopping, Palette.RED, Palette.RED_TEXT))
            s.phase == Phase.WAITING -> head.addView(badge(c, Strings.waiting, Palette.AMBER, Palette.AMBER_TEXT))
            s.phase == Phase.DONE -> head.addView(badge(c, Strings.done, Palette.GREEN, Palette.GREEN_TEXT))
            s.phase == Phase.FAILED -> head.addView(badge(c, Strings.failed, Palette.RED, Palette.RED_TEXT))
            s.total > 0 -> head.addView(text(c, Strings.stepOf(s.step, s.total), 11.5f, Palette.TEXT2, semibold = true))
            !s.detail.isNullOrBlank() -> head.addView(text(c, s.detail, 11.5f, Palette.TEXT2, semibold = true))
        }
        box.addView(head)

        // The done message sits under the title.
        if (s.phase == Phase.DONE && !s.message.isNullOrBlank()) {
            box.addView(text(c, s.message, 13.5f, Palette.GREEN_TEXT, semibold = true).apply {
                background = pillBg(c, Palette.alpha(Palette.GREEN, 0.12f), dpf(c, 12f))
                setPadding(dp(c, 12), dp(c, 9), dp(c, 12), dp(c, 9))
                setCompoundDrawablesRelative(icon(c, R.drawable.agentpresence_ic_check, 16f, Palette.GREEN_TEXT), null, null, null)
                compoundDrawablePadding = dp(c, 8)
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(c, 12) })
        }

        box.addView(taskList(c, s), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(c, 12) })

        // Buttons (SPEC §4): capsules with a leading icon.
        val buttons = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val white10 = Palette.alpha(0xFFFFFFFF.toInt(), 0.10f)
        if (s.live) {
            if (s.stopRequested) {
                buttons.addView(button(c, Strings.stopping, R.drawable.agentpresence_ic_stop, Palette.alpha(Palette.RED, 0.18f), Palette.RED_TEXT, enabled = false) {})
            } else {
                buttons.addView(button(c, Strings.stop, R.drawable.agentpresence_ic_stop, Palette.alpha(Palette.RED, 0.18f), Palette.RED_TEXT) {
                    AgentPresence.requestStop(c, s.id)
                })
            }
            if (s.phase == Phase.WAITING && !s.acknowledged) {
                buttons.addView(button(c, Strings.ok, R.drawable.agentpresence_ic_check, Palette.AMBER, 0xFF000000.toInt()) {
                    AgentPresence.acknowledge(c, s.id)
                }, lpStart(c))
            } else {
                buttons.addView(button(c, Strings.minimize, R.drawable.agentpresence_ic_chevron_up, white10, Palette.TEXT) {
                    AgentPresence.userMinimize(c, s.id)
                }, lpStart(c))
            }
        } else {
            buttons.addView(button(c, Strings.close, R.drawable.agentpresence_ic_close, white10, Palette.TEXT) { AgentPresence.userClose(c, s.id) })
        }
        box.addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(c, 10) })
        return box
    }

    /** The tasks one under another on an inner panel: 20 dp circle · title (· detail or message) · status badge. */
    private fun taskList(c: Context, s: Session): View {
        val list = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = pillBg(c, Palette.PANEL, dpf(c, 16f))
            setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4))
        }
        data class Task(val index: Int, val title: String)
        val tasks = if (s.total == 0) listOf(Task(0, s.title ?: s.app ?: s.agent))
        else s.steps.mapIndexed { i, st -> Task(i, st.title) }

        // Long plans: a window of six rows around the current task, the rest counted.
        val maxRows = 6
        val cur = (s.step - 1).coerceIn(0, maxOf(0, tasks.size - 1))
        val start = if (tasks.size <= maxRows) 0 else (cur - 2).coerceIn(0, tasks.size - maxRows)
        val end = minOf(tasks.size, start + maxRows)
        if (start > 0) list.addView(moreRow(c, "+$start", Strings.done, Palette.GREEN, Palette.GREEN_TEXT))
        val tinted = setOf(RowState.ACTIVE, RowState.WAITING, RowState.FAILED)
        for (k in start until end) {
            // A hairline between plain rows only; a tinted row is its own separation.
            if (k > start && rowState(s, tasks[k - 1].index) !in tinted && rowState(s, tasks[k].index) !in tinted) list.addView(divider(c))
            list.addView(taskRow(c, s, tasks[k].index, tasks[k].title))
        }
        if (end < tasks.size) list.addView(moreRow(c, "+${tasks.size - end}", Strings.pending, Palette.PENDING, Palette.PENDING))
        return list
    }

    private fun taskRow(c: Context, s: Session, i: Int, title: String): View {
        val st = rowState(s, i)
        val accent = accentOf(st)
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(c, 44)
            setPadding(dp(c, 6), dp(c, 6), dp(c, 10), dp(c, 6))
            if (st == RowState.ACTIVE || st == RowState.WAITING || st == RowState.FAILED) {
                // The active row: its accent at 14 % fading to nothing, left to right.
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Palette.alpha(accent, 0.14f), Palette.alpha(accent, 0f)),
                ).apply { cornerRadius = dpf(c, 12f) }
            }
        }
        val circle = StepCircleView(c).apply {
            fill = 0.625f
            bind(
                when (st) {
                    RowState.DONE -> StepCircleView.Look.DONE
                    RowState.ACTIVE -> if (s.total == 0) StepCircleView.Look.SPINNER else StepCircleView.Look.ACTIVE
                    RowState.PENDING -> StepCircleView.Look.PENDING
                    RowState.WAITING -> StepCircleView.Look.WAITING
                    RowState.FAILED -> StepCircleView.Look.FAILED
                },
                i + 1, null,
            )
        }
        row.addView(circle, LinearLayout.LayoutParams(dp(c, 32), dp(c, 32)))

        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 8), 0, dp(c, 8), 0)
        }
        col.addView(
            when (st) {
                RowState.DONE -> text(c, title, 13.5f, Palette.TEXT_DONE)
                RowState.PENDING -> text(c, title, 13.5f, Palette.PENDING)
                else -> text(c, title, 13.5f, Palette.TEXT, semibold = true)
            }.apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        // Under the task: what it is waiting for or why it failed (SPEC), or the live datum.
        val sub = when (st) {
            RowState.WAITING, RowState.FAILED -> s.message
            RowState.ACTIVE -> s.detail
            else -> null
        }
        if (!sub.isNullOrBlank()) {
            col.addView(text(c, sub, 12.5f, if (st == RowState.ACTIVE) Palette.TEXT2 else accentTextOf(st)).apply {
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(c, 3), 0, 0)
                setLineSpacing(dpf(c, 1.5f), 1f)
            })
        }
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(badge(c, wordOf(st), accent, accentTextOf(st)))
        return row
    }

    private fun moreRow(c: Context, count: String, word: String, accent: Int, fg: Int) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(c, 14), dp(c, 6), dp(c, 10), dp(c, 6))
        addView(text(c, count, 12.5f, fg, semibold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(badge(c, word, accent, fg))
    }

    private fun divider(c: Context) = View(c).apply {
        setBackgroundColor(Palette.DIVIDER)
        layoutParams = LinearLayout.LayoutParams(MATCH, hair(c)).apply {
            marginStart = dp(c, 46)
            marginEnd = dp(c, 10)
        }
    }

    /** A capsule button (SPEC §4): 30 dp tall inside a 40 dp touch target, leading icon, 12.5 semibold. */
    private fun button(c: Context, t: String, iconRes: Int, bg: Int, fg: Int, enabled: Boolean = true, onClick: () -> Unit) =
        text(c, t, 12.5f, fg, semibold = true).apply {
            isSingleLine = true
            gravity = Gravity.CENTER
            val v = dp(c, 5)
            background = android.graphics.drawable.InsetDrawable(pillBg(c, bg, dpf(c, 15f)), 0, v, 0, v)
            minHeight = dp(c, 40)
            setPadding(dp(c, 12), 0, dp(c, 14), 0)
            setCompoundDrawablesRelative(icon(c, iconRes, 15f, fg), null, null, null)
            compoundDrawablePadding = dp(c, 5)
            alpha = if (enabled) 1f else 0.6f
            isEnabled = enabled
            if (enabled) setOnClickListener { act { onClick() } }
        }

    private fun lpStart(c: Context) = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(c, 8) }

    private fun span(c: Context, sb: SpannableStringBuilder, t: String, color: Int, sizeSp: Float, face: Typeface) {
        val start = sb.length
        sb.append(t)
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, c.resources.displayMetrics).toInt()
        sb.setSpan(AbsoluteSizeSpan(px, false), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(android.text.style.TypefaceSpan(face), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Taps run after the click ripple, outside the view tree being rebuilt. */
    private fun act(f: () -> Unit) = main.post(f)

    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

    private fun dp(c: Context, v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()

    private fun dpf(c: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics)
}

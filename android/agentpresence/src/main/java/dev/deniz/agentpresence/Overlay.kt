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
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The window drawn over other apps (SPEC v1.1 §2, Android column).
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
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (mode == Mode.EXPANDED) Gravity.TOP else Gravity.CENTER_VERTICAL
        }
        row.addView(
            when (mode) {
                Mode.EXPANDED -> expanded(c, p, others.size)
                Mode.COMPACT -> compact(c, p, others.size)
                Mode.MINIMAL -> circleChip(c, p, chip)
            },
        )
        for (o in others) {
            row.addView(circleChip(c, o, chip), LinearLayout.LayoutParams(chip, chip).apply {
                marginStart = dp(c, if (mode == Mode.MINIMAL) 6 else 8)
            })
        }
        // Minimal: the first circle is the window's left edge, so place() can centre it under the camera.
        r.addView(row, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = if (mode == Mode.MINIMAL) Gravity.START else Gravity.CENTER_HORIZONTAL
        })

        // Every active directive stays on screen: in the capsule under the pill. The expanded card is the task list
        // and never shows them, so while it is open the capsule of any other session still shows.
        val held = list.filter { it.hasDirectivesOn(HERE) && !(it.id == p.id && mode == Mode.EXPANDED) }
            .sortedBy { if (it.id == p.id) 0 else 1 }
        for (s in held) for (v in capsules(c, s, named = s.id != p.id)) {
            r.addView(v, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(c, 6)
            })
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
            row.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
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
        if (lp.gravity != gravity || lp.x != gx || lp.y != gy) {
            lp.gravity = gravity
            lp.x = gx
            lp.y = gy
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

    // ------------------------------------------------------------------ pieces

    private fun circleFor(c: Context, id: String): StepCircleView {
        val v = circles.getOrPut(id) { StepCircleView(c) }
        (v.parent as? ViewGroup)?.removeView(v)
        return v
    }

    private fun card(c: Context, radius: Int, color: Int = Palette.SURFACE) = GradientDrawable().apply {
        cornerRadius = dp(c, radius).toFloat()
        setColor(color)
        setStroke(maxOf(1, dp(c, 1) / 2), Palette.BORDER)
    }

    private fun pillBg(c: Context, color: Int, radius: Int = 20) = GradientDrawable().apply {
        cornerRadius = dp(c, radius).toFloat()
        setColor(color)
    }

    private fun maxWidth(c: Context) = (c.resources.displayMetrics.widthPixels * 0.92f).toInt()

    /** A session as just its circle on a dark disc: minimal, or a second session next to the main one. */
    private fun circleChip(c: Context, s: Session, size: Int): View {
        val small = size < dp(c, 40)
        val f = FrameLayout(c).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF000000.toInt())
                setStroke(maxOf(1, dp(c, 1) / 2), Palette.BORDER)
            }
            if (!small) elevation = dp(c, 4).toFloat()
            contentDescription = listOfNotNull(s.agent, s.app).joinToString(" · ")
            setOnClickListener { act { AgentPresence.userExpand(c, s.id) } }
        }
        val circle = circleFor(c, s.id).apply { fill = if (small) 0.66f else 0.55f }
        f.addView(circle, FrameLayout.LayoutParams(size, size))
        return f
    }

    private fun compact(c: Context, s: Session, others: Int): View {
        val pill = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = card(c, 24)
            elevation = dp(c, 4).toFloat()
            minimumWidth = dp(c, 176)
            setPadding(dp(c, 2), 0, dp(c, 8), 0)
            setOnClickListener { act { AgentPresence.userExpand(c, s.id) } }
        }
        pill.addView(circleFor(c, s.id).apply { fill = 0.55f }, LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)))

        val (rightText, rightColor) = rightSide(s)
        val closable = s.phase == Phase.DONE || s.phase == Phase.FAILED
        val right = if (rightText.isEmpty()) null else TextView(c).apply {
            text = rightText
            setTextColor(rightColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isSingleLine = true
            setPadding(dp(c, 12), 0, dp(c, 6), 0)
        }
        // The label gets whatever the circle, the right side, ✕ and the other circles leave of the screen width.
        val rightWidth = right?.let { (it.paint.measureText(rightText) + it.paddingLeft + it.paddingRight).toInt() + 1 } ?: 0
        val reserve = dp(c, 48) + dp(c, 14) + rightWidth + (if (closable) dp(c, 36) else 0) + others * dp(c, 52)

        val label = SpannableStringBuilder()
        append(label, s.app ?: s.agent, Palette.TEXT, 14f, bold = true)
        val second = when (s.phase) {
            Phase.RUNNING -> s.stepLabel ?: s.title
            else -> s.message ?: s.title
        }
        if (!second.isNullOrBlank()) {
            label.append("  ")
            append(label, second, Palette.TEXT2, 13f, bold = false)
        }
        pill.addView(TextView(c).apply {
            text = label
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = maxWidth(c) - reserve
            setPadding(dp(c, 4), 0, 0, 0)
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        right?.let { pill.addView(it, LinearLayout.LayoutParams(WRAP, WRAP)) }
        if (closable) {
            pill.addView(TextView(c).apply {
                text = "✕"
                gravity = Gravity.CENTER
                setTextColor(Palette.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                background = pillBg(c, Palette.BUTTON, 16)
                contentDescription = Strings.close
                setOnClickListener { act { AgentPresence.userClose(c, s.id) } }
            }, LinearLayout.LayoutParams(dp(c, 32), dp(c, 32)).apply { marginStart = dp(c, 4) })
        }
        return pill
    }

    /** The one critical datum on the compact right side (SPEC §2): `detail`, else `step/total`. */
    private fun rightSide(s: Session): Pair<String, Int> = when {
        s.live && s.stopRequested -> Strings.stopping to Palette.RED
        s.phase == Phase.WAITING -> Strings.waiting to Palette.AMBER
        s.phase == Phase.DONE -> Strings.done to Palette.GREEN
        s.phase == Phase.FAILED -> Strings.failed to Palette.RED
        !s.detail.isNullOrBlank() -> s.detail to Palette.TEXT
        s.total > 0 -> "${s.step}/${s.total}" to Palette.TEXT2
        s.directivesOn(HERE).any { !it.positive } -> "✋ ${Strings.handsOff}" to Palette.DONT_TEXT
        else -> "" to Palette.TEXT
    }

    /**
     * The directive capsules under the pill (SPEC "Directives on screen"): the "don't"s in a dark red one, the
     * "do"s in a green one. A session that is not the main one is named, so it is clear whose directive it is.
     */
    private fun capsules(c: Context, s: Session, named: Boolean): List<View> {
        val who = if (named) (s.app ?: s.agent) + ": " else ""
        val (donts, dos) = s.directivesOn(HERE).partition { !it.positive }
        val out = ArrayList<View>()
        if (donts.isNotEmpty()) out += capsule(c, donts.joinToString("\n") { "✋  $who${it.text}" }, Palette.DONT_BG, Palette.DONT_TEXT, 0x40FFB4AB, s)
        if (dos.isNotEmpty()) out += capsule(c, dos.joinToString("\n") { "✓  $who${it.text}" }, Palette.DO_BG, Palette.DO_TEXT, 0x40A7F3C0, s)
        return out
    }

    private fun capsule(c: Context, t: String, bg: Int, fg: Int, edge: Int, s: Session): View = TextView(c).apply {
        text = t
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setLineSpacing(dp(c, 3).toFloat(), 1f)
        background = GradientDrawable().apply {
            cornerRadius = dp(c, 18).toFloat()
            setColor(bg)
            setStroke(maxOf(1, dp(c, 1) / 2), edge)
        }
        elevation = dp(c, 3).toFloat()
        maxWidth = maxWidth(c)
        setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8))
        setOnClickListener { act { AgentPresence.userExpand(c, s.id) } }
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

    private fun expanded(c: Context, s: Session, others: Int): View {
        val width = minOf(maxWidth(c) - others * dp(c, 52), dp(c, 420))
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = card(c, 28)
            elevation = dp(c, 6).toFloat()
            setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 14))
            layoutParams = LinearLayout.LayoutParams(width, WRAP)
            setOnClickListener {
                act {
                    if (s.phase == Phase.WAITING && !s.acknowledged) AgentPresence.acknowledge(c, s.id)
                    else AgentPresence.userCollapse(c, s.id)
                }
            }
        }

        // Header: "agent · app" and the title · "Adım x/n" or the status. No circle here: the task rows carry
        // the state, a second circle would only repeat the active row's.
        val head = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(c, 44)
        }
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 8), 0, dp(c, 8), 0)
        }
        val name = SpannableStringBuilder()
        append(name, listOfNotNull(s.agent, s.app).joinToString(" · "), Palette.TEXT, 15f, bold = true)
        if (s.mirroredOn(HERE)) {
            name.append("  ")
            append(name, if (s.device == "mac") Strings.onMac else Strings.onPhone, Palette.TEXT2, 12f, bold = true)
        }
        col.addView(TextView(c).apply {
            text = name
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        // Without steps the title is the one task row below, not repeated here.
        if (s.total > 0 && !s.title.isNullOrBlank()) {
            col.addView(TextView(c).apply {
                text = s.title
                setTextColor(Palette.TEXT2)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(c, 2), 0, 0)
            })
        }
        head.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        val (tag, tagColor) = when {
            s.live && s.stopRequested -> Strings.stopping to Palette.RED
            s.phase == Phase.WAITING -> Strings.waiting to Palette.AMBER
            s.phase == Phase.DONE -> Strings.done to Palette.GREEN
            s.phase == Phase.FAILED -> Strings.failed to Palette.RED
            s.total > 0 -> Strings.stepOf(s.step, s.total) to Palette.TEXT2
            !s.detail.isNullOrBlank() -> s.detail to Palette.TEXT
            else -> "" to Palette.TEXT2
        }
        if (tag.isNotEmpty()) {
            head.addView(TextView(c).apply {
                text = tag
                setTextColor(tagColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                maxLines = 1
            })
        }
        box.addView(head)

        // The done message sits under the title.
        if (s.phase == Phase.DONE && !s.message.isNullOrBlank()) {
            box.addView(TextView(c).apply {
                text = s.message
                setTextColor(Palette.DO_TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                background = pillBg(c, Palette.DO_BG, 16)
                setPadding(dp(c, 14), dp(c, 9), dp(c, 14), dp(c, 9))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(c, 10) })
        }

        box.addView(taskList(c, s), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(c, 12) })

        // Buttons.
        val buttons = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        if (s.live) {
            if (s.stopRequested) {
                buttons.addView(button(c, Strings.stopping, Palette.DONT_BG, Palette.DONT_TEXT, enabled = false) {})
            } else {
                buttons.addView(button(c, Strings.stop, Palette.DONT_BG, Palette.DONT_TEXT) { AgentPresence.requestStop(c, s.id) })
            }
            if (s.phase == Phase.WAITING && !s.acknowledged) {
                buttons.addView(button(c, Strings.ok, Palette.BUTTON, Palette.TEXT) { AgentPresence.acknowledge(c, s.id) }, lpStart(c))
            } else {
                buttons.addView(button(c, Strings.minimize, Palette.BUTTON, Palette.TEXT) { AgentPresence.userMinimize(c, s.id) }, lpStart(c))
            }
        } else {
            buttons.addView(button(c, Strings.close, Palette.BUTTON, Palette.TEXT) { AgentPresence.userClose(c, s.id) })
        }
        box.addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(c, 12) })
        return box
    }

    /** The tasks one under another in a One UI inner card: circle · title (· detail or message) · status word. */
    private fun taskList(c: Context, s: Session): View {
        val list = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = pillBg(c, Palette.INNER, 20)
            setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6))
        }
        data class Task(val index: Int, val title: String)
        val tasks = if (s.total == 0) listOf(Task(0, s.title ?: s.app ?: s.agent))
        else s.steps.mapIndexed { i, st -> Task(i, st.title) }

        // Long plans: a window of six rows around the current task, the rest counted.
        val maxRows = 6
        val cur = (s.step - 1).coerceIn(0, maxOf(0, tasks.size - 1))
        val start = if (tasks.size <= maxRows) 0 else (cur - 2).coerceIn(0, tasks.size - maxRows)
        val end = minOf(tasks.size, start + maxRows)
        if (start > 0) list.addView(moreRow(c, "✓  +$start", Palette.GREEN))
        val tinted = setOf(RowState.ACTIVE, RowState.WAITING, RowState.FAILED)
        for (k in start until end) {
            // A hairline between plain rows only; a tinted row is its own separation.
            if (k > start && rowState(s, tasks[k - 1].index) !in tinted && rowState(s, tasks[k].index) !in tinted) list.addView(divider(c))
            list.addView(taskRow(c, s, tasks[k].index, tasks[k].title))
        }
        if (end < tasks.size) list.addView(moreRow(c, "+${tasks.size - end}  ${Strings.pending}", Palette.PENDING_TEXT))
        return list
    }

    private fun taskRow(c: Context, s: Session, i: Int, title: String): View {
        val st = rowState(s, i)
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(c, 50)
            setPadding(dp(c, 4), dp(c, 6), dp(c, 12), dp(c, 6))
            when (st) {
                RowState.ACTIVE -> background = pillBg(c, Palette.ROW_ACTIVE, 16)
                RowState.WAITING -> background = pillBg(c, Palette.ROW_WAIT, 16)
                RowState.FAILED -> background = pillBg(c, Palette.ROW_FAIL, 16)
                else -> {}
            }
        }
        val circle = StepCircleView(c).apply {
            fill = 0.58f
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
        row.addView(circle, LinearLayout.LayoutParams(dp(c, 38), dp(c, 38)))

        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 8), 0, dp(c, 8), 0)
        }
        col.addView(TextView(c).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            when (st) {
                RowState.DONE -> setTextColor(Palette.TEXT2)
                RowState.PENDING -> setTextColor(Palette.PENDING_TEXT)
                else -> {
                    setTextColor(Palette.TEXT)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
            }
        })
        // Under the task: what it is waiting for or why it failed (SPEC), or the live datum.
        val sub = when (st) {
            RowState.WAITING, RowState.FAILED -> s.message
            RowState.ACTIVE -> s.detail
            else -> null
        }
        if (!sub.isNullOrBlank()) {
            col.addView(TextView(c).apply {
                text = sub
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(
                    when (st) {
                        RowState.WAITING -> Palette.WAIT_TEXT
                        RowState.FAILED -> Palette.DONT_TEXT
                        else -> Palette.TEXT2
                    },
                )
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(c, 2), 0, 0)
            })
        }
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))

        val (word, color) = when (st) {
            RowState.DONE -> Strings.done to Palette.GREEN
            RowState.ACTIVE -> Strings.running to Palette.BLUE_TEXT
            RowState.PENDING -> Strings.pending to Palette.PENDING_TEXT
            RowState.WAITING -> Strings.waiting to Palette.AMBER
            RowState.FAILED -> Strings.failed to Palette.RED
        }
        row.addView(TextView(c).apply {
            text = word
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isSingleLine = true
        })
        return row
    }

    private fun moreRow(c: Context, t: String, color: Int) = TextView(c).apply {
        text = t
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(dp(c, 16), dp(c, 8), dp(c, 12), dp(c, 8))
    }

    private fun divider(c: Context) = View(c).apply {
        setBackgroundColor(Palette.DIVIDER)
        layoutParams = LinearLayout.LayoutParams(MATCH, maxOf(1, dp(c, 1) / 2)).apply {
            marginStart = dp(c, 50)
            marginEnd = dp(c, 12)
        }
    }

    private fun button(c: Context, t: String, bg: Int, fg: Int, enabled: Boolean = true, onClick: () -> Unit) = TextView(c).apply {
        text = t
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = pillBg(c, bg, 20)
        minHeight = dp(c, 40)
        setPadding(dp(c, 18), 0, dp(c, 18), 0)
        alpha = if (enabled) 1f else 0.7f
        isEnabled = enabled
        if (enabled) setOnClickListener { act { onClick() } }
    }

    private fun lpStart(c: Context) = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(c, 8) }

    private fun append(sb: SpannableStringBuilder, t: String, color: Int, sizeSp: Float, bold: Boolean) {
        val start = sb.length
        sb.append(t)
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(AbsoluteSizeSpan(sizeSp.toInt(), true), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Taps run after the click ripple, outside the view tree being rebuilt. */
    private fun act(f: () -> Unit) = main.post(f)

    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

    private fun dp(c: Context, v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()
}

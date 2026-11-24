package dev.deniz.agentpresence

import org.json.JSONArray
import org.json.JSONObject

/** Where a session stands (SPEC §1, "phase"). */
enum class Phase(val wire: String) {
    RUNNING("running"), WAITING("waiting"), DONE("done"), FAILED("failed");

    companion object {
        fun of(s: String?): Phase = entries.firstOrNull { it.wire == s } ?: RUNNING
    }
}

/**
 * One step of the plan (SPEC §1 `steps`): its label, the inputs the agent itself drives during it
 * (`keyboard`, `mouse`, `touch`) and "don't"s that hold only while this step is still ahead or running.
 * On the wire a step is a plain string or `{"title","uses","dont"}`.
 */
data class Step(
    val title: String,
    val uses: Set<String> = emptySet(),
    val dont: List<String> = emptyList(),
) {
    /** A plain string when there is nothing but the label, as most agents send it. */
    fun toJson(): Any =
        if (uses.isEmpty() && dont.isEmpty()) title
        else JSONObject().apply {
            put("title", title)
            if (uses.isNotEmpty()) put("uses", JSONArray(uses.toList()))
            if (dont.isNotEmpty()) put("dont", JSONArray(dont))
        }

    companion object {
        val INPUTS = listOf("keyboard", "mouse", "touch")

        fun from(v: Any?): Step? = when (v) {
            is String -> v.trim().takeIf { it.isNotEmpty() }?.let { Step(it) }
            is JSONObject -> v.optString("title").trim().takeIf { it.isNotEmpty() }?.let { t ->
                fun arr(k: String): List<String> {
                    val a = v.optJSONArray(k) ?: return listOf(v.optString(k)).filter { it.isNotBlank() }
                    return (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotBlank() }
                }
                Step(t, arr("uses").map { it.lowercase() }.filter { it in INPUTS }.toCollection(LinkedHashSet()), arr("dont"))
            }
            else -> null
        }

        fun listFrom(a: JSONArray?): List<Step> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { from(a.opt(it)) }
    }
}

/** A button on a question card (SPEC "Choices and opening the place"); [style] is `primary`, `destructive` or null. */
data class Choice(val id: String, val label: String, val style: String? = null) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("label", label).apply { style?.let { put("style", it) } }

    companion object {
        fun listFrom(a: JSONArray?): List<Choice> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Choice(id, o.optString("label").trim().ifEmpty { id }, o.optString("style").trim().takeIf { it.isNotEmpty() })
        }
    }
}

/** One line of the directive capsule: a "don't" (red) or, when [positive], a "do" (green). */
data class Directive(val text: String, val positive: Boolean = false)

/**
 * One change sent by the agent (SPEC §1). A null field was not given: the session keeps its old value.
 * [doList] is the JSON field `do` (a Kotlin keyword).
 */
data class PresenceEvent(
    val id: String,
    val type: String,
    val agent: String? = null,
    val app: String? = null,
    val device: String? = null,
    val title: String? = null,
    val steps: List<Step>? = null,
    val step: Int? = null,
    val detail: String? = null,
    val dont: List<String>? = null,
    val doList: List<String>? = null,
    val message: String? = null,
    val ts: Long? = null,
    /** Seconds of silence after which the host finishes the running session itself (SPEC "Never left blocked");
     *  0 or less clears it. */
    val ttl: Int? = null,
    /** Seconds a `hide` lasts before the warnings come back by themselves (JSON `for`; default 30). */
    val forSec: Int? = null,
    /** What tapping the question opens (a URL, path or app id); meaningful on the device it came from. */
    val open: String? = null,
    /** The answers offered with an `attention` (replaces the list when given). */
    val choices: List<Choice>? = null,
    /** `choose`: the id picked elsewhere. */
    val choice: String? = null,
    /** Internal: an event the host made itself (its own done, the end of a hide). It keeps the stored directives,
     *  and it does not count as the agent speaking, so it does not push the ttl further. */
    val keep: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1)
        put("id", id)
        put("type", type)
        agent?.let { put("agent", it) }
        app?.let { put("app", it) }
        device?.let { put("device", it) }
        title?.let { put("title", it) }
        steps?.let { l -> put("steps", JSONArray(l.map { it.toJson() })) }
        step?.let { put("step", it) }
        detail?.let { put("detail", it) }
        dont?.let { put("dont", JSONArray(it)) }
        doList?.let { put("do", JSONArray(it)) }
        message?.let { put("message", it) }
        ts?.let { put("ts", it) }
        ttl?.let { put("ttl", it) }
        forSec?.let { put("for", it) }
        open?.let { put("open", it) }
        choices?.let { l -> put("choices", JSONArray(l.map { it.toJson() })) }
        choice?.let { put("choice", it) }
    }

    companion object {
        val TYPES = setOf(
            "start", "intervene", "step", "detail", "directive", "attention",
            "resume", "done", "fail", "end", "expand", "collapse", "hide", "unhide", "choose",
        )

        /** One event object or a JSON array of them. Throws [IllegalArgumentException] on bad input. */
        @JvmStatic
        fun parse(json: String): List<PresenceEvent> {
            val t = json.trim()
            return try {
                if (t.startsWith("[")) {
                    val a = JSONArray(t)
                    (0 until a.length()).map { from(a.getJSONObject(it)) }
                } else {
                    listOf(from(JSONObject(t)))
                }
            } catch (e: org.json.JSONException) {
                throw IllegalArgumentException("bad JSON: ${e.message}", e)
            }
        }

        @JvmStatic
        fun from(o: JSONObject): PresenceEvent {
            val id = o.str("id")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("event without id")
            val type = o.str("type") ?: throw IllegalArgumentException("event without type")
            require(type in TYPES) { "unknown type: $type" }
            return PresenceEvent(
                id = id,
                type = type,
                agent = o.str("agent"),
                app = o.str("app"),
                device = o.str("device"),
                title = o.str("title"),
                steps = if (o.has("steps") && !o.isNull("steps")) Step.listFrom(o.optJSONArray("steps")) else null,
                step = if (o.has("step") && !o.isNull("step")) o.optInt("step", 0).takeIf { it > 0 } else null,
                detail = o.str("detail"),
                dont = o.list("dont"),
                doList = o.list("do"),
                message = o.str("message"),
                ts = if (o.has("ts") && !o.isNull("ts")) o.optLong("ts") else null,
                ttl = if (o.has("ttl") && !o.isNull("ttl")) o.optDouble("ttl", 0.0).toInt() else null,
                forSec = if (o.has("for") && !o.isNull("for")) o.optDouble("for", 0.0).toInt() else null,
                open = o.str("open"),
                choices = if (o.has("choices") && !o.isNull("choices")) Choice.listFrom(o.optJSONArray("choices")) else null,
                choice = o.str("choice"),
            )
        }

        private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null

        /** An array of strings; a single string is taken as a one-item list. Blank items are dropped. */
        private fun JSONObject.list(k: String): List<String>? {
            if (!has(k) || isNull(k)) return null
            val a = optJSONArray(k) ?: return listOf(optString(k)).filter { it.isNotBlank() }
            return (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotBlank() }
        }
    }
}

/** What one agent task on the device looks like right now. [doList] = SPEC `do`. */
data class Session(
    val id: String,
    val agent: String = "Claude",
    val app: String? = null,
    val device: String? = null,
    val title: String? = null,
    val steps: List<Step> = emptyList(),
    val step: Int = 1,
    val detail: String? = null,
    val dont: List<String> = emptyList(),
    val doList: List<String> = emptyList(),
    val message: String? = null,
    val phase: Phase = Phase.RUNNING,
    val stopRequested: Boolean = false,
    val acknowledged: Boolean = false,
    val started: Long = 0L,
    val updated: Long = 0L,
    /** Seconds of silence after which the host finishes it itself; null = never. */
    val ttl: Int? = null,
    /** When the agent last sent an event for it (owner taps do not count). */
    val heard: Long = 0L,
    /** `hide`: until when (epoch ms) every written part is off the screen and the edges glow instead; 0 = shown. */
    val hiddenUntil: Long = 0L,
    /** What tapping the question opens, on the device it came from. */
    val open: String? = null,
    /** The answers offered while it waits for the owner. */
    val choices: List<Choice> = emptyList(),
    /** The owner's pick (a [Choice.id]), for the agent waiting on it; null until picked. */
    val choice: String? = null,
) {
    val total: Int get() = steps.size

    /** Steps already finished: the ones before the current step, or all of them once done. */
    val finished: Int get() = if (phase == Phase.DONE) total else (step - 1).coerceIn(0, total)

    val live: Boolean get() = phase == Phase.RUNNING || phase == Phase.WAITING

    /**
     * The active directives (SPEC "Directives on screen"), computed, never typed twice: the inputs that any step not
     * finished yet drives (one generated line), those steps' own "don't"s, the session "don't"s, then its "do"s.
     * None while the agent waits for the owner (they must act now) or once it is over.
     */
    val directives: List<Directive>
        get() {
            if (phase != Phase.RUNNING) return emptyList()
            val ahead = steps.drop((step - 1).coerceAtLeast(0))
            val uses = ahead.flatMapTo(HashSet()) { it.uses }
            val out = ArrayList<Directive>()
            val keys = "keyboard" in uses
            val mouse = "mouse" in uses
            when {
                keys && mouse -> out += Directive(Strings.noKeysMouse)
                keys -> out += Directive(Strings.noKeys)
                mouse -> out += Directive(Strings.noMouse)
            }
            val touch = "touch" in uses
            if (touch) out += Directive(Strings.noTouch)
            // One warning is said once: a typed "don't" that only repeats a generated one is dropped (SPEC).
            for (d in ahead.flatMap { it.dont } + dont) {
                if ((keys || mouse) && mentions(d, POINTER_WORDS)) continue
                if (touch && mentions(d, TOUCH_WORDS)) continue
                out += Directive(d)
            }
            for (d in doList) out += Directive(d, positive = true)
            return out.distinct()
        }

    /** The agent itself drives an input (touch, keyboard, ...) in a step not finished yet. */
    val drivesInput: Boolean
        get() = phase == Phase.RUNNING && steps.drop((step - 1).coerceAtLeast(0)).any { it.uses.isNotEmpty() }

    /** Something the owner must (not) do is in force: the compact pill and its capsule stay on screen. */
    val hasDirectives: Boolean get() = directives.isNotEmpty()

    /** Directives belong to the device being driven. A mirror on the other device shows the progress, never the
     *  "don't touch" (owner, 2026-11-24: a phone task must not tell him to keep off the Mac). */
    fun directivesOn(here: String): List<Directive> = if (mirroredOn(here)) emptyList() else directives

    fun hasDirectivesOn(here: String) = directivesOn(here).isNotEmpty()

    /** Label of the current step, if there is a plan. */
    val stepLabel: String? get() = steps.getOrNull(step - 1)?.title

    /** Shown on the device it does not drive (SPEC: mirror with a device badge). */
    fun mirroredOn(here: String) = device != null && device != here

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("agent", agent)
        app?.let { put("app", it) }
        device?.let { put("device", it) }
        title?.let { put("title", it) }
        put("steps", JSONArray(steps.map { it.toJson() }))
        put("step", step)
        detail?.let { put("detail", it) }
        put("dont", JSONArray(dont))
        put("do", JSONArray(doList))
        message?.let { put("message", it) }
        put("phase", phase.wire)
        put("stopRequested", stopRequested)
        put("acknowledged", acknowledged)
        put("started", started)
        put("updated", updated)
        ttl?.let { put("ttl", it) }
        put("heard", heard)
        if (hiddenUntil > 0) put("hiddenUntil", hiddenUntil)
        open?.let { put("open", it) }
        if (choices.isNotEmpty()) put("choices", JSONArray(choices.map { it.toJson() }))
        choice?.let { put("choice", it) }
    }

    /** The part the agent reads back (SPEC "State the host reports back"). */
    fun stateJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("phase", phase.wire)
        put("step", if (phase == Phase.DONE) total.coerceAtLeast(step) else step)
        put("total", total)
        put("stopRequested", stopRequested)
        put("acknowledged", acknowledged)
        put("updated", updated)
        put("choice", choice ?: JSONObject.NULL)
    }

    /** The label of the owner's pick, for "✓ İzin ver". */
    val choiceLabel: String? get() = choice?.let { c -> choices.firstOrNull { it.id == c }?.label ?: c }

    companion object {
        /** Words that make a typed "don't" a repeat of the keyboard/trackpad warning ("imlec" = imleci, imlece). */
        private val POINTER_WORDS = listOf("klavye", "keyboard", "fare", "mouse", "trackpad", "imleç", "imlec")

        /** Words that make it a repeat of the touch warning. Not "dokun": "Kabloya dokunma" must stay. */
        private val TOUCH_WORDS = listOf("ekran", "screen", "telefon", "phone")

        private val TURKISH = java.util.Locale.forLanguageTag("tr")

        /** Case-insensitive, Turkish-aware ("İmleç" → "imleç") and plain ("IMLEC" → "imlec"). */
        internal fun mentions(text: String, words: List<String>): Boolean {
            val tr = text.lowercase(TURKISH)
            val plain = text.lowercase(java.util.Locale.ROOT)
            return words.any { it in tr || it in plain }
        }

        fun fromJson(o: JSONObject): Session {
            fun arr(k: String): List<String> {
                val a = o.optJSONArray(k) ?: return emptyList()
                return (0 until a.length()).map { a.optString(it) }
            }
            fun str(k: String): String? = if (o.has(k) && !o.isNull(k)) o.optString(k) else null
            return Session(
                id = o.getString("id"),
                agent = o.optString("agent", "Claude"),
                app = str("app"),
                device = str("device"),
                title = str("title"),
                steps = Step.listFrom(o.optJSONArray("steps")),
                step = o.optInt("step", 1),
                detail = str("detail"),
                dont = arr("dont"),
                doList = arr("do"),
                message = str("message"),
                phase = Phase.of(o.optString("phase")),
                stopRequested = o.optBoolean("stopRequested"),
                acknowledged = o.optBoolean("acknowledged"),
                started = o.optLong("started"),
                updated = o.optLong("updated"),
                ttl = if (o.has("ttl") && !o.isNull("ttl")) o.optInt("ttl").takeIf { it > 0 } else null,
                heard = o.optLong("heard", o.optLong("updated")),
                hiddenUntil = o.optLong("hiddenUntil"),
                open = str("open"),
                choices = Choice.listFrom(o.optJSONArray("choices")),
                choice = str("choice"),
            )
        }

        /** `{"v":1,"sessions":[...]}`: what the host hands back after each event. */
        fun stateOf(sessions: Collection<Session>): String =
            JSONObject().put("v", 1).put("sessions", JSONArray(sessions.map { it.stateJson() })).toString()
    }
}

/** What the screen should do after an event (SPEC §1 "effect" column). */
enum class Cue { NONE, ANNOUNCE, FLASH, STICKY, EXPAND, COLLAPSE, REMOVE }

/** Short feedback the owner can feel: attention and the end of a task. */
enum class Buzz { NONE, ATTENTION, DONE, FAILED }

/**
 * What the island shows after an event, in order (SPEC §2, "Warning first"): the owner learns what to (not) do
 * before anything about the process. Pure, no Android.
 */
object Plan {
    enum class Show { WARNING, EXPANDED, COMPACT }

    /** Show [show] for [ms] (0 = until the owner acts), then the next stage, then the resting size. */
    data class Stage(val show: Show, val ms: Long)

    const val WARNING_MS = 3_000L
    const val ANNOUNCE_MS = 5_000L
    const val FLASH_MS = 3_000L
    const val TAP_MS = 8_000L

    /**
     * SPEC "Never left blocked": a running session with a ttl that heard nothing from the agent for ttl seconds is
     * finished by the host itself. A waiting session never expires: the owner must act.
     */
    fun expired(s: Session, now: Long): Boolean {
        val ttl = s.ttl ?: return false
        return s.phase == Phase.RUNNING && ttl > 0 && now - s.heard >= ttl * 1000L
    }

    /** [s] is hidden right now (SPEC "Edge glow while hidden"). */
    fun hidden(s: Session, now: Long) = s.hiddenUntil > now

    /** A hide whose time is up: the warnings come back by themselves. */
    fun unhideDue(s: Session, now: Long) = s.hiddenUntil in 1..now

    /** When [s] expires (epoch ms), or null when it cannot. */
    fun expiresAt(s: Session): Long? {
        val ttl = s.ttl ?: return null
        return if (s.phase == Phase.RUNNING && ttl > 0) s.heard + ttl * 1000L else null
    }

    /** The session now shows a directive on [here] that it did not show a moment ago ([before]). */
    fun newDirective(before: List<Directive>, after: Session?, here: String): Boolean {
        if (after == null || after.phase != Phase.RUNNING) return false
        val now = after.directivesOn(here)
        return now.isNotEmpty() && now.any { it !in before }
    }

    /**
     * The stages for one event. null = leave the island as it is; an empty list = back to the resting size.
     * With a new directive: the warning card first (3 s), then the task list for start/intervene, or rest.
     */
    fun stages(type: String, cue: Cue, warn: Boolean): List<Stage>? = when {
        cue == Cue.REMOVE -> emptyList()
        warn && (type == "start" || type == "intervene") ->
            listOf(Stage(Show.WARNING, WARNING_MS), Stage(Show.EXPANDED, ANNOUNCE_MS))
        warn -> listOf(Stage(Show.WARNING, WARNING_MS))
        else -> when (cue) {
            Cue.ANNOUNCE -> listOf(Stage(Show.EXPANDED, ANNOUNCE_MS))
            Cue.STICKY -> listOf(Stage(Show.EXPANDED, 0))
            Cue.EXPAND -> listOf(Stage(Show.EXPANDED, TAP_MS))
            Cue.FLASH -> listOf(Stage(Show.COMPACT, FLASH_MS))
            Cue.COLLAPSE -> emptyList()
            Cue.NONE, Cue.REMOVE -> null
        }
    }
}

/** Pure session logic, no Android: one event in, the new session and what the screen should do out. */
object Reducer {
    class Result(val session: Session?, val cue: Cue, val buzz: Buzz)

    /** Seconds a `hide` lasts when it does not say (SPEC `for`). */
    const val HIDE_SECONDS = 30

    fun apply(old: Session?, e: PresenceEvent, now: Long, here: String = "phone"): Result {
        if (e.type == "end") return Result(null, Cue.REMOVE, Buzz.NONE)
        // Hiding is about a session that exists; a stray hide/unhide does not make one.
        if (old == null && (e.type == "hide" || e.type == "unhide" || e.type == "choose")) return Result(null, Cue.NONE, Buzz.NONE)

        val fresh = e.type == "start" || old == null
        val base = if (fresh) Session(id = e.id, started = now) else old!!
        var s = base.copy(
            agent = e.agent ?: base.agent,
            app = e.app ?: base.app,
            device = e.device ?: base.device,
            title = e.title ?: base.title,
            steps = e.steps ?: base.steps,
            detail = e.detail ?: base.detail,
            open = e.open ?: base.open,
            choices = e.choices ?: base.choices,
            dont = e.dont ?: base.dont,
            doList = e.doList ?: base.doList,
            updated = now,
            heard = if (e.keep) base.heard else now,
            ttl = when {
                e.ttl == null -> base.ttl
                e.ttl > 0 -> e.ttl
                else -> null
            },
        )
        s = s.copy(step = clamp(s.step, s))

        var cue = Cue.NONE
        var buzz = Buzz.NONE
        when (e.type) {
            "start" -> {
                s = s.copy(step = clamp(e.step ?: 1, s), phase = Phase.RUNNING, message = e.message)
                cue = Cue.ANNOUNCE
            }
            "intervene" -> {
                val again = base.phase == Phase.DONE || base.phase == Phase.FAILED
                s = s.copy(
                    step = clamp(e.step ?: if (again && e.steps != null) 1 else s.step, s),
                    phase = Phase.RUNNING,
                    acknowledged = false,
                    message = e.message,
                )
                cue = Cue.ANNOUNCE
            }
            "step" -> {
                s = s.copy(step = clamp(e.step ?: (base.step + 1), s), phase = Phase.RUNNING, acknowledged = false)
                cue = Cue.FLASH
            }
            "detail" -> cue = Cue.NONE
            "directive" -> {
                val newDont = e.dont != null && e.dont.isNotEmpty() && e.dont != base.dont
                val newDo = e.doList != null && e.doList.isNotEmpty() && e.doList != base.doList
                cue = if (newDont || newDo) Cue.ANNOUNCE else Cue.NONE
            }
            "attention" -> {
                // A new question: the old answer no longer counts.
                s = s.copy(phase = Phase.WAITING, acknowledged = false, message = e.message, hiddenUntil = 0L, choice = null)
                cue = Cue.STICKY
                buzz = Buzz.ATTENTION
            }
            "resume" -> s = s.copy(phase = Phase.RUNNING, acknowledged = false)
            "done" -> {
                s = s.copy(
                    phase = Phase.DONE, step = s.total.coerceAtLeast(1), message = e.message, hiddenUntil = 0L,
                    dont = if (e.keep) s.dont else emptyList(),
                    doList = e.doList ?: if (e.keep) s.doList else emptyList(),
                )
                cue = Cue.ANNOUNCE
                buzz = Buzz.DONE
            }
            "fail" -> {
                s = s.copy(
                    phase = Phase.FAILED, message = e.message, hiddenUntil = 0L,
                    dont = emptyList(), doList = e.doList ?: emptyList(),
                )
                cue = Cue.ANNOUNCE
                buzz = Buzz.FAILED
            }
            // The agent must see or tap what is under the island (SPEC): the written parts go, the edges glow.
            // Only on the device being driven: a mirror ignores it.
            "hide" -> if (!s.mirroredOn(here)) {
                s = s.copy(hiddenUntil = now + (e.forSec?.takeIf { it > 0 } ?: HIDE_SECONDS) * 1000L)
            }
            "unhide" -> s = s.copy(hiddenUntil = 0L)
            // A pick made elsewhere (the Mac, or the owner on this phone through AgentPresence.choose).
            "choose" -> {
                s = s.copy(choice = e.choice ?: s.choice, acknowledged = true)
                cue = Cue.ANNOUNCE
            }
            "expand" -> cue = Cue.EXPAND
            "collapse" -> cue = Cue.COLLAPSE
        }
        return Result(s, cue, buzz)
    }

    private fun clamp(n: Int, s: Session) = if (s.steps.isEmpty()) n.coerceAtLeast(1) else n.coerceIn(1, s.steps.size)
}

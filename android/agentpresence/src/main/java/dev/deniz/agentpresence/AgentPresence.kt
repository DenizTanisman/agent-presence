package dev.deniz.agentpresence

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import org.json.JSONArray

/**
 * Public entry point. An app that drives this phone (or the bundled host app, for an agent on a computer
 * over adb) sends events here; the library keeps the sessions, draws the overlay, keeps a foreground
 * service with an ongoing notification while anything is shown, and reports the state back.
 *
 * ```
 * AgentPresence.send(context, PresenceEvent(id = "t1", type = "start", app = "Ayarlar",
 *     steps = listOf(Step("Aç", uses = setOf("touch")), Step("Bul"), Step("Değiştir", uses = setOf("touch")))))
 * ```
 *
 * Thread-safe: the state changes at once and is returned; drawing happens on the main thread.
 */
object AgentPresence {
    /** This device's name in the protocol (`device` field). Sessions for another device are mirrors. */
    const val DEVICE = "phone"

    private val lock = Any()
    private val sessions = LinkedHashMap<String, Session>()
    private val main = Handler(Looper.getMainLooper())
    private var restored = false
    @Volatile private var app: Context? = null

    /** Called on the main thread when the owner taps Durdur. The agent also sees it in [state]. */
    @JvmStatic var onStopRequested: ((Session) -> Unit)? = null

    /** Called on the main thread after every change. */
    @JvmStatic var onChange: (() -> Unit)? = null

    /**
     * Where the minimal circle sits: false (default) = right under the camera hole, centred on it, just below the
     * status bar (tappable); true = inside the status bar band, right of the hole (there the status bar takes the taps).
     */
    @JvmStatic var minimalInStatusBar: Boolean
        get() = Overlay.minimalInStatusBar
        set(v) {
            Overlay.minimalInStatusBar = v
            app?.let { c -> onMain { refresh(c) } }
        }

    @JvmStatic
    fun send(context: Context, event: PresenceEvent): String = send(context, listOf(event))

    /** Applies the events in order and returns the state JSON (SPEC "State the host reports back"). */
    @JvmStatic
    fun send(context: Context, events: List<PresenceEvent>): String {
        init(context)
        val now = System.currentTimeMillis()
        val effects = ArrayList<Triple<String, Cue, Buzz>>()
        val ended = ArrayList<Session>()
        synchronized(lock) {
            for (e in events) {
                if (e.type == "start") {
                    // A new task takes the place of finished ones (SPEC: done stays until tapped or a new start).
                    val gone = sessions.values.filter { it.id != e.id && (it.phase == Phase.DONE || it.phase == Phase.FAILED) }
                    for (g in gone) {
                        sessions.remove(g.id)
                        Notifier.cancelFinished(app, g.id)
                    }
                }
                val r = Reducer.apply(sessions[e.id], e, now)
                if (e.type == "start" || e.type == "end") Notifier.cancelFinished(app, e.id)
                if (r.session == null) {
                    sessions.remove(e.id)
                } else {
                    sessions[e.id] = r.session
                    if (r.buzz == Buzz.DONE || r.buzz == Buzz.FAILED) ended += r.session
                }
                effects += Triple(e.id, r.cue, r.buzz)
            }
            save()
        }
        onMain {
            val c = context.applicationContext
            for ((id, cue, buzz) in effects) {
                Overlay.cue(c, id, cue)
                buzz(c, buzz)
            }
            for (s in ended) Notifier.finished(c, s)
            refresh(c)
        }
        return state()
    }

    /** One event object or a JSON array of them (SPEC §1). Throws [IllegalArgumentException] on bad input. */
    @JvmStatic
    fun sendJson(context: Context, json: String): String = send(context, PresenceEvent.parse(json))

    /** `{"v":1,"sessions":[{"id","phase","step","total","stopRequested","acknowledged","updated"}]}` */
    @JvmStatic
    fun state(): String = synchronized(lock) {
        val o = org.json.JSONObject(Session.stateOf(sessions.values))
        Overlay.bounds?.let { b ->
            o.put("overlay", org.json.JSONObject().put("x", b.left).put("y", b.top).put("w", b.width()).put("h", b.height()))
        }
        o.toString()
    }

    @JvmStatic
    fun sessions(): List<Session> = synchronized(lock) { sessions.values.toList() }

    /** Restores the sessions saved before the process died, and shows them again. Safe to call often. */
    @JvmStatic
    fun init(context: Context) {
        val c = context.applicationContext
        app = c
        val again: Boolean
        synchronized(lock) {
            if (restored) return
            restored = true
            val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            if (raw != null) runCatching {
                val a = JSONArray(raw)
                for (i in 0 until a.length()) {
                    val s = Session.fromJson(a.getJSONObject(i))
                    sessions[s.id] = s
                }
            }
            again = sessions.isNotEmpty()
        }
        if (again) onMain { refresh(c) }
    }

    // ------------------------------------------------------------------ the owner's taps

    internal fun requestStop(c: Context, id: String) {
        val s = update(c, id) { it.copy(stopRequested = true) } ?: return
        onStopRequested?.invoke(s)
    }

    internal fun acknowledge(c: Context, id: String) {
        update(c, id) { it.copy(acknowledged = true) }
        Overlay.rest(id)
        refresh(c)
    }

    internal fun userExpand(c: Context, id: String) {
        Overlay.open(id)
        refresh(c)
    }

    internal fun userCollapse(c: Context, id: String) {
        Overlay.rest(id)
        refresh(c)
    }

    /** Küçült: back to the resting size (minimal, or compact while a directive is active). */
    internal fun userMinimize(c: Context, id: String) {
        Overlay.rest(id)
        refresh(c)
    }

    /** Kapat / ✕ on a finished session: it is gone. */
    internal fun userClose(c: Context, id: String) {
        synchronized(lock) {
            sessions.remove(id)
            save()
        }
        Notifier.cancelFinished(c, id)
        Overlay.forget(id)
        refresh(c)
    }

    private fun update(c: Context, id: String, f: (Session) -> Session): Session? {
        val s = synchronized(lock) {
            val old = sessions[id] ?: return null
            f(old).copy(updated = System.currentTimeMillis()).also {
                sessions[id] = it
                save()
            }
        }
        onMain { refresh(c.applicationContext) }
        return s
    }

    // ------------------------------------------------------------------ internals

    internal fun refresh(c: Context) {
        Overlay.render(c)
        PresenceService.sync(c)
        onChange?.invoke()
    }

    private fun save() {
        val c = app ?: return
        val a = JSONArray()
        for (s in sessions.values) a.put(s.toJson())
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    private fun buzz(c: Context, b: Buzz) {
        if (b == Buzz.NONE) return
        val effect = when (b) {
            Buzz.ATTENTION -> VibrationEffect.createWaveform(longArrayOf(0, 60, 90, 60), -1)
            Buzz.DONE -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            else -> VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120), -1)
        }
        runCatching { c.getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(effect) }
    }

    private fun onMain(f: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) f() else main.post(f)
    }

    private const val PREFS = "dev.deniz.agentpresence"
    private const val KEY = "sessions"
}

package dev.deniz.agentpresence

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * SPEC "Never left blocked": when the agent goes quiet or the adb link drops, its `done` never arrives and the phone
 * would keep saying "Ekrana dokunma". A running session with a `ttl` that heard nothing for that long is finished
 * here, through the same path as a real `done` (announcement, green glow, vibration, "Bitti" notification, then the
 * green check). It also ends a `hide` when its time is up. Checks at most every 5 s and only while such a session
 * exists; it holds nothing alive by itself.
 * Main thread only.
 */
internal object Watchdog {
    private const val EVERY_MS = 5_000L
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var due = 0L

    private val tick = Runnable {
        due = 0L
        val c = app ?: return@Runnable
        val now = System.currentTimeMillis()
        val list = AgentPresence.sessions()
        // A hide whose time is up brings the warnings back; a quiet running session is finished. The same path as
        // the agent's own events; it refreshes the screen, which schedules the next check.
        val events = list.filter { Plan.unhideDue(it, now) }.map { PresenceEvent(id = it.id, type = "unhide", keep = true) } +
            list.filter { Plan.expired(it, now) }.map { PresenceEvent(id = it.id, type = "done", message = Strings.selfDone, keep = true) }
        if (events.isNotEmpty()) AgentPresence.send(c, events) else kick(c)
    }

    /** After every change: check again when the next session would expire, but at least every 5 s. */
    fun kick(c: Context) {
        app = c.applicationContext
        val next = AgentPresence.sessions().flatMap { listOfNotNull(Plan.expiresAt(it), it.hiddenUntil.takeIf { h -> h > 0 }) }.minOrNull()
        if (next == null) {
            main.removeCallbacks(tick)
            due = 0L
            return
        }
        val now = System.currentTimeMillis()
        val at = minOf(next, now + EVERY_MS).coerceAtLeast(now + 200)
        if (due in 1..at) return
        main.removeCallbacks(tick)
        due = at
        main.postDelayed(tick, at - now)
    }
}

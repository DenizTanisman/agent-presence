package dev.deniz.agentpresence.host

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.deniz.agentpresence.AgentPresence
import dev.deniz.agentpresence.PresenceEvent
import dev.deniz.agentpresence.Step

/** A scripted session that walks through every state: start, steps, attention, resume, done. */
object Demo {
    private val main = Handler(Looper.getMainLooper())
    private const val ID = "demo"

    fun run(c: Context) {
        main.removeCallbacksAndMessages(null)
        val app = c.applicationContext
        val script = listOf(
            0L to PresenceEvent(
                id = ID, type = "start", app = "Ayarlar", device = "phone",
                title = "Wi-Fi ayarlarını kontrol ediyor",
                steps = listOf(
                    Step("Ayarları aç", uses = setOf("touch")),
                    Step("Wi-Fi'ı bul"),
                    Step("Ağı seç", uses = setOf("touch")),
                    Step("Kontrol et"),
                ),
            ),
            7_000L to PresenceEvent(id = ID, type = "step", detail = "Aranıyor"),
            11_000L to PresenceEvent(id = ID, type = "step", detail = "3 ağ"),
            15_000L to PresenceEvent(id = ID, type = "attention", message = "Ağ şifresini senin girmen gerekiyor"),
            22_000L to PresenceEvent(id = ID, type = "resume"),
            23_000L to PresenceEvent(id = ID, type = "step", detail = "Bağlanıyor"),
            27_000L to PresenceEvent(id = ID, type = "done", message = "Wi-Fi bağlı, 4 adım tamam"),
        )
        for ((at, e) in script) main.postDelayed({ AgentPresence.send(app, e) }, at)
    }

    fun clear(c: Context) {
        main.removeCallbacksAndMessages(null)
        val app = c.applicationContext
        AgentPresence.send(app, AgentPresence.sessions().map { PresenceEvent(id = it.id, type = "end") })
    }
}

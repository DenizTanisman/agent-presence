package dev.deniz.agentpresence.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import dev.deniz.agentpresence.AgentPresence
import org.json.JSONObject

/**
 * Events from an agent on a computer, over adb (SPEC §6):
 *
 * ```
 * adb shell am broadcast -n dev.deniz.agentpresence.host/.EventReceiver \
 *     -a dev.deniz.agentpresence.EVENT --es b64 <base64 of the event JSON or a JSON array>
 * ```
 *
 * The result data is the base64 state JSON; `am broadcast` prints it as `data="..."`. Without `b64` the
 * broadcast only asks for the state. Result code 0 = applied, 1 = bad event (the data then holds `{"error":...}`).
 */
class EventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (intent.hasExtra("band")) {
            AgentPresence.init(context)
            AgentPresence.minimalInStatusBar = intent.getBooleanExtra("band", false)
        }
        val b64 = intent.getStringExtra("b64")
        val state = try {
            if (b64.isNullOrBlank()) {
                AgentPresence.init(context)
                AgentPresence.state()
            } else {
                val json = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
                AgentPresence.sendJson(context, json)
            }
        } catch (e: Exception) {
            resultCode = 1
            resultData = encode(JSONObject().put("v", 1).put("error", e.message ?: e.toString()).toString())
            return
        }
        resultCode = 0
        resultData = encode(state)
    }

    private fun encode(s: String) = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    companion object {
        const val ACTION = "dev.deniz.agentpresence.EVENT"
    }
}

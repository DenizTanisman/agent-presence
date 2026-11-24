package dev.deniz.agentpresence.host

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.deniz.agentpresence.AgentPresence

/** Permissions, a demo, and how an agent reaches this app. Everything else happens in the overlay. */
class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentPresence.init(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(56), dp(22), dp(32))
        }
        col.addView(text("Agent Presence", 26f, bold = true))
        col.addView(text(
            if (tr) "Bir ajan (Claude) bu telefonu kullanırken ekranın üstünde ne yaptığını, hangi adımda olduğunu ve neye dokunmaman gerektiğini gösterir."
            else "While an agent (Claude) uses this phone, shows on top of the screen what it does, which step it is on and what you must not touch.",
            15f, color = 0xFFA1A1AA.toInt(),
        ).apply { setPadding(0, dp(8), 0, dp(18)) })
        status = text("", 14f, color = 0xFFD4D4D8.toInt())
        col.addView(status)
        col.addView(button(if (tr) "Diğer uygulamaların üstünde gösterme izni" else "Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(button(if (tr) "Bildirim izni" else "Notification permission") {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        })
        col.addView(button(if (tr) "Demoyu oynat" else "Play demo", primary = true) { Demo.run(this) })
        col.addView(button(if (tr) "Tümünü kapat" else "Clear all") { Demo.clear(this) })
        col.addView(text(
            "adb shell am broadcast -n $packageName/.EventReceiver -a dev.deniz.agentpresence.EVENT --es b64 <base64 JSON>",
            12f, color = 0xFF8E8E93.toInt(),
        ).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(20), 0, 0)
            setTextIsSelectable(true)
        })
        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(col)
        })
    }

    override fun onResume() {
        super.onResume()
        val overlay = Settings.canDrawOverlays(this)
        val notif = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val promoted = if (Build.VERSION.SDK_INT >= 36) getSystemService(NotificationManager::class.java).canPostPromotedNotifications() else false
        fun mark(b: Boolean) = if (b) "✓" else "✕"
        status.text = buildString {
            append("${mark(overlay)}  ${if (tr) "Üstte gösterme" else "Overlay"}\n")
            append("${mark(notif)}  ${if (tr) "Bildirim" else "Notifications"}\n")
            append("${mark(promoted)}  Live Update")
        }
    }

    private val tr get() = resources.configuration.locales[0].language == "tr"

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(t: String, size: Float, bold: Boolean = false, color: Int = 0xFFFFFFFF.toInt()) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun button(t: String, primary: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = t
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(if (primary) 0xFF0B1220.toInt() else 0xFFFFFFFF.toInt())
        background = GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            setColor(if (primary) 0xFF93C5FD.toInt() else 0xFF2C2C2E.toInt())
        }
        minHeight = dp(52)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        }
    }
}

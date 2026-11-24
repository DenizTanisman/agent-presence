package dev.deniz.agentpresence

import java.util.Locale

/** Colors from SPEC §3–4 (v1.2 "premium" look). */
internal object Palette {
    // Surfaces.
    const val SURFACE = 0xFF0E0E10.toInt()          // card
    const val BORDER = 0x14FFFFFF                    // 1 px hairline
    const val PANEL = 0x0AFFFFFF                     // inner panels (the task list)
    const val DIVIDER = 0x0FFFFFFF
    const val CAPSULE = 0xFF141416.toInt()
    const val CHIP = 0xFF0E0E10.toInt()              // the disc behind a minimal circle
    const val GLYPH = 0xFF2C2C2E.toInt()             // neutral app glyph

    // Text: never pure white on black.
    const val TEXT = 0xFFF5F5F7.toInt()
    const val TEXT2 = 0xFF9A9AA2.toInt()
    const val TEXT_DONE = 0xFFC7C7CC.toInt()

    // Accents and their text colors.
    const val BLUE = 0xFF3B82F6.toInt()
    const val BLUE_TEXT = 0xFF7AB8FF.toInt()
    const val GREEN = 0xFF30D158.toInt()
    const val GREEN_TEXT = 0xFF4ADE80.toInt()
    const val AMBER = 0xFFFF9F0A.toInt()
    const val AMBER_TEXT = 0xFFFFB340.toInt()
    const val RED = 0xFFFF453A.toInt()
    const val RED_TEXT = 0xFFFF6961.toInt()
    const val PENDING = 0xFF8E8E93.toInt()

    // The step circle (SPEC §3).
    const val BLUE_ORBIT = 0xFF60A5FA.toInt()
    const val BLUE_FILL = 0xFF16233F.toInt()
    const val PENDING_FILL = 0xFF2C2C2E.toInt()
    const val PENDING_TEXT = PENDING

    // Directive capsule gradients (SPEC §4).
    val DONT_GRADIENT = intArrayOf(0xFFFF5A4F.toInt(), 0xFFE5332A.toInt())
    val DO_GRADIENT = intArrayOf(0xFF34D399.toInt(), 0xFF16A34A.toInt())

    /** [color] with its alpha replaced by [share] (0..1). */
    fun alpha(color: Int, share: Float): Int = (color and 0x00FFFFFF) or ((share * 255).toInt().coerceIn(0, 255) shl 24)
}

/** Type from SPEC §4: system font (One UI Sans on Samsung), semibold for titles and labels. */
internal object Type {
    val regular: android.graphics.Typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 400, false)
    val semibold: android.graphics.Typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600, false)
}

/** UI strings, SPEC §5: Turkish by default, English when the phone is not in Turkish. */
internal object Strings {
    private val tr get() = Locale.getDefault().language == "tr"

    val stop get() = if (tr) "Durdur" else "Stop"
    val stopping get() = if (tr) "Durdurma istendi" else "Stop requested"
    val minimize get() = if (tr) "Küçült" else "Minimize"
    val close get() = if (tr) "Kapat" else "Close"
    val ok get() = if (tr) "Tamam" else "OK"
    val done get() = if (tr) "Bitti" else "Done"
    val failed get() = if (tr) "Olmadı" else "Failed"
    val waiting get() = if (tr) "Seni bekliyor" else "Needs you"
    fun stepOf(i: Int, n: Int) = if (tr) "Adım $i/$n" else "Step $i of $n"
    val handsOff get() = if (tr) "Dokunma" else "Hands off"
    val onMac get() = if (tr) "Mac'te" else "On Mac"
    val onPhone get() = if (tr) "Telefonda" else "On phone"
    val running get() = if (tr) "Sürüyor" else "Running"
    val pending get() = if (tr) "Sırada" else "Next"
    val noKeysMouse get() = if (tr) "Klavye ve fareye dokunma" else "Don't touch the keyboard or mouse"
    val noKeys get() = if (tr) "Klavyeye dokunma" else "Don't type"
    val noMouse get() = if (tr) "Fareye dokunma" else "Don't touch the mouse"
    val noTouch get() = if (tr) "Ekrana dokunma" else "Don't touch the screen"
    val show get() = if (tr) "Göster" else "Show"
    val channel get() = if (tr) "Ajan durumu" else "Agent status"
    val channelEnd get() = if (tr) "Ajan bitti / olmadı" else "Agent finished"
}

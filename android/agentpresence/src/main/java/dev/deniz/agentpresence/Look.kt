package dev.deniz.agentpresence

import java.util.Locale

/** Colors from SPEC §3–4. */
internal object Palette {
    // SPEC says #F2121214; on the phone bright text behind the card showed through even at 98 % (there is no
    // blur behind an app overlay), so the card is opaque.
    const val SURFACE = 0xFF121214.toInt()
    const val BORDER = 0x26FFFFFF
    const val TEXT = 0xFFFFFFFF.toInt()
    const val TEXT2 = 0xFFA1A1AA.toInt()

    const val BLUE = 0xFF3B82F6.toInt()
    const val BLUE_ORBIT = 0xFF60A5FA.toInt()
    const val BLUE_FILL = 0xFF16233F.toInt()
    const val BLUE_TEXT = 0xFF93C5FD.toInt()
    const val GREEN = 0xFF34C759.toInt()
    const val AMBER = 0xFFF5A524.toInt()
    const val RED = 0xFFFF453A.toInt()
    const val PENDING_FILL = 0xFF2C2C2E.toInt()
    const val PENDING_TEXT = 0xFF8E8E93.toInt()
    const val LINE = 0xFF3A3A3C.toInt()

    const val DONT_BG = 0xFF3A1515.toInt()
    const val DONT_TEXT = 0xFFFFB4AB.toInt()
    const val DO_BG = 0xFF12301C.toInt()
    const val DO_TEXT = 0xFFA7F3C0.toInt()
    const val WAIT_BG = 0xFF3A2A0A.toInt()
    const val WAIT_TEXT = 0xFFFCD58A.toInt()
    const val BUTTON = 0xFF2C2C2E.toInt()

    // The task list in the expanded card: an inner One UI card, rows tinted by state.
    const val INNER = 0xFF1D1D20.toInt()
    const val DIVIDER = 0xFF2C2C30.toInt()
    const val ROW_ACTIVE = 0xFF172238.toInt()
    const val ROW_WAIT = 0xFF2E2410.toInt()
    const val ROW_FAIL = 0xFF301617.toInt()
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

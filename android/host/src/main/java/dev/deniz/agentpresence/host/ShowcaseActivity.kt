package dev.deniz.agentpresence.host

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View

/**
 * A plain backdrop for screenshots of the overlay: nothing personal behind it.
 * `adb shell am start -n dev.deniz.agentpresence.host/.ShowcaseActivity`
 */
class ShowcaseActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF3B4A66.toInt(), 0xFF1C2436.toInt()),
            )
        })
    }
}

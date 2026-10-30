package dev.genie.android

import android.graphics.Color
import android.widget.TextView

// All Genie text glows, like CSS text-shadow 0 0 12px: white text at 80%, grey text at 48%.
// Thin strokes spread little light, so anything weaker barely shows on black.
fun TextView.glow(bright: Boolean) {
    val radius = (6 * resources.displayMetrics.density - 0.5f) / 0.57735f
    setShadowLayer(radius, 0f, 0f, Color.argb(if (bright) 0xCC else 0x7A, 255, 255, 255))
}

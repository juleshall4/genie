package dev.genie.android

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.widget.TextView

private const val DOT_MILLIS = 400L

// Shows text in a TextView, counting up a trailing ellipsis as ".", "..", "..." while it's on screen.
// The dots not yet shown are still laid out, just not drawn, so the text never changes width.
class CountingDots(private val view: TextView) : Runnable {
    private var base: CharSequence? = null
    private var dots = 0

    fun setText(text: CharSequence) {
        view.removeCallbacks(this)
        if (text.endsWith("…") || text.endsWith("...")) {
            base = text.removeSuffix("…").removeSuffix("...")
            dots = 0
            run()
        } else {
            base = null
            view.text = text
        }
    }

    override fun run() {
        val base = base ?: return
        dots = dots % 3 + 1
        view.text = SpannableStringBuilder(base).append("...").apply {
            if (dots < 3) setSpan(Hidden, length - (3 - dots), length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (view.isAttachedToWindow || !view.isLaidOut) view.postDelayed(this, DOT_MILLIS)
    }

    // Draws nothing, and no glow, while keeping its place in the line.
    private object Hidden : CharacterStyle() {
        override fun updateDrawState(paint: TextPaint) {
            paint.color = Color.TRANSPARENT
            paint.clearShadowLayer()
        }
    }
}

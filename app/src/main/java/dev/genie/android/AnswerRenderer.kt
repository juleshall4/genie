package dev.genie.android

import android.content.Context
import android.graphics.Color
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import org.commonmark.node.Heading
import org.commonmark.node.StrongEmphasis

class AnswerRenderer(context: Context) {
    private val markdown = Markwon.builder(context).usePlugin(object : AbstractMarkwonPlugin() {
        override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
            builder.appendFactory(StrongEmphasis::class.java) { _, _ -> SofterGlow() }
            builder.appendFactory(Heading::class.java) { _, _ -> SofterGlow() }
        }
    }).build()

    fun setAnswer(view: TextView, answer: String) {
        markdown.setMarkdown(view, AnswerText.withoutReferences(answer))
    }
}

// Bold strokes spread more light, so bold text and headings glow half as much as the rest.
private class SofterGlow : CharacterStyle() {
    override fun updateDrawState(paint: TextPaint) {
        val color = paint.shadowLayerColor
        paint.setShadowLayer(paint.shadowLayerRadius, paint.shadowLayerDx, paint.shadowLayerDy,
            Color.argb(Color.alpha(color) / 2, Color.red(color), Color.green(color), Color.blue(color)))
    }
}

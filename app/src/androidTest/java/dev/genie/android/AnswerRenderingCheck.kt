package dev.genie.android

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.widget.TextView

class AnswerRenderingCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        try {
            var renderingFailure: Exception? = null
            runOnMainSync {
                try {
                    val view = TextView(targetContext)
                    val renderer = AnswerRenderer(targetContext)
                    renderer.setAnswer(view, "A **bo")
                    renderer.setAnswer(view, "A **bold** and *italic* answer.")
                    check(view.text.toString() == "A bold and italic answer.") { "Unexpected formatted answer: ${view.text}" }
                    fun paintAt(word: String): TextPaint {
                        val rendered = view.text as Spanned
                        val start = rendered.toString().indexOf(word)
                        check(start >= 0) { "Missing word $word in ${view.text}" }
                        return TextPaint(view.paint).apply {
                            rendered.getSpans(start, start + word.length, CharacterStyle::class.java)
                                .forEach { it.updateDrawState(this) }
                        }
                    }
                    val bold = paintAt("bold")
                    check(bold.isFakeBoldText || bold.typeface?.isBold == true) { "Bold styling is missing" }
                    val italic = paintAt("italic")
                    check(italic.typeface?.isItalic == true || italic.textSkewX != 0f) { "Italic styling is missing" }
                    renderer.setAnswer(view, "A ***strong*** answer.")
                    val strong = paintAt("strong")
                    check(strong.isFakeBoldText || strong.typeface?.isBold == true) { "Combined emphasis is missing bold" }
                    check(strong.typeface?.isItalic == true || strong.textSkewX != 0f) { "Combined emphasis is missing italic" }
                    renderer.setAnswer(view, "A **bold** answer. ([developer.android.com](https://developer.android.com/))")
                    check(view.text.toString() == "A bold answer.") { "Unexpected source-cleaned answer: ${view.text}" }
                    check(paintAt("bold").let { it.isFakeBoldText || it.typeface?.isBold == true }) { "Source cleanup lost bold styling" }
                    renderer.setAnswer(view, "Use `Space`.")
                    check(view.text.contains("Space") && !view.text.contains('`')) { "Inline code delimiters remain visible: ${view.text}" }
                    renderer.setAnswer(view, "A plain answer.")
                    check(view.text.toString() == "A plain answer.") { "Unexpected plain answer: ${view.text}" }
                    check(paintAt("plain").typeface?.isBold != true) { "Prior bold styling leaked into the next answer" }
                } catch (error: Exception) { renderingFailure = error }
            }
            renderingFailure?.let { throw it }
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "Passed: streamed Markdown renders bold and italic, hides delimiters, removes source references, supports inline code, and resets styling for plain answers.\n")
            })
        } catch (error: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Failed: ${error.message} at line ${error.stackTrace.firstOrNull { it.className == javaClass.name }?.lineNumber}\n") })
        }
    }
}

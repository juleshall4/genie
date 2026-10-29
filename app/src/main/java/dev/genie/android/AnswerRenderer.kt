package dev.genie.android

import android.content.Context
import android.widget.TextView
import io.noties.markwon.Markwon

class AnswerRenderer(context: Context) {
    private val markdown = Markwon.create(context)

    fun setAnswer(view: TextView, answer: String) {
        markdown.setMarkdown(view, AnswerText.withoutReferences(answer))
    }
}

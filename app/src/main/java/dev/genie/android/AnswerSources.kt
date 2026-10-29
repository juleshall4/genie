package dev.genie.android

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.Button
import android.widget.TextView
import dev.genie.android.chatgpt.WebCitation

class AnswerSources(private val button: Button, private val links: TextView, private val open: (String) -> Unit) {
    private var expanded = false

    init {
        button.visibility = View.GONE
        links.visibility = View.GONE
        button.setOnClickListener {
            expanded = !expanded
            links.visibility = if (expanded) View.VISIBLE else View.GONE
            button.setText(if (expanded) R.string.hide_sources else R.string.answer_sources)
        }
    }

    fun setSources(citations: List<WebCitation>) {
        expanded = false
        val text = SpannableStringBuilder()
        for (source in citations.distinctBy { it.url }) {
            val uri = runCatching { java.net.URI(source.url) }.getOrNull()
            if (uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrBlank()) continue
            if (text.isNotEmpty()) text.append("\n\n")
            val start = text.length
            if (source.title.isNotBlank()) text.append(source.title).append("\n")
            text.append(source.url)
            text.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = open(source.url)
            }, start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        links.text = text
        links.movementMethod = LinkMovementMethod.getInstance()
        links.visibility = View.GONE
        button.setText(R.string.answer_sources)
        button.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }
}

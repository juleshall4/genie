package dev.genie.android

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View
import dev.genie.android.chatgpt.WebCitation

object CitedAnswer {
    fun render(text: String, citations: List<WebCitation>, open: (String) -> Unit): CharSequence {
        val result = SpannableStringBuilder(text)
        for ((index, citation) in citations.withIndex().sortedByDescending { it.value.start }) {
            if (citation.start !in 0..result.length || citation.end !in citation.start..result.length) continue
            var end = citation.end
            val cited = result.substring(citation.start, end)
            if (cited.matches(Regex("\\uE200[^\\uE201]*\\uE201")) || citation.start == end) {
                val label = "[${index + 1}]"
                result.replace(citation.start, end, label)
                end = citation.start + label.length
            } else if (cited.startsWith("[") && cited.endsWith(")") && cited.contains("](")) {
                result.replace(citation.start, end, citation.title)
                end = citation.start + citation.title.length
            }
            result.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = open(citation.url)
            }, citation.start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return result
    }
}

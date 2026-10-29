package dev.genie.android

import java.util.Locale

/** Releases complete sentences from cumulative answer updates, then flushes the final remainder. */
class StreamingTextBuffer {
    private var consumed = 0

    fun update(answer: String, finished: Boolean = false): List<String> {
        var safe = answer
        if (!finished) {
            val citation = safe.lastIndexOf('\uE200')
            if (citation >= 0 && safe.indexOf('\uE201', citation) < 0) safe = safe.take(citation)
            val fences = Regex("```").findAll(safe).toList()
            if (fences.size % 2 == 1) safe = safe.take(fences.last().range.first)
            // Hold a parenthetical citation until the whole reference is known.
            val reference = Regex("\\(\\s*\\[").findAll(safe).lastOrNull()?.range?.first
            if (reference != null) {
                var depth = 0
                var closed = false
                for (index in reference until safe.length) {
                    if (safe[index] == '(') depth++
                    if (safe[index] == ')' && --depth == 0) { closed = true; break }
                }
                if (!closed) safe = safe.take(reference)
            }
            val openLink = safe.lastIndexOf('[')
            if (openLink >= 0) {
                val labelEnd = safe.indexOf(']', openLink)
                if (labelEnd < 0 || labelEnd == safe.lastIndex ||
                    (safe[labelEnd + 1] == '(' && safe.indexOf(')', labelEnd + 1) < 0)) {
                    safe = safe.take(openLink)
                }
            }
        }
        val text = SpokenText.chunks(safe, Int.MAX_VALUE).joinToString(" ")
        if (text.length < consumed) return emptyList()
        val remaining = text.substring(consumed)
        val boundary = if (finished) remaining.length
            else sentenceBoundary(remaining, safe.lastOrNull()?.isWhitespace() == true)
        if (boundary == 0) return emptyList()
        consumed += boundary
        val chunks = SpokenText.splitPlain(remaining.take(boundary), 1000)
        return chunks
    }

    private fun sentenceBoundary(text: String, trailingWhitespace: Boolean): Int {
        var boundary = 0
        for (ending in sentenceEnd.findAll(text)) {
            val end = ending.range.last + 1
            // A final dot can still become part of a streamed decimal or abbreviation.
            if (end == text.length && !trailingWhitespace) continue
            if (ending.groupValues[1] == "." && isAbbreviation(text, ending.range.first)) continue
            boundary = end
        }
        return boundary
    }

    private fun isAbbreviation(text: String, period: Int): Boolean {
        val prefix = text.take(period)
        val word = prefix.takeLastWhile { it.isLetter() || it == '.' }
        return word.lowercase(Locale.ROOT) in abbreviations || word.length == 1 ||
            initials.matches(word) || prefix.trim().matches(numberedItem)
    }

    companion object {
        private val sentenceEnd = Regex("""([.!?。！？]+)["'’”)\]]*(?=\s|$)""")
        private val initials = Regex("""(?:\p{L}\.)+\p{L}""")
        private val numberedItem = Regex("""\d+""")
        private val abbreviations = setOf("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc")
    }
}

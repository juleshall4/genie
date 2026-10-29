package dev.genie.android

/** Releases complete words from cumulative answer updates without waiting for sentences. */
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
        val boundary = if (finished || safe.lastOrNull()?.isWhitespace() == true) remaining.length
            else remaining.indexOfLast { it.isWhitespace() }.let { if (it < 0) 0 else it + 1 }
        if (boundary == 0) return emptyList()
        consumed += boundary
        val chunks = SpokenText.splitPlain(remaining.take(boundary), 300)
        return chunks
    }
}

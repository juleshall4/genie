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
            // A link label can change when its URL arrives; wait until the link is complete.
            val openLink = safe.lastIndexOf('[')
            if (openLink >= 0 && safe.indexOf(')', openLink) < 0) safe = safe.take(openLink)
        }
        val text = SpokenText.chunks(safe, Int.MAX_VALUE).joinToString(" ")
        if (text.length < consumed) return emptyList()
        val remaining = text.substring(consumed)
        val boundary = if (finished || safe.lastOrNull()?.isWhitespace() == true) remaining.length
            else remaining.indexOfLast { it.isWhitespace() }.let { if (it < 0) 0 else it + 1 }
        if (boundary == 0) return emptyList()
        consumed += boundary
        val chunks = SpokenText.chunks(remaining.take(boundary), 300)
        return chunks
    }
}

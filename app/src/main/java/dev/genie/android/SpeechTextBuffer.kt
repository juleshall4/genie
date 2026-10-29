package dev.genie.android

/** Releases stable sentences from the cumulative answer without repeating earlier text. */
class SpeechTextBuffer {
    private var consumed = 0

    fun update(answer: String, finished: Boolean = false): List<String> {
        var safe = answer
        if (!finished) {
            val fences = Regex("```").findAll(answer).toList()
            if (fences.size % 2 == 1) safe = safe.take(fences.last().range.first)
            // A link label can change when its URL arrives; wait until the link is complete.
            val openLink = safe.lastIndexOf('[')
            if (openLink >= 0 && safe.indexOf(')', openLink) < 0) safe = safe.take(openLink)
        }
        val text = SpokenText.chunks(safe, Int.MAX_VALUE).joinToString(" ")
        if (text.length < consumed) return emptyList()
        val remaining = text.substring(consumed)
        val boundary = if (finished) remaining.length else {
            val sentence = Regex("[.!?][\"')]*\\s+|\\n").findAll(remaining)
                .firstOrNull { it.range.last >= 20 }?.range?.last?.plus(1)
            sentence ?: if (remaining.length >= 300) remaining.lastIndexOf(' ', 300).coerceAtLeast(0) else 0
        }
        if (boundary == 0) return emptyList()
        consumed += boundary
        val chunks = SpokenText.chunks(remaining.take(boundary), 300)
        // Drain further stable sentences without waiting for another token callback.
        return chunks + if (!finished) update(answer) else emptyList()
    }
}

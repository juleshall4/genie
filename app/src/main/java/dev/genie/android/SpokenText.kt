package dev.genie.android

object SpokenText {
    fun chunks(markdown: String, limit: Int): List<String> {
        require(limit > 1)
        var text = markdown
            .replace(Regex("\uE200[^\uE201]*\uE201"), "")
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("!?\\[([^]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
            .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
            .replace(Regex("[*_`]+"), "")
            .trim()
        val chunks = mutableListOf<String>()
        while (text.length > limit) {
            var end = text.lastIndexOf(' ', limit)
            if (end <= 0) {
                end = limit
                if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            }
            chunks.add(text.substring(0, end))
            text = text.substring(end).trimStart()
        }
        if (text.isNotEmpty()) chunks.add(text)
        return chunks
    }
}

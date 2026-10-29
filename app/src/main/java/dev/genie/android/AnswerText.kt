package dev.genie.android

/** Removes reference-only text while retaining meaningful words that happen to be linked. */
object AnswerText {
    private val link = Regex("!?\\[([^]]*)]\\([^)]*\\)")
    private val addressLabel = Regex("(?i)(?:https?://|www\\.)\\S*|(?:[a-z0-9-]+\\.)+[a-z]{2,}(?:[/?#]\\S*)?")

    fun withoutReferences(markdown: String): String = markdown
        .replace(Regex("\uE200[^\uE201]*\uE201"), "")
        .replace(Regex("\uE200[^\uE201]*$"), "")
        .replace(Regex("(?ims)^\\s*(?:#{1,6}\\s*)?\\*{0,2}(?:sources?|references?)\\*{0,2}\\s*(?::|$).*"), "")
        .replace(Regex("(?m)^\\s*\\[\\d+]\\s*:\\s*.*$"), "")
        .replace(Regex("\\(\\s*(?:\\[[^]]*]\\([^)]*\\)\\s*[,;]?\\s*)+\\)"), "")
        .replace(Regex("(?m)(?<=[.!?])\\s+(?:\\[[^]]*]\\([^)]*\\)\\s*)+$"), "")
        .replace(link) {
            val label = it.groupValues[1].trim()
            if (addressLabel.matches(label) || label.matches(Regex("(?i)(?:source\\s*)?\\d*"))) "" else label
        }
        .replace(Regex("(?i)(?:https?://|www\\.)[^\\s<>()\\[\\]]+"), "")
        .replace(Regex("(?i)(?<![\\w@])(?:[a-z0-9-]+\\.)+(?:com|org|net|edu|gov|io|ai|co\\.uk)\\b(?:[/?#][^\\s<>()\\[\\]]*)?"), "")
        .replace(Regex("\\[\\d+(?:\\s*[,–-]\\s*\\d+)*]"), "")
        .replace(Regex("\\([\\s,;]*\\)"), "")
        .replace(Regex("[ \\t]+([.,;:!?])"), "$1")
        .replace(Regex("[ \\t]{2,}"), " ")
        .trim()
}

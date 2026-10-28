package dev.genie.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenTextTest {
    @Test
    fun speaksLabelsInsteadOfMarkdownAndCode() {
        val text = "# Answer\n**Read** [this](https://example.com).\n```kotlin\nprintln(123)\n```"
        assertEquals(listOf("Answer\nRead this."), SpokenText.chunks(text, 4000))
    }

    @Test
    fun keepsLongAnswersWithinAndroidInputLimitWithoutLosingWords() {
        val answer = "A longer answer with several sentences. ".repeat(300).trim()
        val chunks = SpokenText.chunks(answer, 4000)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 4000 && it.isNotBlank() })
        assertEquals(answer, chunks.joinToString(" "))
    }

    @Test
    fun preservesUnicodeWhenSplittingAnUnbrokenString() {
        val answer = "a😀😀😀😀"
        val chunks = SpokenText.chunks(answer, 4)
        assertTrue(chunks.all { it.length <= 4 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
        assertEquals(answer, chunks.joinToString(""))
    }
}

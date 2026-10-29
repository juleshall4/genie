package dev.genie.android

import org.junit.Assert.*
import org.junit.Test

class SpeechTextBufferTest {
    @Test fun releasesFirstSentenceBeforeTheAnswerFinishesWithoutRepeatingIt() {
        val buffer = SpeechTextBuffer()
        assertTrue(buffer.update("A chicken is").isEmpty())
        assertEquals(listOf("A chicken is usually thirty centimetres tall."),
            buffer.update("A chicken is usually thirty centimetres tall. It"))
        assertTrue(buffer.update("A chicken is usually thirty centimetres tall. It weighs").isEmpty())
        assertEquals(listOf("It weighs two kilograms."),
            buffer.update("A chicken is usually thirty centimetres tall. It weighs two kilograms.", true))
        assertTrue(buffer.update("A chicken is usually thirty centimetres tall. It weighs two kilograms.", true).isEmpty())
    }

    @Test fun holdsIncompleteCodeAndLinksAndSpeaksOnlyReadableContent() {
        val buffer = SpeechTextBuffer()
        assertTrue(buffer.update("```kotlin\nThis is code. More code.").isEmpty())
        assertTrue(buffer.update("```kotlin\nThis is code. More code.\n```\nRead [this").isEmpty())
        assertEquals(listOf("Read this page for details."), buffer.update(
            "```kotlin\nThis is code. More code.\n```\nRead [this page](https://example.com) for details.", true))
    }

    @Test fun longUnpunctuatedAnswerStartsAtWordBoundaryAndPreservesWords() {
        val text = "a longer answer without punctuation ".repeat(30).trim()
        val buffer = SpeechTextBuffer()
        val chunks = buffer.update(text)
        assertTrue(chunks.isNotEmpty())
        val all = chunks + buffer.update(text, true)
        assertEquals(text, all.joinToString(" "))
        assertTrue(all.all { it.length <= 300 })
    }
}

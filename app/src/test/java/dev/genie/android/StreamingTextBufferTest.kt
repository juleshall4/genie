package dev.genie.android

import org.junit.Assert.*
import org.junit.Test

class StreamingTextBufferTest {
    @Test fun sendsWordsBeforeTheSentenceFinishesWithoutRepeatingText() {
        val buffer = StreamingTextBuffer()
        assertTrue(buffer.update("Chick").isEmpty())
        assertEquals(listOf("Chickens"), buffer.update("Chickens are"))
        assertEquals(listOf("are usually"), buffer.update("Chickens are usually "))
        assertTrue(buffer.update("Chickens are usually ").isEmpty())
        assertEquals(listOf("small."), buffer.update("Chickens are usually small.", true))
        assertTrue(buffer.update("Chickens are usually small.", true).isEmpty())
    }

    @Test fun holdsIncompleteCodeAndLinksAndSpeaksOnlyReadableContent() {
        val buffer = StreamingTextBuffer()
        assertTrue(buffer.update("```kotlin\nThis is code. More code.").isEmpty())
        assertEquals(listOf("Read"), buffer.update("```kotlin\nThis is code. More code.\n```\nRead [this"))
        assertEquals(listOf("this page for details."), buffer.update(
            "```kotlin\nThis is code. More code.\n```\nRead [this page](https://example.com) for details.", true))
    }

    @Test fun preservesAllWordsAcrossLongUpdatesAndFinalFlush() {
        val text = "a longer answer without punctuation ".repeat(30).trim()
        val buffer = StreamingTextBuffer()
        val chunks = mutableListOf<String>()
        for (end in 1..text.length) chunks.addAll(buffer.update(text.take(end)))
        chunks.addAll(buffer.update(text, true))
        assertEquals(text, chunks.joinToString(" "))
    }
    @Test fun neverSpeaksCompleteOrPartialProviderCitationMarkers() {
        val buffer = StreamingTextBuffer()
        val chunks = buffer.update("Today it is sunny. \uE200cite\uE202turn0") +
            buffer.update("Today it is sunny. \uE200cite\uE202turn0search0\uE201 More details.", true)
        assertEquals("Today it is sunny. More details.", chunks.joinToString(" ").replace(Regex("\\s+"), " "))
        assertFalse(chunks.joinToString().contains("search0"))
    }

}

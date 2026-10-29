package dev.genie.android

import org.junit.Assert.*
import org.junit.Test

class StreamingTextBufferTest {
    @Test fun holdsWordsUntilTheSentenceFinishesWithoutRepeatingText() {
        val buffer = StreamingTextBuffer()
        assertTrue(buffer.update("The temperature ").isEmpty())
        assertTrue(buffer.update("The temperature is ").isEmpty())
        assertTrue(buffer.update("The temperature is high").isEmpty())
        assertTrue(buffer.update("The temperature is high.").isEmpty())
        assertEquals(listOf("The temperature is high."), buffer.update("The temperature is high. Next"))
        assertTrue(buffer.update("The temperature is high. Next").isEmpty())
        assertEquals(listOf("Next unfinished thought"), buffer.update("The temperature is high. Next unfinished thought", true))
        assertTrue(buffer.update("The temperature is high. Next unfinished thought", true).isEmpty())
    }

    @Test fun holdsIncompleteCodeAndLinksAndSpeaksOnlyReadableContent() {
        val buffer = StreamingTextBuffer()
        assertTrue(buffer.update("```kotlin\nThis is code. More code.").isEmpty())
        assertTrue(buffer.update("```kotlin\nThis is code. More code.\n```\nRead [this").isEmpty())
        assertEquals(listOf("Read this page for details."), buffer.update(
            "```kotlin\nThis is code. More code.\n```\nRead [this page](https://example.com) for details.", true))
    }

    @Test fun doesNotMistakeStreamedDecimalsOrAbbreviationsForSentenceEnds() {
        val buffer = StreamingTextBuffer()
        assertTrue(buffer.update("Dr. ").isEmpty())
        assertTrue(buffer.update("Dr. Smith says it is 3.").isEmpty())
        assertTrue(buffer.update("Dr. Smith says it is 3.14 ").isEmpty())
        assertEquals(listOf("Dr. Smith says it is 3.14 metres high."),
            buffer.update("Dr. Smith says it is 3.14 metres high. Next"))
    }

    @Test fun preservesSentencePunctuationAndClosingQuotes() {
        for (sentence in listOf("Is it high?", "It is high!", "She said, \"It is high.\"", "It is high… Really?")) {
            val buffer = StreamingTextBuffer()
            assertEquals(listOf(sentence), buffer.update("$sentence "))
            assertTrue(buffer.update("$sentence ").isEmpty())
        }
    }

    @Test fun keepsAnUnpunctuatedAnswerUntilTheFinalFlush() {
        val buffer = StreamingTextBuffer()
        val answer = "the temperature is high but it will fall later"
        for (end in 1..answer.length) assertTrue(buffer.update(answer.take(end)).isEmpty())
        assertEquals(listOf(answer), buffer.update(answer, true))
    }

    @Test fun streamsCompleteSentencesFromCharacterUpdatesAndKeepsPayloadsWithinTheApiLimit() {
        val answer = "The temperature is high. Is it safe? Yes, it is! " + "Warm 🙂 weather ".repeat(400) + "Today."
        val buffer = StreamingTextBuffer()
        val chunks = mutableListOf<String>()
        for (end in 1..answer.length) chunks.addAll(buffer.update(answer.take(end)))
        chunks.addAll(buffer.update(answer, true))
        assertEquals("The temperature is high.", chunks.first())
        assertEquals(answer, chunks.joinToString(" "))
        assertTrue(chunks.all { (it + " ").toByteArray(Charsets.UTF_8).size <= 5000 })
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

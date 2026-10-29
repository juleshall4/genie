package dev.genie.android.chatgpt

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptFormattingTest {
    @Test fun acceptsCapitalizationPunctuationAndApostrophes() {
        for ((raw, formatted) in listOf(
            "what is the weather like in Liverpool" to "What is the weather like in Liverpool?",
            "im home can you help" to "I'm home. Can you help?",
            "yes lets go" to "Yes, let’s go.",
            "où est paris" to "Où est Paris ?"
        )) assertEquals(formatted, TranscriptFormatting.accept(raw, formatted))
    }

    @Test fun rejectsAnswersRewritesAndChangesToWordBoundaries() {
        val raw = "what is the weather like in Liverpool"
        for (invalid in listOf("", "Liverpool is rainy.", "What's the weather like in Liverpool?",
            "Formatted transcript: What is the weather like in Liverpool?", "What is the Liverpool weather like in?")) {
            assertEquals(raw, TranscriptFormatting.accept(raw, invalid))
        }
        assertEquals("now here", TranscriptFormatting.accept("now here", "Nowhere."))
        assertEquals("set timer for 15 minutes", TranscriptFormatting.accept("set timer for 15 minutes", "Set timer for 50 minutes."))
    }
}

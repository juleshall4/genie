package dev.genie.android

import org.junit.Assert.*
import org.junit.Test

class AnswerTextTest {
    @Test fun removesDomainCitationsButPreservesMeaningfulLinkedWords() {
        assertEquals("The Pixel has a camera.", AnswerText.withoutReferences(
            "The [Pixel](https://store.google.com/) has a camera. ([developer.android.com](https://developer.android.com/))"))
        assertEquals("A useful answer.", AnswerText.withoutReferences(
            "A useful answer. [1] ([source](https://example.com))\n\nSources:\n[Example](https://example.com)"))
    }

    @Test fun removesBareAddressesAndReferenceSections() {
        assertEquals("A useful answer.", AnswerText.withoutReferences("A useful answer. (https://example.com/path)"))
        assertEquals("A useful answer.", AnswerText.withoutReferences("A useful answer. bbc.co.uk"))
        assertEquals("A useful answer.", AnswerText.withoutReferences("A useful answer.\n\n**References**\n1. https://example.com"))
    }

    @Test fun streamedLinksNeverLeakIntoSubmittedSpeech() {
        for (reference in listOf(
            "([developer.android.com](https://developer.android.com/latest-updates?utm_source=openai))",
            "https://example.com/very/long/path",
            "www.example.com/long/path",
            "[1]",
            "developer.android.com/path/",
            "([Android Developers](https://developer.android.com/))",
            "\n\nSources:\n[Example](https://example.com)"
        )) {
            val answer = "A useful answer. $reference"
            val buffer = StreamingTextBuffer()
            val chunks = mutableListOf<String>()
            for (end in 1..answer.length) chunks.addAll(buffer.update(answer.take(end)))
            chunks.addAll(buffer.update(answer, true))
            assertEquals(reference, "A useful answer.", chunks.joinToString(" "))
        }
    }
}

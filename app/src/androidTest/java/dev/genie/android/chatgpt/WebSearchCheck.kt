package dev.genie.android.chatgpt

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle

/** Opt-in live search through the same account, settings, and transport as the popup. */
class WebSearchCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        if (arguments?.getString("live_chatgpt") == "true") start()
        else finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Skipped: live_chatgpt=true required.") })
    }

    override fun onStart() {
        runOnMainSync {
            check(sourcePanelWorks(listOf(WebCitation(0, 0, "https://developer.android.com/", "Android"))))
            check(dev.genie.android.AnswerText.withoutReferences("A fact. ([developer.android.com](https://developer.android.com/))") == "A fact.")
        }
        var answer = ""
        var searched = false
        val speechBuffer = dev.genie.android.StreamingTextBuffer()
        val spoken = mutableListOf<String>()
        var citations = emptyList<WebCitation>()
        val client = ChatGptClient.get(targetContext)
        client.answer(listOf(ChatTurn("user", "Search the web for the Android 16 release date on developer.android.com. Cite your source and answer in one short sentence.")),
            onText = { answer = it; spoken.addAll(speechBuffer.update(it)) },
            onSearching = { searched = true },
            onCitations = { citations = it },
            onDone = { error ->
                spoken.addAll(speechBuffer.update(answer, true))
                val speechClean = spoken.none { it.contains("https://") || it.contains("developer.android.com") || it.contains('\uE200') }
                val linked = citations.isNotEmpty() && sourcePanelWorks(citations)
                val passed = error == null && searched && linked && speechClean && answer.isNotBlank()
                finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
                    putString("stream", if (passed) "Passed: live web search executed, answer streamed, ${citations.size} sources behind a working Sources toggle; streamed speech excludes links.\n"
                        else "Failed: ${error?.let { targetContext.getString(it) } ?: "Search observed=$searched; citations=${citations.size}; answer=${answer.take(240)}"}\n")
                })
            })
    }
    private fun sourcePanelWorks(citations: List<WebCitation>): Boolean {
        val button = android.widget.Button(targetContext)
        val links = android.widget.TextView(targetContext)
        var opened: String? = null
        val panel = dev.genie.android.AnswerSources(button, links) { opened = it }
        panel.setSources(citations)
        if (button.visibility != android.view.View.VISIBLE || links.visibility != android.view.View.GONE) return false
        button.performClick()
        if (links.visibility != android.view.View.VISIBLE) return false
        val content = links.text as android.text.Spanned
        val spans = content.getSpans(0, content.length, android.text.style.ClickableSpan::class.java)
        if (spans.isEmpty()) return false
        spans.first().onClick(links)
        if (citations.none { it.url == opened }) return false
        button.performClick()
        if (links.visibility != android.view.View.GONE) return false
        panel.setSources(emptyList())
        return button.visibility == android.view.View.GONE && links.visibility == android.view.View.GONE
    }

}

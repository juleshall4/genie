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
            val raw = "A fact. \uE200cite\uE202turn0search0\uE201"
            var opened: String? = null
            val rendered = dev.genie.android.CitedAnswer.render(raw,
                listOf(WebCitation(8, raw.length, "https://developer.android.com/", "Android"))) { opened = it }
            check(rendered.toString() == "A fact. [1]")
            val spans = (rendered as android.text.Spanned).getSpans(0, rendered.length, android.text.style.ClickableSpan::class.java)
            check(spans.size == 1)
            spans.single().onClick(android.widget.TextView(targetContext))
            check(opened == "https://developer.android.com/")
        }
        var answer = ""
        var searched = false
        var citations = emptyList<WebCitation>()
        val client = ChatGptClient.get(targetContext)
        client.answer(listOf(ChatTurn("user", "Search the web for the Android 16 release date on developer.android.com. Cite your source and answer in one short sentence.")),
            onText = { answer = it },
            onSearching = { searched = true },
            onCitations = { citations = it },
            onDone = { error ->
                var linked = false
                if (citations.isNotEmpty()) {
                    var opened: String? = null
                    val rendered = dev.genie.android.CitedAnswer.render(answer, citations) { opened = it } as android.text.Spanned
                    val spans = rendered.getSpans(0, rendered.length, android.text.style.ClickableSpan::class.java)
                    if (spans.isNotEmpty()) {
                        spans.first().onClick(android.widget.TextView(targetContext))
                        linked = citations.any { it.url == opened }
                    }
                }
                val passed = error == null && searched && linked && answer.isNotBlank()
                finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
                    putString("stream", if (passed) "Passed: live web search executed, answer streamed, ${citations.size} clickable sources received.\n"
                        else "Failed: ${error?.let { targetContext.getString(it) } ?: "Search observed=$searched; citations=${citations.size}; answer=${answer.take(240)}"}\n")
                })
            })
    }
}

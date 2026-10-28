package dev.genie.android.chatgpt

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in live measurement. Uses the saved account and settings without changing them. */
class ResponseLatencyCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        if (arguments?.getString("live_chatgpt") == "true") start()
        else finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Pass live_chatgpt=true to measure live requests.") })
    }

    override fun onStart() {
        val results = mutableListOf<String>()
        val client = ChatGptClient.get(targetContext)
        try {
            val loaded = CountDownLatch(1)
            var selected = ""
            var failure: Int? = null
            val modelStart = SystemClock.elapsedRealtime()
            val models = client.models { catalog, error ->
                failure = error
                if (catalog != null) {
                    val account = requireNotNull(client.store.active())
                    val selection = client.settings.selection(account.clientId, catalog)
                    selected = "${selection.model.slug} / ${selection.effort ?: "default"}"
                }
                loaded.countDown()
            }
            if (!loaded.await(45, TimeUnit.SECONDS)) {
                models.cancel()
                error("Model lookup timed out")
            }
            check(failure == null) { targetContext.getString(requireNotNull(failure)) }
            results.add("Model lookup: ${SystemClock.elapsedRealtime() - modelStart} ms; selection: $selected")
            repeat(2) { run ->
                val done = CountDownLatch(1)
                var firstText = -1L
                val started = SystemClock.elapsedRealtime()
                val request = client.answer(listOf(ChatTurn("user", "How tall is an adult chicken? Answer in one short sentence.")),
                    onText = { if (firstText < 0 && it.isNotBlank()) firstText = SystemClock.elapsedRealtime() - started },
                    onDone = { failure = it; done.countDown() })
                if (!done.await(60, TimeUnit.SECONDS)) {
                    request.cancel()
                    error("Answer timed out")
                }
                check(failure == null) { targetContext.getString(requireNotNull(failure)) }
                check(firstText >= 0) { "No answer text delivered" }
                results.add("Run ${run + 1}: first text $firstText ms; complete ${SystemClock.elapsedRealtime() - started} ms")
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", results.joinToString("\n")) })
        } catch (error: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", (results + "FAIL: ${error.message}").joinToString("\n")) })
        }
    }
}

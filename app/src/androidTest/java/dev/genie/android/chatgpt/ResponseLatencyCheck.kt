package dev.genie.android.chatgpt

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in live measurement. restore_low=true saves Low; other settings remain unchanged. */
class ResponseLatencyCheck : Instrumentation() {
    private var restoreLow = false
    private var backgroundRefresh = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        restoreLow = arguments?.getString("restore_low") == "true"
        backgroundRefresh = arguments?.getString("background_refresh") == "true"
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
                    if (restoreLow) {
                        val model = catalog.selectModel(client.settings.model(account.clientId))
                        model.selectReasoning("low")
                        client.settings.setReasoning(account.clientId, model.slug, "low")
                    }
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
            client.prepare()
            // Simulate time spent speaking, during which the overlay prepares its connection.
            Thread.sleep(1500)
            results.add("Warm connection measurement; preparation time excluded.")
            repeat(3) { run ->
                val done = CountDownLatch(1)
                var firstText = -1L
                val started = SystemClock.elapsedRealtime()
                val refreshed = CountDownLatch(if (backgroundRefresh) 1 else 0)
                val refreshTime = java.util.concurrent.atomic.AtomicLong(-1)
                val refresh = if (backgroundRefresh) client.models { _, _ ->
                    refreshTime.set(SystemClock.elapsedRealtime() - started)
                    refreshed.countDown()
                } else null
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
                if (!refreshed.await(30, TimeUnit.SECONDS)) {
                    refresh?.cancel()
                    error("Background model refresh timed out")
                }
                if (backgroundRefresh) results.add("Background refresh completed at ${refreshTime.get()} ms")
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", results.joinToString("\n")) })
        } catch (error: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", (results + "FAIL: ${error.message}").joinToString("\n")) })
        } finally {
            client.closeConnection()
        }
    }
}

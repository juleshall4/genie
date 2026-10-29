package dev.genie.android.chatgpt

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TranscriptFormattingCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        if (arguments?.getString("live_chatgpt") == "true") start()
        else finish(Activity.RESULT_CANCELED, Bundle())
    }

    override fun onStart() {
        val client = ChatGptClient.get(targetContext)
        val started = SystemClock.elapsedRealtime()
        val done = CountDownLatch(1)
        var formatted = ""
        var failure: Int? = null
        val request = client.formatTranscript("what is the weather like in Liverpool") { text, error ->
            formatted = text
            failure = error
            done.countDown()
        }
        try {
            check(done.await(30, TimeUnit.SECONDS)) { "Formatting timed out" }
            check(failure == null) { "Formatting request failed: ${failure?.let { targetContext.getString(it) }}" }
            check(formatted == "What is the weather like in Liverpool?") {
                "Final transcript is still incorrect: $formatted"
            }
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "PASS: $formatted (${SystemClock.elapsedRealtime() - started} ms)\n")
            })
        } catch (error: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: ${error.message}\n") })
        } finally { request.cancel(); client.closeConnection() }
    }
}

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
            repeat(3) { turn ->
                val answered = CountDownLatch(1)
                var answer = ""
                var answerError: Int? = null
                val response = client.answer(listOf(ChatTurn("user", formatted)), onText = { answer = it }, onDone = {
                    answerError = it
                    answered.countDown()
                })
                try {
                    check(answered.await(45, TimeUnit.SECONDS)) { "Answer ${turn + 1} timed out" }
                    check(answerError == null) { "Answer ${turn + 1} failed: ${answerError?.let { targetContext.getString(it) }}" }
                    check(answer.isNotBlank()) { "Answer ${turn + 1} was empty" }
                } finally { response.cancel() }
            }
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "PASS: $formatted (${SystemClock.elapsedRealtime() - started} ms)\n")
            })
        } catch (error: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: ${error.message}\n") })
        } finally { request.cancel(); client.closeConnection() }
    }
}

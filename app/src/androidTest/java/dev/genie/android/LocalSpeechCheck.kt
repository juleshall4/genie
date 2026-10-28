package dev.genie.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real-engine smoke check: Nano must be installed before running this instrumentation. */
class LocalSpeechCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        var activity: Activity? = null
        var speech: LocalSpeechOutput? = null
        try {
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val screen = activity
            runOnMainSync { screen.findViewById<Button>(R.id.read_aloud_test).performClick() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            var finished = false
            while (System.nanoTime() < deadline) {
                var status = ""
                runOnMainSync { status = screen.findViewById<TextView>(R.id.read_aloud_status).text.toString() }
                check(status != targetContext.getString(R.string.read_aloud_failed)) { status }
                check(status != targetContext.getString(R.string.read_aloud_download)) { status }
                check(status != targetContext.getString(R.string.read_aloud_install)) { status }
                if (status == targetContext.getString(R.string.read_aloud_ready)) {
                    finished = true
                    break
                }
                Thread.sleep(100)
            }
            check(finished) { "Preview did not finish playing" }

            val stopped = CountDownLatch(1)
            val failure = AtomicReference<String?>(null)
            runOnMainSync {
                speech = LocalSpeechOutput(screen) { status ->
                    if (status == R.string.read_aloud_speaking) {
                        speech?.stop()
                        stopped.countDown()
                    } else if (status == R.string.read_aloud_failed || status == R.string.read_aloud_ready) {
                        failure.set("Unexpected status before cancellation: $status")
                        stopped.countDown()
                    }
                }.also { it.speak("This is a longer answer. ".repeat(50)) }
            }
            check(stopped.await(30, TimeUnit.SECONDS)) { "Cancellation speech did not start" }
            Thread.sleep(1000)
            check(failure.get() == null) { failure.get().orEmpty() }
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "PASS: Genie preview played to completion using offline Inflect Nano; active speech stops without stale completion callbacks.")
            })
        } catch (error: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: ${error.message}") })
        } finally {
            runOnMainSync {
                speech?.close()
                activity?.finish()
            }
        }
    }
}

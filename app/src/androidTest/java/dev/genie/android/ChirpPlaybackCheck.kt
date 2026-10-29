package dev.genie.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in live check: submits two short speech requests to the user's Google project. */
class ChirpPlaybackCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        if (arguments?.getString("live_chirp") == "true") start()
        else finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Skipped: live_chirp=true required.") })
    }

    override fun onStart() {
        var speech: ChirpSpeechOutput? = null
        var screen: Activity? = null
        val complete = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        var error: Int? = null
        var speaking = false
        var stopping = false
        try {
            screen = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runOnMainSync {
                speech = ChirpSpeechOutput(screen!!) { status ->
                    when (status) {
                        R.string.read_aloud_preparing -> Unit
                        R.string.read_aloud_speaking -> {
                            speaking = true
                            if (stopping) { speech?.stop(); interrupted.countDown() }
                        }
                        R.string.read_aloud_ready -> complete.countDown()
                        else -> { error = status; complete.countDown(); interrupted.countDown() }
                    }
                }
                speech?.speak("Hello, I’m Genie. This is my British voice, powered by Google Chirp 3 HD.")
            }
            check(complete.await(60, TimeUnit.SECONDS)) { "Voice test timed out" }
            check(error == null) { targetContext.getString(error!!) }
            check(speaking) { "Playback never started" }
            runOnMainSync {
                stopping = true
                speech?.speak("This is a second short voice test. Genie should interrupt this answer as soon as playback starts.")
            }
            check(interrupted.await(60, TimeUnit.SECONDS)) { "Interruption test timed out" }
            check(error == null) { targetContext.getString(error!!) }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "Passed: live Chirp synthesis, MP3 playback to completion, and stopping playback.\n") })
        } catch (failure: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Failed: ${failure.message}\n") })
        } finally {
            runOnMainSync { speech?.close(); screen?.finish() }
        }
    }
}

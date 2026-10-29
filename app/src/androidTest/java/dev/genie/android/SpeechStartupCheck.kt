package dev.genie.android

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.content.Intent
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SpeechStartupCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        var input: NativeSpeechInput? = null
        val timings = mutableListOf<Long>()
        try {
            val recovered = CountDownLatch(1)
            var attempts = 0
            runOnMainSync {
                input = NativeSpeechInput(targetContext, onUpdate = { status, _, finished ->
                    if (status == R.string.speech_listening || finished) recovered.countDown()
                }, createEngine = {
                    val number = ++attempts
                    object : SpeechEngine {
                        private lateinit var listener: RecognitionListener
                        override fun setRecognitionListener(listener: RecognitionListener) { this.listener = listener }
                        override fun startListening(intent: Intent) { if (number > 1) listener.onReadyForSpeech(Bundle()) }
                        override fun downloadModel(intent: Intent, listener: ModelDownloadListener) = Unit
                        override fun close() = Unit
                    }
                }, startupTimeoutMillis = 100).also { it.start() }
            }
            check(recovered.await(2, TimeUnit.SECONDS)) { "A silent speech service leaves the microphone stuck on starting" }
            check(attempts == 2) { "Expected one recovery attempt, got $attempts" }
            runOnMainSync { input?.close() }
            val reconnected = CountDownLatch(1)
            var disconnectedAttempts = 0
            var reconnectStatus = 0
            runOnMainSync {
                input = NativeSpeechInput(targetContext, onUpdate = { status, _, finished ->
                    reconnectStatus = status
                    if (status == R.string.speech_listening || finished) reconnected.countDown()
                }, createEngine = {
                    val number = ++disconnectedAttempts
                    object : SpeechEngine {
                        private lateinit var listener: RecognitionListener
                        override fun setRecognitionListener(listener: RecognitionListener) { this.listener = listener }
                        override fun startListening(intent: Intent) {
                            if (number == 1) listener.onError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
                            else listener.onReadyForSpeech(Bundle())
                        }
                        override fun downloadModel(intent: Intent, listener: ModelDownloadListener) = Unit
                        override fun close() = Unit
                    }
                }).also { it.start() }
            }
            check(reconnected.await(2, TimeUnit.SECONDS)) { "Speech service did not reconnect" }
            check(reconnectStatus == R.string.speech_listening && disconnectedAttempts == 2) { "A disconnect during service teardown required a manual retry" }
            runOnMainSync { input?.close() }
            val stopped = CountDownLatch(1)
            var finalStatus = 0
            var silentAttempts = 0
            runOnMainSync {
                input = NativeSpeechInput(targetContext, onUpdate = { status, _, finished ->
                    if (finished) { finalStatus = status; stopped.countDown() }
                }, createEngine = { silentAttempts++; SilentEngine() }, startupTimeoutMillis = 100).also { it.start() }
            }
            check(stopped.await(2, TimeUnit.SECONDS)) { "Repeated startup failure never finished" }
            check(finalStatus == R.string.speech_start_timeout && silentAttempts == 2) { "Startup retries were not bounded" }
            var canceledAttempts = 0
            runOnMainSync {
                input = NativeSpeechInput(targetContext, onUpdate = { _, _, _ -> }, createEngine = {
                    canceledAttempts++; SilentEngine()
                }, startupTimeoutMillis = 100).also { it.start(); it.close() }
            }
            SystemClock.sleep(600)
            runOnMainSync { check(canceledAttempts == 1) { "Closing the popup restarted the microphone" } }
            repeat(10) { attempt ->
                val ready = CountDownLatch(1)
                var status = R.string.speech_starting
                var errorCode: Int? = null
                val started = SystemClock.elapsedRealtime()
                runOnMainSync {
                    input = NativeSpeechInput(targetContext, onUpdate = { next, _, finished ->
                        status = next
                        if (next == R.string.speech_listening || finished) ready.countDown()
                    }, createEngine = {
                        val engine = AndroidSpeechEngine(targetContext)
                        object : SpeechEngine by engine {
                            override fun setRecognitionListener(listener: RecognitionListener) {
                                engine.setRecognitionListener(object : RecognitionListener by listener {
                                    override fun onError(error: Int) { errorCode = error; listener.onError(error) }
                                })
                            }
                        }
                    }).also { it.start() }
                }
                check(ready.await(8, TimeUnit.SECONDS)) { "Attempt ${attempt + 1} hung on starting microphone" }
                check(status == R.string.speech_listening) { "Attempt ${attempt + 1}: ${targetContext.getString(status)} (code=$errorCode)" }
                timings.add(SystemClock.elapsedRealtime() - started)
                runOnMainSync { input?.close() }
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: silent-service and disconnect recovery, bounded retries, cancellation, and ten real microphone starts in $timings ms\n") })
        } catch (error: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: ${error.message}; ready timings $timings ms\n") })
        } finally { runOnMainSync { input?.close() } }
    }

    private class SilentEngine : SpeechEngine {
        override fun setRecognitionListener(listener: RecognitionListener) = Unit
        override fun startListening(intent: Intent) = Unit
        override fun downloadModel(intent: Intent, listener: ModelDownloadListener) = Unit
        override fun close() = Unit
    }
}

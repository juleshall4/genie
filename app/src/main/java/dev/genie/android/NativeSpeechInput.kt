package dev.genie.android

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class NativeSpeechInput internal constructor(
    private val context: Context,
    private val onUpdate: (status: Int, transcript: String?, finished: Boolean) -> Unit,
    private val onFinalTranscript: (String) -> Unit = {},
    private val onLevel: (rmsdB: Float) -> Unit = {},
    private val createEngine: () -> SpeechEngine = { AndroidSpeechEngine(context) },
    private val startupTimeoutMillis: Long = 5000
) {
    private var recognizer: SpeechEngine? = null
    private var modelDownloadAttempted = false
    private val main = Handler(Looper.getMainLooper())
    private var startupTimeout: Runnable? = null
    private var pendingRestart: Runnable? = null
    private var recoveryAttempted = false

    fun start() {
        recoveryAttempted = false
        startSession()
    }

    private fun startSession() {
        close()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onUpdate(R.string.speech_unavailable, null, true)
            return
        }

        val session = createEngine()
        var speechStarted = false
        recognizer = session
        fun recover(): Boolean {
            if (recoveryAttempted) return false
            recoveryAttempted = true
            close()
            onUpdate(R.string.speech_starting, null, false)
            pendingRestart = Runnable { startSession() }.also { main.postDelayed(it, 500) }
            return true
        }
        session.setRecognitionListener(object : RecognitionListener {
            private fun update(status: Int, transcript: String? = null, finished: Boolean = false) {
                if (recognizer !== session) return
                onUpdate(status, transcript, finished)
                if (finished) close()
            }

            override fun onReadyForSpeech(params: Bundle?) {
                if (recognizer !== session) return
                clearStartupTimeout()
                update(R.string.speech_listening)
            }

            override fun onBeginningOfSpeech() {
                if (recognizer !== session) return
                speechStarted = true
                clearStartupTimeout()
                update(R.string.speech_listening)
            }

            override fun onEndOfSpeech() {
                if (recognizer !== session) return
                clearStartupTimeout()
                update(R.string.speech_processing)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (recognizer !== session) return
                val text = transcript(partialResults)
                if (text != null) {
                    speechStarted = true
                    clearStartupTimeout()
                    update(R.string.speech_listening, text)
                }
            }

            override fun onResults(results: Bundle?) {
                if (recognizer !== session) return
                val text = transcript(results)
                update(
                    if (text == null) R.string.speech_no_match else R.string.speech_done,
                    text,
                    finished = true
                )
                if (text != null) onFinalTranscript(text)
            }

            override fun onError(error: Int) {
                if (recognizer !== session) return
                val disconnectedBeforeSpeech = error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED && !speechStarted
                if ((error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || disconnectedBeforeSpeech) && recover()) return
                if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE &&
                    Build.VERSION.SDK_INT >= 34 && !modelDownloadAttempted
                ) {
                    modelDownloadAttempted = true
                    clearStartupTimeout()
                    update(R.string.speech_downloading_model)
                    session.downloadModel(recognitionIntent(), object : ModelDownloadListener {
                        override fun onSuccess() {
                            if (recognizer === session) start()
                        }

                        override fun onProgress(completedPercent: Int) = Unit

                        override fun onScheduled() = update(R.string.speech_download_scheduled, finished = true)

                        override fun onError(error: Int) = update(R.string.speech_language_unavailable, finished = true)
                    })
                    return
                }
                val status = when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.speech_permission_needed
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.speech_no_match
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.speech_network_error
                    SpeechRecognizer.ERROR_AUDIO -> R.string.speech_audio_error
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.speech_busy
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> R.string.speech_language_unavailable
                    else -> R.string.speech_failed
                }
                update(status, finished = true)
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (recognizer === session) onLevel(rmsdB)
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        onUpdate(R.string.speech_starting, null, false)
        startupTimeout = Runnable {
            if (recognizer === session && !recover()) {
                close()
                onUpdate(R.string.speech_start_timeout, null, true)
            }
        }.also { main.postDelayed(it, startupTimeoutMillis) }
        try {
            session.startListening(recognitionIntent())
        } catch (_: SecurityException) {
            close()
            onUpdate(R.string.speech_permission_needed, null, true)
        }
    }

    fun close() {
        clearStartupTimeout()
        pendingRestart?.let { main.removeCallbacks(it) }
        pendingRestart = null
        val previous = recognizer
        recognizer = null
        previous?.close()
    }

    private fun clearStartupTimeout() {
        startupTimeout?.let { main.removeCallbacks(it) }
        startupTimeout = null
    }

    private fun transcript(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun recognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            putExtra(RecognizerIntent.EXTRA_HIDE_PARTIAL_TRAILING_PUNCTUATION, true)
        }
    }
}

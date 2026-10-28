package dev.genie.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/** Uses only TensorSpeak's installed, offline Inflect Nano voice. */
class LocalSpeechOutput(
    private val context: Context,
    private val onStatus: (Int) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change ->
            if (change < 0) stop()
        }, main).build()
    private var tts: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private var pending: String? = null
    private var request = 0
    private var lastUtterance: String? = null
    private var failureStatus = R.string.read_aloud_failed
    private val initTimeout = Runnable {
        if (!ready && !closed) {
            pending = null
            onStatus(R.string.read_aloud_failed)
            tts?.shutdown()
            tts = null
        }
    }

    init {
        if (isInstalled(context)) {
            main.postDelayed(initTimeout, 15_000)
            tts = TextToSpeech(context, { result ->
                main.post { initialized(result) }
            }, ENGINE_PACKAGE)
        } else onStatus(R.string.read_aloud_install)
    }

    private fun initialized(result: Int) {
        if (closed || tts == null) return
        main.removeCallbacks(initTimeout)
        val engine = tts ?: return
        if (result != TextToSpeech.SUCCESS) {
            pending = null
            engine.shutdown()
            tts = null
            onStatus(R.string.read_aloud_failed)
            return
        }
        val nano = engine.voices?.firstOrNull { it.name == "nano" && !it.isNetworkConnectionRequired }
        if (nano == null || engine.setVoice(nano) != TextToSpeech.SUCCESS) {
            pending = null
            failureStatus = R.string.read_aloud_download
            engine.shutdown()
            tts = null
            onStatus(R.string.read_aloud_download)
            return
        }
        engine.setAudioAttributes(attributes)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = update(utteranceId) {
                onStatus(R.string.read_aloud_speaking)
            }

            override fun onDone(utteranceId: String?) = update(utteranceId) {
                if (utteranceId == lastUtterance) {
                    audio.abandonAudioFocusRequest(focus)
                    lastUtterance = null
                    onStatus(R.string.read_aloud_ready)
                }
            }

            @Deprecated("Android callback")
            override fun onError(utteranceId: String?) = update(utteranceId) {
                stop()
                onStatus(R.string.read_aloud_failed)
            }
        })
        ready = true
        val waiting = pending
        pending = null
        if (waiting != null) speak(waiting)
    }

    fun speak(text: String) {
        if (closed || text.isBlank()) return
        stop()
        if (!ready) {
            if (tts != null) {
                pending = text
                onStatus(R.string.read_aloud_preparing)
            } else onStatus(if (isInstalled(context)) failureStatus else R.string.read_aloud_install)
            return
        }
        val chunks = SpokenText.chunks(text, TextToSpeech.getMaxSpeechInputLength())
        if (chunks.isEmpty()) return
        // Android can deny focus to an overlay above another app; speech still works without ducking.
        audio.requestAudioFocus(focus)
        onStatus(R.string.read_aloud_preparing)
        lastUtterance = "$request:${chunks.lastIndex}"
        for ((index, chunk) in chunks.withIndex()) {
            if (tts?.speak(chunk, TextToSpeech.QUEUE_ADD, null, "$request:$index") != TextToSpeech.SUCCESS) {
                stop()
                onStatus(R.string.read_aloud_failed)
                return
            }
        }
    }

    fun stop() {
        request++
        pending = null
        lastUtterance = null
        tts?.stop()
        audio.abandonAudioFocusRequest(focus)
    }

    fun close() {
        closed = true
        main.removeCallbacks(initTimeout)
        stop()
        tts?.shutdown()
        tts = null
    }

    private fun update(utteranceId: String?, action: () -> Unit) {
        main.post {
            if (!closed && utteranceId?.substringBefore(':') == request.toString()) action()
        }
    }

    companion object {
        const val ENGINE_PACKAGE = "com.github.aljge.tensorspeak"

        fun isInstalled(context: Context): Boolean = try {
            context.packageManager.getPackageInfo(ENGINE_PACKAGE, 0)
            true
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
    }
}

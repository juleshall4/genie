package dev.genie.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import java.io.File
import java.util.concurrent.Executors

class ChirpSpeechOutput(private val context: Context, private val onStatus: (Int) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val settings = ChirpSettings(context)
    private val api = ChirpApi()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change -> if (change < 0) stop() }, main).build()
    private var player: MediaPlayer? = null
    private var audioFile: File? = null
    private var call: Call? = null
    private var generation = 0
    private var closed = false

    fun speak(text: String) {
        if (closed) return
        stop()
        if (!settings.enabled) { onStatus(R.string.read_aloud_disabled); return }
        val key = try { settings.apiKey } catch (_: Exception) { "" }
        if (key.isBlank()) { onStatus(R.string.read_aloud_setup_needed); return }
        val chunks = SpokenText.chunks(text, 900)
        if (chunks.isEmpty()) return
        val current = generation
        val voice = settings.voice
        fun next(index: Int) {
            if (closed || current != generation) return
            if (index == chunks.size) {
                audio.abandonAudioFocusRequest(focus)
                onStatus(R.string.read_aloud_ready)
                return
            }
            val chunk = chunks[index]
            val request = api.request(key, voice, chunk)
            if (!settings.reserve(chunk.codePointCount(0, chunk.length))) {
                stop()
                onStatus(R.string.read_aloud_limit)
                return
            }
            call = request
            onStatus(R.string.read_aloud_preparing)
            worker.execute {
                try {
                    val bytes = api.audio(request)
                    main.post {
                        if (!closed && current == generation) {
                            call = null
                            try {
                                releasePlayer()
                                val file = File.createTempFile("chirp-", ".mp3", context.cacheDir)
                                audioFile = file
                                file.writeBytes(bytes)
                                player = MediaPlayer().apply {
                                    setAudioAttributes(attributes)
                                    setDataSource(file.absolutePath)
                                    setOnPreparedListener {
                                        if (!closed && current == generation) {
                                            audio.requestAudioFocus(focus)
                                            start()
                                            onStatus(R.string.read_aloud_speaking)
                                        }
                                    }
                                    setOnCompletionListener { completed ->
                                        if (!closed && current == generation && player === completed) {
                                            releasePlayer()
                                            next(index + 1)
                                        }
                                    }
                                    setOnErrorListener { failed, _, _ ->
                                        if (!closed && current == generation && player === failed) {
                                            stop()
                                            onStatus(R.string.read_aloud_failed)
                                        }
                                        true
                                    }
                                    prepareAsync()
                                }
                            } catch (_: Exception) { stop(); onStatus(R.string.read_aloud_failed) }
                        }
                    }
                } catch (error: Exception) {
                    main.post {
                        if (!closed && current == generation) {
                            stop()
                            onStatus(when ((error as? ChirpFailure)?.status) {
                                400, 401, 403 -> R.string.read_aloud_credentials_failed
                                429 -> R.string.read_aloud_quota_failed
                                else -> R.string.read_aloud_failed
                            })
                        }
                    }
                }
            }
        }
        next(0)
    }

    fun stop() {
        generation++
        call?.cancel()
        call = null
        releasePlayer()
        audio.abandonAudioFocusRequest(focus)
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        audioFile?.delete()
        audioFile = null
    }

    fun close() {
        closed = true
        stop()
        worker.shutdownNow()
    }
}

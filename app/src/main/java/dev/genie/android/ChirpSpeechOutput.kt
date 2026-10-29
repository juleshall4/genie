package dev.genie.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import java.io.File
import java.util.concurrent.Executors

class ChirpSpeechOutput(
    private val context: Context,
    private val api: ChirpApi = ChirpApi(),
    private val onStatus: (Int) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val settings = ChirpSettings(context)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change -> if (change < 0) stop() }, main).build()
    private val pending = ArrayDeque<String>()
    private val ready = ArrayDeque<ByteArray>()
    private var buffer = SpeechTextBuffer()
    private var player: MediaPlayer? = null
    private var audioFile: File? = null
    private var call: Call? = null
    @Volatile private var generation = 0
    private var closed = false
    private var active = false
    private var finished = false
    private var fetching = false
    private var voice = settings.voice
    private var speakingRate = settings.speakingRate
    private var apiKey = ""
    private val ringerChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (active && isSilent()) { stop(); onStatus(R.string.read_aloud_silent) }
        }
    }

    init {
        val filter = IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(ringerChanged, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(ringerChanged, filter)
    }

    private fun isSilent() = audio.ringerMode != AudioManager.RINGER_MODE_NORMAL

    fun beginAnswer() {
        if (closed) return
        stop()
        // Silent mode gates credentials, character reservations, and network work.
        if (isSilent()) { onStatus(R.string.read_aloud_silent); return }
        if (!settings.enabled) { onStatus(R.string.read_aloud_disabled); return }
        apiKey = try { settings.apiKey } catch (_: Exception) { "" }
        if (apiKey.isBlank()) { onStatus(R.string.read_aloud_setup_needed); return }
        voice = settings.voice
        speakingRate = settings.speakingRate
        active = true
    }

    fun updateAnswer(text: String, done: Boolean = false) {
        if (!active || closed) return
        if (isSilent() || !settings.enabled) { stop(); return }
        pending.addAll(buffer.update(text, done))
        finished = done
        pump()
    }

    fun speak(text: String) {
        beginAnswer()
        updateAnswer(text, true)
    }

    private fun pump() {
        if (!active || closed) return
        if (isSilent() || !settings.enabled) { stop(); return }
        if (player == null && ready.isNotEmpty()) play(ready.removeFirst())
        if (!fetching && pending.isNotEmpty() && ready.isEmpty()) {
            val chunk = pending.removeFirst()
            val current = generation
            val request = api.request(apiKey, voice, chunk, speakingRate)
            call = request
            fetching = true
            if (player == null) onStatus(R.string.read_aloud_preparing)
            worker.execute {
                try {
                    // Check again immediately before submission; a queued worker must not send after mute/stop.
                    if (current != generation || isSilent() || !settings.enabled) {
                        request.cancel()
                        main.post { if (current == generation) stop() }
                        return@execute
                    }
                    if (!settings.reserve(chunk.codePointCount(0, chunk.length))) {
                        main.post { if (current == generation) { stop(); onStatus(R.string.read_aloud_limit) } }
                        return@execute
                    }
                    val bytes = api.audio(request)
                    main.post {
                        if (current == generation && active && !closed) {
                            fetching = false
                            call = null
                            ready.addLast(bytes)
                            pump()
                        }
                    }
                } catch (error: Exception) {
                    main.post {
                        if (current == generation && active && !closed) {
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
        if (finished && pending.isEmpty() && ready.isEmpty() && !fetching && player == null) {
            active = false
            audio.abandonAudioFocusRequest(focus)
            onStatus(R.string.read_aloud_ready)
        }
    }

    private fun play(bytes: ByteArray) {
        val current = generation
        try {
            val file = File.createTempFile("chirp-", ".mp3", context.cacheDir)
            audioFile = file
            file.writeBytes(bytes)
            player = MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(file.absolutePath)
                setOnPreparedListener {
                    if (current == generation && active && !closed) {
                        if (isSilent() || !settings.enabled) { stop(); return@setOnPreparedListener }
                        audio.requestAudioFocus(focus)
                        start()
                        onStatus(R.string.read_aloud_speaking)
                    }
                }
                setOnCompletionListener { completed ->
                    if (current == generation && player === completed && !closed) {
                        releasePlayer()
                        pump()
                    }
                }
                setOnErrorListener { failed, _, _ ->
                    if (current == generation && player === failed && !closed) {
                        stop(); onStatus(R.string.read_aloud_failed)
                    }
                    true
                }
                prepareAsync()
            }
        } catch (_: Exception) { stop(); onStatus(R.string.read_aloud_failed) }
    }

    fun stop() {
        generation++
        active = false
        fetching = false
        finished = false
        pending.clear()
        ready.clear()
        buffer = SpeechTextBuffer()
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
        if (closed) return
        closed = true
        stop()
        context.unregisterReceiver(ringerChanged)
        worker.shutdownNow()
    }
}

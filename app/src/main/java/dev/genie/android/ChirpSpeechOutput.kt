package dev.genie.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.grpc.Status
import java.util.concurrent.Executors

class ChirpSpeechOutput(
    private val context: Context,
    private val api: ChirpStreamFactory = ChirpStreamingApi(),
    private val onStatus: (Int) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val outgoing = Executors.newSingleThreadExecutor()
    private val settings = ChirpSettings(context)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change -> if (change < 0) stop() }, main).build()
    private var buffer = StreamingTextBuffer()
    @Volatile private var session: Session? = null
    private var closed = false
    private val ringerChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (session != null && isSilent()) { stop(); onStatus(R.string.read_aloud_silent) }
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
        if (isSilent()) { onStatus(R.string.read_aloud_silent); return }
        if (!settings.enabled) { onStatus(R.string.read_aloud_disabled); return }
        val key = try { settings.apiKey } catch (_: Exception) { "" }
        if (key.isBlank()) { onStatus(R.string.read_aloud_setup_needed); return }
        if (settings.usage().characters >= ChirpSettings.MONTHLY_LIMIT) { onStatus(R.string.read_aloud_limit); return }
        val next = Session()
        session = next
        val voice = settings.voice
        val rate = settings.speakingRate
        // Connect and send config during answer generation, before the first words arrive.
        outgoing.execute {
            if (!next.allowed()) return@execute
            try {
                next.attach(api.open(key, voice, rate, next))
            } catch (_: Exception) { next.fail(R.string.read_aloud_failed) }
        }
    }

    fun updateAnswer(text: String, done: Boolean = false) {
        val current = session ?: return
        if (!current.allowed()) { stop(); return }
        for (chunk in buffer.update(text, done)) {
            val submitted = chunk + " "
            outgoing.execute {
                if (!current.allowed()) return@execute
                try {
                    if (!settings.reserve(submitted.codePointCount(0, submitted.length))) {
                        current.fail(R.string.read_aloud_limit)
                        return@execute
                    }
                    current.send(submitted)
                } catch (_: Exception) { current.fail(R.string.read_aloud_failed) }
            }
        }
        if (done) outgoing.execute {
            if (current.allowed()) try { current.finishInput() } catch (_: Exception) { current.fail(R.string.read_aloud_failed) }
        }
    }

    fun speak(text: String) { beginAnswer(); updateAnswer(text, true) }

    private inner class Session : ChirpStreamFactory.Listener {
        private val lock = Any()
        @Volatile private var cancelled = false
        private var stream: ChirpStream? = null
        private val player = StreamingPcmPlayer(attributes, ::allowed) {
            main.post {
                if (allowed()) {
                    audio.requestAudioFocus(focus)
                    onStatus(R.string.read_aloud_speaking)
                }
            }
        }

        fun allowed() = !cancelled && session === this && !isSilent() && settings.enabled

        fun attach(opened: ChirpStream) = synchronized(lock) {
            if (!allowed()) opened.cancel() else stream = opened
        }

        fun send(text: String) = synchronized(lock) {
            if (allowed()) {
                // Include the delimiter in the reservation: Google counts all submitted characters.
                stream?.send(text)
            }
        }

        fun finishInput() = synchronized(lock) { if (allowed()) stream?.finish() }

        override fun onAudio(pcm: ByteArray) {
            if (!allowed()) return
            try { player.write(pcm) } catch (_: Exception) { fail(R.string.read_aloud_failed) }
        }

        override fun onComplete() {
            if (!allowed()) return
            try {
                player.finish()
                main.post {
                    if (session === this && allowed()) {
                        stop()
                        onStatus(R.string.read_aloud_ready)
                    }
                }
            } catch (_: Exception) { fail(R.string.read_aloud_failed) }
        }

        override fun onError(code: Status.Code) {
            fail(when (code) {
                Status.Code.INVALID_ARGUMENT, Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED -> R.string.read_aloud_credentials_failed
                Status.Code.RESOURCE_EXHAUSTED -> R.string.read_aloud_quota_failed
                else -> R.string.read_aloud_failed
            })
        }

        fun fail(message: Int) {
            cancel()
            main.post {
                if (session === this) { stop(); onStatus(message) }
            }
        }

        fun cancel() {
            synchronized(lock) {
                if (cancelled) return
                cancelled = true
                stream?.cancel()
                stream = null
            }
            player.close()
        }
    }

    fun stop() {
        val current = session
        session = null
        current?.cancel()
        buffer = StreamingTextBuffer()
        audio.abandonAudioFocusRequest(focus)
    }

    fun close() {
        if (closed) return
        closed = true
        stop()
        context.unregisterReceiver(ringerChanged)
        outgoing.shutdownNow()
    }
}

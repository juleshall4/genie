package dev.genie.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Plays packaged samples without credentials, network requests, or usage reservations. */
class VoicePreviewOutput(private val context: Context, private val onStatus: (Int) -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change -> if (change < 0) stop() },
            Handler(Looper.getMainLooper())).build()
    private var player: MediaPlayer? = null
    private var closed = false
    private val ringerChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (player != null && isSilent()) { stop(); onStatus(R.string.read_aloud_silent) }
        }
    }

    init {
        val filter = IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(ringerChanged, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(ringerChanged, filter)
    }

    private fun isSilent() = audio.ringerMode != AudioManager.RINGER_MODE_NORMAL

    fun play(voice: String, speed: Float) {
        if (closed) return
        require(voice in ChirpSettings.VOICES)
        require(speed.isFinite() && speed in 0.25f..2f)
        stop()
        if (isSilent()) { onStatus(R.string.read_aloud_silent); return }
        onStatus(R.string.voice_preview_preparing)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(attributes)
                context.assets.openFd("voice_samples/$voice.ogg").use {
                    setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
                setOnPreparedListener { prepared ->
                    if (!closed && player === prepared) {
                        if (isSilent()) { stop(); onStatus(R.string.read_aloud_silent) }
                        else try {
                            audio.requestAudioFocus(focus)
                            playbackParams = PlaybackParams().setPitch(1f).setSpeed(speed)
                            start()
                            onStatus(R.string.voice_preview_playing)
                        } catch (_: Exception) { stop(); onStatus(R.string.voice_preview_failed) }
                    }
                }
                setOnCompletionListener { completed ->
                    if (!closed && player === completed) {
                        stop()
                        onStatus(R.string.voice_preview_ready)
                    }
                }
                setOnErrorListener { failed, _, _ ->
                    if (!closed && player === failed) { stop(); onStatus(R.string.voice_preview_failed) }
                    true
                }
                prepareAsync()
            }
        } catch (_: Exception) { stop(); onStatus(R.string.voice_preview_failed) }
    }

    fun stop() {
        player?.release()
        player = null
        audio.abandonAudioFocusRequest(focus)
    }

    fun close() {
        if (closed) return
        closed = true
        stop()
        context.unregisterReceiver(ringerChanged)
    }
}

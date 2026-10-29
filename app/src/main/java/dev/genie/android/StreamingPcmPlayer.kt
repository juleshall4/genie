package dev.genie.android

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock

/** Writes headerless 24 kHz mono PCM directly to Android's streaming audio buffer. */
class StreamingPcmPlayer(
    private val attributes: AudioAttributes,
    private val allowed: () -> Boolean,
    private val onStarted: () -> Unit
) {
    private val lock = Any()
    @Volatile private var stopped = false
    @Volatile private var track: AudioTrack? = null
    private var framesWritten = 0L
    private var trailingByte: Byte? = null
    private var started = false

    fun write(bytes: ByteArray) {
        if (stopped || !allowed()) return
        val joined = trailingByte?.let { byteArrayOf(it) + bytes } ?: bytes
        val size = joined.size - joined.size % 2
        trailingByte = if (joined.size % 2 == 1) joined.last() else null
        if (size == 0) return
        val output = synchronized(lock) {
            if (stopped || !allowed()) return
            track ?: AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(24_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(4800, AudioTrack.getMinBufferSize(24_000,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
                .build().also {
                    check(it.state == AudioTrack.STATE_INITIALIZED)
                    track = it
                    it.play()
                }
        }
        var offset = 0
        while (offset < size && !stopped && allowed()) {
            val written = output.write(joined, offset, minOf(2400, size - offset), AudioTrack.WRITE_BLOCKING)
            if (stopped) return
            check(written > 0) { "Audio output stopped" }
            offset += written
            framesWritten += written / 2
            if (!started) { started = true; onStarted() }
        }
    }

    /** The server can finish before the speaker drains its last buffered samples. */
    fun finish() {
        while (!stopped && allowed()) {
            val output = track ?: return
            val played = output.playbackHeadPosition.toLong() and 0xffff_ffffL
            if (played >= framesWritten) return
            SystemClock.sleep(5)
        }
    }

    fun close() {
        synchronized(lock) {
            stopped = true
            track?.let {
                try { it.pause(); it.flush() } finally { it.release() }
            }
            track = null
        }
    }
}

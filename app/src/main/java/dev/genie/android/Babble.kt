package dev.genie.android

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.random.Random

/**
 * Puff's voice: a short, pitched blip per syllable, a little higher on vowels, like Animal Crossing.
 * The blips are made once as small WAV files, so SoundPool can play them with almost no delay.
 */
class Babble(context: Context) {
    private val pool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ).build()
    private val consonants = IntArray(PITCHES)
    private val vowels = IntArray(PITCHES)

    init {
        val folder = File(context.cacheDir, "babble").apply { mkdirs() }
        for (i in 0 until PITCHES) {
            consonants[i] = pool.load(blip(folder, CONSONANT_HZ + i * STEP_HZ).path, 1)
            vowels[i] = pool.load(blip(folder, VOWEL_HZ + i * STEP_HZ).path, 1)
        }
    }

    /** Plays the blip for [letter]. The same letter always lands on the same pitch, with a little wobble. */
    fun say(letter: Char) {
        val c = letter.lowercaseChar()
        val sounds = if (c in "aeiouy") vowels else consonants
        pool.play(sounds[c.code * 37 % PITCHES], 1f, 1f, 0, 0, 1 + (Random.nextFloat() - 0.5f) * 0.08f)
    }

    fun release() = pool.release()

    private companion object {
        const val PITCHES = 7
        const val CONSONANT_HZ = 560
        const val VOWEL_HZ = 700
        const val STEP_HZ = 34
        const val RATE = 44100
        const val LENGTH = 0.08f

        // A triangle wave that slides down from 112% to 90% of its pitch, with a fast attack and a quick fade.
        fun blip(folder: File, hz: Int): File {
            val file = File(folder, "blip_$hz.wav")
            if (file.exists()) return file
            val samples = (RATE * LENGTH).toInt()
            val data = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            data.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVE".toByteArray())
            data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2)
                .putShort(2).putShort(16)
            data.put("data".toByteArray()).putInt(samples * 2)
            var phase = 0.0
            for (i in 0 until samples) {
                val t = i.toFloat() / RATE
                phase += hz * 1.12 * (0.9 / 1.12).pow((t / 0.06).coerceAtMost(1.0)) / RATE
                val wave = 4 * abs(phase - floor(phase) - 0.5) - 1
                val envelope = when {
                    t < 0.008f -> 1600.0.pow(t / 0.008 - 1)
                    t < 0.07f -> (0.0001 / 0.16).pow((t - 0.008) / 0.062)
                    else -> 0.0
                }
                data.putShort((wave * envelope.coerceAtMost(1.0) * 0.45 * Short.MAX_VALUE).toInt().toShort())
            }
            file.writeBytes(data.array())
            return file
        }
    }
}

package dev.genie.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ChirpSettings(context: Context) {
    private val preferences = context.getSharedPreferences("chirp", Context.MODE_PRIVATE)
    private val encryptionKey: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("genie_chirp", null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).apply {
            init(KeyGenParameterSpec.Builder("genie_chirp", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    var apiKey: String
        get() {
            val value = preferences.getString("key", null) ?: return ""
            val bytes = Base64.getDecoder().decode(value)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
        set(value) {
            if (value.isBlank()) {
                check(preferences.edit().remove("key").commit())
            } else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
                val encrypted = cipher.iv + cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
                check(preferences.edit().putString("key", Base64.getEncoder().encodeToString(encrypted)).commit())
            }
        }

    init {
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            val pending = java.io.File(context.noBackupFilesDir, "chirp-api-key.pending")
            if (pending.exists()) {
                apiKey = pending.readText().trim()
                check(pending.delete())
            }
        }
    }

    var enabled: Boolean
        get() = preferences.getBoolean("enabled", true)
        set(value) { preferences.edit().putBoolean("enabled", value).apply() }

    var voice: String
        get() = preferences.getString("voice", VOICES.first()) ?: VOICES.first()
        set(value) { require(value in VOICES); preferences.edit().putString("voice", value).apply() }

    var speakingRate: Float
        get() = preferences.getFloat("speaking_rate", 1f)
        set(value) {
            require(value.isFinite() && value in 0.25f..2f)
            preferences.edit().putFloat("speaking_rate", value).apply()
        }

    // A process-wide lock covers both the overlay and the settings preview.
    fun usage(): Usage = synchronized(usageLock) {
        val month = YearMonth.now(ZoneOffset.UTC).toString()
        Usage(month, if (preferences.getString("month", "") == month) preferences.getLong("characters", 0) else 0)
    }

    fun reserve(characters: Int): Boolean = synchronized(usageLock) {
        require(characters > 0)
        val current = usage()
        if (current.characters + characters > MONTHLY_LIMIT) return false
        // Count before sending. Failed or interrupted requests may still be billed by Google.
        check(preferences.edit().putString("month", current.month)
            .putLong("characters", current.characters + characters).commit())
        true
    }

    data class Usage(val month: String, val characters: Long)

    companion object {
        const val MONTHLY_LIMIT = 900_000L
        val VOICES = listOf(
            "en-GB-Chirp3-HD-Aoede",
            "en-GB-Chirp3-HD-Achernar",
            "en-GB-Chirp3-HD-Achird",
            "en-GB-Chirp3-HD-Algenib",
            "en-GB-Chirp3-HD-Algieba",
            "en-GB-Chirp3-HD-Alnilam",
            "en-GB-Chirp3-HD-Autonoe",
            "en-GB-Chirp3-HD-Callirrhoe",
            "en-GB-Chirp3-HD-Charon",
            "en-GB-Chirp3-HD-Despina",
            "en-GB-Chirp3-HD-Enceladus",
            "en-GB-Chirp3-HD-Erinome",
            "en-GB-Chirp3-HD-Fenrir",
            "en-GB-Chirp3-HD-Gacrux",
            "en-GB-Chirp3-HD-Iapetus",
            "en-GB-Chirp3-HD-Kore",
            "en-GB-Chirp3-HD-Laomedeia",
            "en-GB-Chirp3-HD-Leda",
            "en-GB-Chirp3-HD-Orus",
            "en-GB-Chirp3-HD-Puck",
            "en-GB-Chirp3-HD-Pulcherrima",
            "en-GB-Chirp3-HD-Rasalgethi",
            "en-GB-Chirp3-HD-Sadachbia",
            "en-GB-Chirp3-HD-Sadaltager",
            "en-GB-Chirp3-HD-Schedar",
            "en-GB-Chirp3-HD-Sulafat",
            "en-GB-Chirp3-HD-Umbriel",
            "en-GB-Chirp3-HD-Vindemiatrix",
            "en-GB-Chirp3-HD-Zephyr",
            "en-GB-Chirp3-HD-Zubenelgenubi",
            "en-US-Chirp3-HD-Aoede",
            "en-US-Chirp3-HD-Charon",
            "en-US-Chirp3-HD-Kore",
            "en-US-Chirp3-HD-Puck"
        )
        val MALE_VOICES = setOf(
            "en-US-Chirp3-HD-Charon",
            "en-US-Chirp3-HD-Puck",
            "en-GB-Chirp3-HD-Achird",
            "en-GB-Chirp3-HD-Algenib",
            "en-GB-Chirp3-HD-Algieba",
            "en-GB-Chirp3-HD-Alnilam",
            "en-GB-Chirp3-HD-Charon",
            "en-GB-Chirp3-HD-Enceladus",
            "en-GB-Chirp3-HD-Fenrir",
            "en-GB-Chirp3-HD-Iapetus",
            "en-GB-Chirp3-HD-Orus",
            "en-GB-Chirp3-HD-Puck",
            "en-GB-Chirp3-HD-Rasalgethi",
            "en-GB-Chirp3-HD-Sadachbia",
            "en-GB-Chirp3-HD-Sadaltager",
            "en-GB-Chirp3-HD-Schedar",
            "en-GB-Chirp3-HD-Umbriel",
            "en-GB-Chirp3-HD-Zubenelgenubi"
        )
        val SPEAKING_RATES = (1..8).map { it * 0.25f }

        private val usageLock = Any()
    }
}

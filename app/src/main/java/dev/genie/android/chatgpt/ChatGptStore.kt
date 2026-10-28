package dev.genie.android.chatgpt

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ChatGptAccount(
    val clientId: String,
    val subject: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val idToken: String,
    val expiresAt: Long,
    val scopes: Set<String>
) {
    val connected: Boolean get() = accessToken.isNotEmpty() &&
        "chatgpt.tokens.use.direct" in scopes && "resource.invoke" in scopes

    fun json() = JSONObject().put("client_id", clientId).put("subject", subject).put("email", email)
        .put("access_token", accessToken).put("refresh_token", refreshToken).put("id_token", idToken)
        .put("expires_at", expiresAt).put("scopes", JSONArray(scopes.toList()))
}

class ChatGptStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "chatgpt-credentials.enc"))
    private val preferences = context.getSharedPreferences("chatgpt", Context.MODE_PRIVATE)
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("genie_chatgpt", null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).apply {
            init(KeyGenParameterSpec.Builder("genie_chatgpt", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    val hostId: String
        @Synchronized get() = preferences.getString("host_id", null) ?: "urn:uuid:${UUID.randomUUID()}".also {
            check(preferences.edit().putString("host_id", it).commit())
        }

    @Synchronized fun accounts(): List<ChatGptAccount> {
        val array = read().optJSONArray("accounts") ?: return emptyList()
        return (0 until array.length()).map { index ->
            val json = array.getJSONObject(index)
            val scopes = json.getJSONArray("scopes")
            ChatGptAccount(json.getString("client_id"), json.getString("subject"), json.optString("email"),
                json.optString("access_token"), json.optString("refresh_token"), json.optString("id_token"),
                json.optLong("expires_at"), (0 until scopes.length()).map { scopes.getString(it) }.toSet())
        }
    }

    @Synchronized fun active(): ChatGptAccount? {
        val id = read().optString("active")
        return accounts().firstOrNull { it.clientId == id }
    }

    @Synchronized fun save(account: ChatGptAccount, activate: Boolean = true) {
        val root = read()
        val accounts = accounts().filterNot { it.clientId == account.clientId } + account
        root.put("accounts", JSONArray(accounts.map { it.json() }))
        if (activate) root.put("active", account.clientId)
        write(root)
    }

    @Synchronized fun select(clientId: String) {
        require(accounts().any { it.clientId == clientId })
        write(read().put("active", clientId))
    }

    fun firstWelcome(): Boolean {
        if (preferences.getBoolean("welcomed", false)) return false
        preferences.edit().putBoolean("welcomed", true).apply()
        return true
    }

    private fun read(): JSONObject {
        if (!file.baseFile.exists()) return JSONObject()
        val bytes = file.readFully()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }

    private fun write(root: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val bytes = cipher.iv + cipher.doFinal(root.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}

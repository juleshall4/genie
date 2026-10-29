package dev.genie.android

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

class ChirpApi(
    private val http: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .callTimeout(45, TimeUnit.SECONDS).build(),
    private val endpoint: String = "https://texttospeech.googleapis.com/v1/text:synthesize"
) {
    fun request(apiKey: String, voice: String, text: String): Call {
        require(text.toByteArray(Charsets.UTF_8).size <= 5000)
        val body = JSONObject().put("input", JSONObject().put("text", text))
            .put("voice", JSONObject().put("languageCode", "en-GB").put("name", voice))
            .put("audioConfig", JSONObject().put("audioEncoding", "MP3"))
        return http.newCall(Request.Builder().url(endpoint).header("X-Goog-Api-Key", apiKey)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build())
    }

    fun audio(call: Call): ByteArray = call.execute().use { response ->
        if (!response.isSuccessful) throw ChirpFailure(response.code)
        val content = JSONObject(response.body?.string() ?: throw IOException("Empty speech response"))
            .getString("audioContent")
        Base64.getDecoder().decode(content).also { if (it.isEmpty()) throw IOException("Empty speech audio") }
    }
}

class ChirpFailure(val status: Int) : IOException("Speech request failed ($status)")

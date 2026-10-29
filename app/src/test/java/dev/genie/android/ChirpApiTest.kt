package dev.genie.android

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Base64

class ChirpApiTest {
    @Test fun returnsAudioAndSendsPlainTextWithSelectedVoice() {
        MockWebServer().use { server ->
            val audio = byteArrayOf(73, 68, 51, 1, 2, 3)
            server.enqueue(MockResponse().setBody(JSONObject().put("audioContent", Base64.getEncoder().encodeToString(audio)).toString()))
            val api = ChirpApi(endpoint = server.url("/v1/text:synthesize").toString())
            assertArrayEquals(audio, api.audio(api.request("test-key", "en-GB-Chirp3-HD-Charon", "Hello, Genie.")))
            val request = server.takeRequest()
            assertEquals("test-key", request.getHeader("X-Goog-Api-Key"))
            assertFalse(request.path!!.contains("test-key"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("Hello, Genie.", body.getJSONObject("input").getString("text"))
            assertEquals("en-GB-Chirp3-HD-Charon", body.getJSONObject("voice").getString("name"))
            assertEquals("MP3", body.getJSONObject("audioConfig").getString("audioEncoding"))
        }
    }

    @Test fun reportsQuotaFailureWithoutRetryingOrExposingProviderBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setBody("private provider detail"))
            val api = ChirpApi(endpoint = server.url("/speech").toString())
            try {
                api.audio(api.request("secret-key", ChirpSettings.VOICES.first(), "Hello."))
                fail("Expected a quota failure")
            } catch (error: ChirpFailure) {
                assertEquals(429, error.status)
                assertFalse(error.message!!.contains("private"))
                assertFalse(error.message!!.contains("secret-key"))
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun cancelledSpeechIsNotSubmitted() {
        MockWebServer().use { server ->
            val api = ChirpApi(endpoint = server.url("/speech").toString())
            val call = api.request("key", ChirpSettings.VOICES.first(), "Hello.")
            call.cancel()
            try { api.audio(call); fail("Expected cancellation") } catch (_: IOException) { }
            assertEquals(0, server.requestCount)
        }
    }
}

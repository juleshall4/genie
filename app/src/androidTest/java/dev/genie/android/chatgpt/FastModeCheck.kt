package dev.genie.android.chatgpt

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Restores Low and probes one Fast request without enabling it for normal app requests. */
class FastModeCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        if (arguments?.getString("live_chatgpt") == "true") start()
        else finish(Activity.RESULT_CANCELED, Bundle())
    }

    override fun onStart() {
        val results = mutableListOf<String>()
        var socket: WebSocket? = null
        try {
            val client = ChatGptClient.get(targetContext)
            val loaded = CountDownLatch(1)
            var selection: ChatGptModelSelection? = null
            client.models { catalog, _ ->
                if (catalog != null) {
                    val account = requireNotNull(client.store.active())
                    val model = catalog.selectModel(client.settings.model(account.clientId))
                    if (model.reasoningLevels.any { it.effort == "low" }) {
                        client.settings.setReasoning(account.clientId, model.slug, "low")
                        selection = model.selectReasoning("low")
                    }
                }
                loaded.countDown()
            }
            check(loaded.await(30, TimeUnit.SECONDS)) { "Model lookup timed out" }
            val selected = requireNotNull(selection) { "No model with Low reasoning available" }
            results.add("Restored ${selected.model.slug} / low")
            val account = requireNotNull(client.store.active())
            val opened = CountDownLatch(1)
            val failure = AtomicReference<String?>(null)
            val done = AtomicReference(CountDownLatch(1))
            var started = 0L
            var first = -1L
            var servedTier = "unreported"
            val http = OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false).build()
            socket = http.newWebSocket(Request.Builder().url("https://api.openai.com/v1/responses")
                .header("Authorization", "Bearer ${account.accessToken}").build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { opened.countDown() }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val event = JSONObject(text)
                    when (event.optString("type")) {
                        "response.output_text.delta" -> if (first < 0 && event.optString("delta").isNotBlank()) {
                            first = SystemClock.elapsedRealtime() - started
                        }
                        "response.completed" -> {
                            servedTier = event.getJSONObject("response").optString("service_tier", "unreported")
                            done.get().countDown()
                        }
                        "error", "response.failed", "response.incomplete" -> {
                            val error = event.optJSONObject("response")?.optJSONObject("error")
                                ?: event.optJSONObject("error") ?: event
                            failure.set("${event.optString("type")}: ${error.optString("message")}".take(240))
                            done.get().countDown()
                        }
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failure.set("Connection failed: HTTP ${response?.code}")
                    opened.countDown()
                    done.get().countDown()
                }
            })
            check(opened.await(15, TimeUnit.SECONDS)) { "Socket opening timed out" }
            check(failure.get() == null) { failure.get().orEmpty() }
            for (tier in listOf("priority")) {
                val latch = CountDownLatch(1)
                done.set(latch)
                first = -1
                val body = selected.requestBody(listOf(ChatTurn("user", "How tall is an adult chicken? Answer in one short sentence.")))
                    .put("type", "response.create").put("service_tier", tier)
                body.remove("stream")
                started = SystemClock.elapsedRealtime()
                check(socket.send(body.toString()))
                check(latch.await(45, TimeUnit.SECONDS)) { "Response timed out" }
                check(failure.get() == null) { failure.get().orEmpty() }
                results.add("Requested $tier; served $servedTier; first text $first ms; complete ${SystemClock.elapsedRealtime() - started} ms")
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", results.joinToString("\n")) })
        } catch (error: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", (results + "FAIL: ${error.message}").joinToString("\n")) })
        } finally { socket?.cancel() }
    }
}

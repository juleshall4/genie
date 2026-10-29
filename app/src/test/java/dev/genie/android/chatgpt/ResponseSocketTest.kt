package dev.genie.android.chatgpt

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ResponseSocketTest {
    @Test
    fun deliversTextBeforeCompletionAndReusesConnectionForNextTurn() {
        val server = MockWebServer()
        val peer = java.util.concurrent.atomic.AtomicReference<WebSocket>()
        val firstText = CountDownLatch(1)
        val client = OkHttpClient()
        val worker = Executors.newSingleThreadExecutor()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { peer.set(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val request = JSONObject(text)
                assertEquals("response.create", request.getString("type"))
                assertFalse(request.has("stream"))
                assertFalse(request.getBoolean("store"))
                webSocket.send("""{"type":"response.output_text.delta","delta":"Hello"}""")
            }
        }))
        val socket = ResponseSocket(client, Request.Builder().url(server.url("/v1/responses")).build())
        try {
            val body = JSONObject().put("model", "example").put("store", false).put("stream", true)
            val result = worker.submit<Boolean> { socket.answer(body) { firstText.countDown() } }
            assertTrue("Text must arrive while completion is withheld", firstText.await(3, TimeUnit.SECONDS))
            assertFalse(result.isDone)
            peer.get().send("""{"type":"response.completed","response":{"status":"completed"}}""")
            assertTrue(result.get(3, TimeUnit.SECONDS))
            val secondText = CountDownLatch(1)
            val second = worker.submit<Boolean> { socket.answer(body) { secondText.countDown() } }
            assertTrue(secondText.await(3, TimeUnit.SECONDS))
            peer.get().send("""{"type":"response.completed","response":{"status":"completed"}}""")
            assertTrue(second.get(3, TimeUnit.SECONDS))
            assertEquals(1, server.requestCount)
        } finally {
            socket.close()
            worker.shutdownNow()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.close()
        }
    }

    @Test
    fun cancellationUnblocksAResponseThatHasNotFinished() {
        val server = MockWebServer()
        val received = CountDownLatch(1)
        val client = OkHttpClient()
        val worker = Executors.newSingleThreadExecutor()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { received.countDown() }
        }))
        val socket = ResponseSocket(client, Request.Builder().url(server.url("/v1/responses")).build())
        try {
            val result = worker.submit<Boolean> {
                try {
                    socket.answer(JSONObject().put("store", false)) {}
                    false
                } catch (_: java.io.IOException) { true }
            }
            assertTrue(received.await(3, TimeUnit.SECONDS))
            socket.close()
            assertTrue(result.get(1, TimeUnit.SECONDS))
            assertFalse(socket.usable)
        } finally {
            socket.close()
            worker.shutdownNow()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.close()
        }
    }

    @Test
    fun rejectedHandshakeAllowsFallbackBeforeAnyInferenceIsSent() {
        val server = MockWebServer()
        val client = OkHttpClient()
        server.enqueue(MockResponse().setResponseCode(403))
        val socket = ResponseSocket(client, Request.Builder().url(server.url("/v1/responses")).build())
        try {
            assertFalse(socket.answer(JSONObject().put("store", false)) { fail("No text expected") })
            assertEquals(1, server.requestCount)
        } finally {
            socket.close()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.close()
        }
    }
}

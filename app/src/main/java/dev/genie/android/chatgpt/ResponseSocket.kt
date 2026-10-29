package dev.genie.android.chatgpt

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.io.Reader
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One authenticated connection, with one response in flight at a time. */
class ResponseSocket(http: OkHttpClient, request: Request) {
    private val opened = CountDownLatch(1)
    private val messages = LinkedBlockingQueue<String>(256)
    @Volatile private var failure: IOException? = null
    private val socket = http.newWebSocket(request, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { opened.countDown() }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!messages.offer(text)) {
                fail(IOException("Response buffer exceeded"))
                webSocket.cancel()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            fail(IOException("Response connection failed", t))
            response?.close()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            fail(IOException("Response connection closed"))
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            fail(IOException("Response connection closed"))
        }
    })

    val usable: Boolean get() = failure == null

    /** False means no inference was submitted, so the caller may safely use HTTP instead. */
    fun answer(body: JSONObject, onText: (String) -> Unit): Boolean {
        if (!opened.await(1, TimeUnit.SECONDS) || failure != null) return false
        val event = JSONObject(body.toString()).put("type", "response.create")
        event.remove("stream")
        if (!socket.send(event.toString())) return false
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        // Adapt JSON messages to the existing SSE decoder so error and completion handling stay identical.
        val reader = object : Reader() {
            private var frame = ""
            private var position = 0

            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                failure?.let { throw it }
                if (position == frame.length) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) throw SocketTimeoutException("Response deadline exceeded")
                    val message = messages.poll(minOf(remaining, TimeUnit.SECONDS.toNanos(60)), TimeUnit.NANOSECONDS)
                    failure?.let { throw it }
                    if (message == null) throw SocketTimeoutException("Response stream timed out")
                    frame = message.lineSequence().joinToString("\n") { "data: $it" } + "\n\n"
                    position = 0
                }
                val count = minOf(length, frame.length - position)
                frame.toCharArray(buffer, offset, position, position + count)
                position += count
                return count
            }

            override fun close() = Unit
        }
        ResponseStream.read(reader.buffered(), onText)
        return true
    }

    fun close() {
        fail(IOException("Response connection cancelled"))
        socket.cancel()
    }

    private fun fail(error: IOException) {
        failure = error
        opened.countDown()
        messages.offer("")
    }
}

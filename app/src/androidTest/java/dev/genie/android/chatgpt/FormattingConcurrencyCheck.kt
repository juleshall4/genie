package dev.genie.android.chatgpt

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FormattingConcurrencyCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val name = "formatting_check_${java.util.UUID.randomUUID()}"
        val directory = File(targetContext.cacheDir, name).apply { mkdirs() }
        val isolated = object : ContextWrapper(targetContext) {
            override fun getNoBackupFilesDir() = directory
            override fun getSharedPreferences(ignored: String, mode: Int) = targetContext.getSharedPreferences(name, mode)
        }
        val formattingStarted = CountDownLatch(1)
        val releaseFormatting = CountDownLatch(1)
        val formatted = CountDownLatch(1)
        val answered = CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            var status = 200
            val body = when {
                request.header("Upgrade") == "websocket" -> { status = 403; "{}" }
                request.url.encodedPath.endsWith("/models") -> """{"models":[{"slug":"test-model","visibility":"list"}]}"""
                else -> {
                    val buffer = Buffer()
                    request.body!!.writeTo(buffer)
                    val formatting = JSONObject(buffer.readUtf8()).optString("instructions") == TranscriptFormatting.INSTRUCTIONS
                    if (formatting) { formattingStarted.countDown(); check(releaseFormatting.await(5, TimeUnit.SECONDS)) }
                    val text = if (formatting) "What is the weather like in Liverpool?" else "Test answer."
                    "data: ${JSONObject().put("type", "response.output_text.delta").put("delta", text)}\n\n" +
                        "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Test response")
                .body(body.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val client = ChatGptClient(isolated, http)
        var formatting: ChatGptRequest? = null
        var answer: ChatGptRequest? = null
        try {
            client.store.save(ChatGptAccount("test-client", "test-subject", "", "test-access", "test-refresh", "", Long.MAX_VALUE,
                setOf("chatgpt.tokens.use.direct", "resource.invoke")))
            formatting = client.formatTranscript("what is the weather like in Liverpool") { _, _ -> formatted.countDown() }
            check(formattingStarted.await(3, TimeUnit.SECONDS)) { "Formatting did not start" }
            var answerError: Int? = null
            var text = ""
            answer = client.answer(listOf(ChatTurn("user", "what is the weather like in Liverpool")), onText = { text = it }, onDone = {
                answerError = it; answered.countDown()
            })
            check(answered.await(2, TimeUnit.SECONDS)) { "A slow formatting request blocked the answer" }
            check(answerError == null && text == "Test answer.") { "Answer failed while formatting was running" }
            releaseFormatting.countDown()
            check(formatted.await(3, TimeUnit.SECONDS)) { "Formatting did not complete after the answer" }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: answers complete while transcript formatting is stalled\n") })
        } catch (error: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: ${error.message}\n") })
        } finally {
            formatting?.cancel(); answer?.cancel(); releaseFormatting.countDown()
            client.closeConnection()
            directory.deleteRecursively()
            targetContext.deleteSharedPreferences(name)
            http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
        }
    }
}

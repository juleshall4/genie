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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CredentialRefreshCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        try {
            for (action in listOf("cancel", "switch", "signout", "invalid", "unavailable")) checkRefresh(action)
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: safe cancellation and account switching, sign-out, rejected-token recovery, and preserving credentials during temporary failures\n") })
        } catch (error: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: ${error.message}\n") })
        }
    }

    private fun checkRefresh(action: String) {
        val name = "refresh_check_${java.util.UUID.randomUUID()}"
        val directory = File(targetContext.cacheDir, name).apply { mkdirs() }
        val isolated = object : ContextWrapper(targetContext) {
            override fun getNoBackupFilesDir() = directory
            override fun getSharedPreferences(ignored: String, mode: Int) = targetContext.getSharedPreferences(name, mode)
        }
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val refreshing = chain.request().url.encodedPath.endsWith("/oauth/token")
            val body = if (refreshing) {
                received.countDown()
                check(release.await(3, TimeUnit.SECONDS)) { "Refresh was not released" }
                when (action) {
                    "invalid" -> """{"error":"invalid_grant"}"""
                    "unavailable" -> """{"error":"temporarily_unavailable"}"""
                    else -> """{"access_token":"new-access","refresh_token":"new-refresh","token_type":"Bearer","expires_in":3600}"""
                }
            } else """{"models":[]}"""
            val status = if (refreshing && action == "invalid") 400 else if (refreshing && action == "unavailable") 503 else 200
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Test response")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = ChatGptClient(isolated, http)
        try {
            client.store.save(ChatGptAccount("test-client", "test-subject", "", "old-access", "old-refresh", "", 0,
                setOf("chatgpt.tokens.use.direct", "resource.invoke")))
            val request = client.prepare()
            check(received.await(3, TimeUnit.SECONDS)) { "Refresh was not submitted" }
            when (action) {
                "cancel" -> request.cancel()
                "switch" -> {
                    client.store.save(ChatGptAccount("other-client", "other-subject", "", "other-access", "other-refresh", "",
                        Long.MAX_VALUE, setOf("chatgpt.tokens.use.direct", "resource.invoke")), activate = false)
                    client.select("other-client")
                }
                "signout" -> client.signOut { }
            }
            release.countDown()
            val drained = CountDownLatch(1)
            client.models(refresh = false) { _, _ -> drained.countDown() }
            check(drained.await(3, TimeUnit.SECONDS)) { "Renewal did not finish after $action" }
            val renewed = client.store.accounts().first { it.clientId == "test-client" }
            if (action == "signout" || action == "invalid") {
                check(!renewed.connected && renewed.refreshToken.isEmpty()) { "Credentials remained connected after $action" }
            } else if (action == "unavailable") {
                check(renewed.connected && renewed.refreshToken == "old-refresh") { "Temporary failure erased valid credentials" }
            } else {
                check(renewed.refreshToken == "new-refresh") { "$action lost the replacement refresh token" }
                if (action == "switch") check(client.store.active()?.clientId == "other-client") { "Renewal changed the selected account" }
            }
        } finally {
            release.countDown()
            client.closeConnection()
            directory.deleteRecursively()
            targetContext.deleteSharedPreferences(name)
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
        }
    }
}

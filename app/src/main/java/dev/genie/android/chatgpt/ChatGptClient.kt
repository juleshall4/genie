package dev.genie.android.chatgpt

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.genie.android.R
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ChatGptRequest {
    @Volatile var cancelled = false
        private set
    @Volatile private var call: Call? = null
    @Volatile private var cancelStream: (() -> Unit)? = null

    fun attach(next: Call) {
        call = next
        if (cancelled) next.cancel()
    }

    fun cancel() {
        cancelled = true
        call?.cancel()
        cancelStream?.invoke()
    }

    fun streamCancellation(action: (() -> Unit)?) {
        cancelStream = action
        if (cancelled) action?.invoke()
    }
}

data class ChatTurn(val role: String, val text: String)

class ChatGptClient private constructor(context: Context) {
    val store = ChatGptStore(context)
    val settings = ChatGptSettings(context)
    private val main = Handler(Looper.getMainLooper())
    private val modelCache = ModelCatalogCache(SystemClock::elapsedRealtime)
    private var responseSocket: ResponseSocket? = null
    private var socketAccount: String? = null
    private var socketToken: String? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val answerWorker = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    private val refreshLock = Any()
    private val stateLock = Any()
    private val requests = java.util.concurrent.ConcurrentHashMap.newKeySet<ChatGptRequest>()
    @Volatile var signIn: BrowserSignIn? = null
        private set

    fun beginSignIn(clientId: String?): BrowserSignIn {
        signIn?.cancel()
        return BrowserSignIn(clientId?.let { id -> store.accounts().firstOrNull { it.clientId == id } }).also {
            signIn = it
            it.start()
        }
    }

    fun select(clientId: String) {
        synchronized(stateLock) {
            requests.forEach { it.cancel() }
            modelCache.clear()
            closeConnection()
            store.select(clientId)
        }
    }

    fun signOut(onDone: (Boolean) -> Unit) {
        val account = synchronized(stateLock) {
            signIn?.cancel()
            signIn = null
            requests.forEach { it.cancel() }
            modelCache.clear()
            closeConnection()
            store.active()?.also {
                // Keep the registration, but prevent further requests immediately.
                store.save(it.copy(accessToken = "", refreshToken = "", idToken = "", expiresAt = 0))
            }
        } ?: return onDone(true)
        worker.execute {
            val revoked = runCatching {
                if (account.refreshToken.isNotEmpty()) {
                    val discovery = getJson("https://auth.openai.com/.well-known/openid-configuration")
                    val endpoint = discovery.getString("revocation_endpoint")
                    require(endpoint == "https://auth.openai.com/api/accounts/oauth/revoke")
                    http.newCall(Request.Builder().url(endpoint).post(FormBody.Builder()
                        .add("token", account.refreshToken).add("token_type_hint", "refresh_token")
                        .add("client_id", account.clientId).build()).build()).execute().use {
                        check(it.isSuccessful)
                    }
                }
            }.isSuccess
            main.post { onDone(revoked) }
        }
    }

    fun answer(turns: List<ChatTurn>, onText: (String) -> Unit, onDone: (Int?) -> Unit): ChatGptRequest {
        val personality = settings.personality
        val operation = ChatGptRequest()
        val accountId = store.active()?.takeIf { it.connected }?.clientId
        requests.add(operation)
        answerWorker.execute {
            try {
                if (operation.cancelled) return@execute
                if (accountId == null) throw ChatGptFailure(R.string.chatgpt_connect_needed)
                val (account, catalog) = catalog(accountId, operation)
                val body = settings.selection(accountId, catalog).requestBody(turns, personality)
                val connection = connection(account)
                operation.streamCancellation(connection::close)
                val streamed = connection.answer(body) { text ->
                    main.post { if (!operation.cancelled) onText(text) }
                }
                operation.streamCancellation(null)
                if (streamed) {
                    main.post { if (!operation.cancelled) onDone(null) }
                    return@execute
                }
                closeConnection()
                if (operation.cancelled) return@execute
                fun openAnswer(refreshAccess: Boolean): okhttp3.Response {
                    val (account, catalog) = catalog(accountId, operation, refreshAccess = refreshAccess)
                    val body = settings.selection(accountId, catalog).requestBody(turns, personality)
                    val request = Request.Builder().url("https://api.openai.com/v1/responses")
                        .header("Authorization", "Bearer ${account.accessToken}")
                        .header("Accept", "text/event-stream")
                        .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                    val call = http.newCall(request)
                    operation.attach(call)
                    return call.execute()
                }
                var response = openAnswer(refreshAccess = false)
                if (response.code == 401 && !operation.cancelled) {
                    response.close()
                    modelCache.clear()
                    response = openAnswer(refreshAccess = true)
                }
                response.use {
                    checkStatus(response.code, if (response.isSuccessful) null else response.body?.string())
                    val reader = response.body?.charStream()?.buffered()
                        ?: throw ChatGptFailure(R.string.chatgpt_interrupted)
                    ResponseStream.read(reader) { text ->
                        main.post { if (!operation.cancelled) onText(text) }
                    }
                }
                main.post { if (!operation.cancelled) onDone(null) }
            } catch (error: Exception) {
                closeConnection()
                val message = (error as? ChatGptFailure)?.messageResource ?: R.string.chatgpt_network_error
                if (error is ChatGptFailure) modelCache.clear()
                main.post { if (!operation.cancelled) onDone(message) }
            } finally {
                operation.streamCancellation(null)
                requests.remove(operation)
            }
        }
        return operation
    }

    fun prepare(): ChatGptRequest = models(refresh = false) { _, error ->
        if (error == null) store.active()?.takeIf { it.connected }?.let { connection(it) }
    }

    @Synchronized
    private fun connection(account: ChatGptAccount): ResponseSocket {
        val current = responseSocket
        if (current != null && current.usable && socketAccount == account.clientId && socketToken == account.accessToken) {
            return current
        }
        closeConnection()
        return ResponseSocket(http, Request.Builder().url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer ${account.accessToken}").build()).also {
            responseSocket = it
            socketAccount = account.clientId
            socketToken = account.accessToken
        }
    }

    @Synchronized
    fun closeConnection() {
        responseSocket?.close()
        responseSocket = null
        socketAccount = null
        socketToken = null
    }

    fun models(refresh: Boolean = true, onDone: (ChatGptModels?, Int?) -> Unit): ChatGptRequest {
        val operation = ChatGptRequest()
        val accountId = store.active()?.takeIf { it.connected }?.clientId
        requests.add(operation)
        worker.execute {
            try {
                if (operation.cancelled) return@execute
                if (accountId == null) throw ChatGptFailure(R.string.chatgpt_connect_needed)
                val models = catalog(accountId, operation, refresh).second
                main.post { if (!operation.cancelled) onDone(models, null) }
            } catch (failure: Exception) {
                val message = (failure as? ChatGptFailure)?.messageResource ?: R.string.chatgpt_network_error
                main.post { if (!operation.cancelled) onDone(null, message) }
            } finally {
                requests.remove(operation)
            }
        }
        return operation
    }

    private fun catalog(
        clientId: String,
        operation: ChatGptRequest,
        refresh: Boolean = false,
        refreshAccess: Boolean = false
    ): Pair<ChatGptAccount, ChatGptModels> {
        var account = access(clientId, operation, force = refreshAccess)
        if (!refresh) modelCache.get(clientId, account.accessToken)?.let { return account to it }
        var response = authorizedGet("https://api.openai.com/v1/models", account.accessToken, operation)
        if (response.first == 401) {
            account = access(clientId, operation, force = true)
            response = authorizedGet("https://api.openai.com/v1/models", account.accessToken, operation)
        }
        checkStatus(response.first, response.second)
        val catalog = ChatGptModels.parse(JSONObject(response.second))
        synchronized(stateLock) {
            if (!operation.cancelled && store.active()?.accessToken == account.accessToken) {
                modelCache.put(clientId, account.accessToken, catalog)
            }
        }
        return account to catalog
    }

    private fun access(clientId: String, operation: ChatGptRequest, force: Boolean = false): ChatGptAccount = synchronized(refreshLock) {
        val account = store.active()?.takeIf { it.clientId == clientId && it.connected }
            ?: throw ChatGptFailure(R.string.chatgpt_connect_needed)
        if (!force && account.expiresAt > System.currentTimeMillis() + 60_000) return@synchronized account
        if (account.refreshToken.isEmpty()) throw ChatGptFailure(R.string.chatgpt_connect_needed)
        val tokens = exchange(FormBody.Builder().add("grant_type", "refresh_token")
            .add("client_id", clientId).add("refresh_token", account.refreshToken)
            .add("resource", "https://api.openai.com/v1").build(), operation)
        val renewed = credential(tokens, clientId, null, account)
        synchronized(stateLock) {
            if (operation.cancelled || store.active()?.accessToken != account.accessToken) {
                throw ChatGptFailure(R.string.chatgpt_connect_needed)
            }
            store.save(renewed)
        }
        if (!renewed.connected) throw ChatGptFailure(R.string.chatgpt_plan_permission_needed)
        renewed
    }

    private fun credential(tokens: JSONObject, clientId: String, nonce: String?, previous: ChatGptAccount?): ChatGptAccount {
        val idToken = tokens.optString("id_token").ifEmpty { previous?.idToken.orEmpty() }
        val identity = if (tokens.optString("id_token").isNotEmpty() || nonce != null) {
            val discovery = getJson("https://auth.openai.com/.well-known/openid-configuration")
            require(discovery.getString("issuer") == "https://auth.openai.com")
            require(discovery.getString("jwks_uri") == "https://auth.openai.com/.well-known/jwks.json")
            OpenAiIdentity.validate(idToken, getJson(discovery.getString("jwks_uri")).toString(), clientId, nonce)
        } else {
            requireNotNull(previous).let { it.subject to it.email }
        }
        require(previous == null || previous.subject == identity.first)
        val scopes = if (tokens.has("scope")) tokens.getString("scope").split(' ').filter { it.isNotBlank() }.toSet()
            else previous?.scopes.orEmpty()
        require(tokens.getString("token_type").equals("Bearer", ignoreCase = true))
        val expiry = tokens.getLong("expires_in")
        require(expiry in 1..604800)
        val token = tokens.getString("access_token")
        require(token.isNotBlank())
        return ChatGptAccount(clientId, identity.first, identity.second, token,
            tokens.optString("refresh_token").ifEmpty { previous?.refreshToken.orEmpty() }, idToken,
            System.currentTimeMillis() + expiry * 1000, scopes)
    }

    private fun exchange(body: FormBody, operation: ChatGptRequest): JSONObject {
        val call = http.newCall(Request.Builder().url("https://auth.openai.com/api/accounts/oauth/token").post(body).build())
        operation.attach(call)
        return call.execute().use {
            if (!it.isSuccessful) throw ChatGptFailure(R.string.chatgpt_connect_needed)
            JSONObject(it.body?.string().orEmpty())
        }
    }

    private fun getJson(url: String): JSONObject = http.newCall(Request.Builder().url(url).build()).execute().use {
        if (!it.isSuccessful) throw ChatGptFailure(R.string.chatgpt_sign_in_failed)
        JSONObject(it.body?.string().orEmpty())
    }

    private fun authorizedGet(url: String, token: String, operation: ChatGptRequest): Pair<Int, String> {
        val call = http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build())
        operation.attach(call)
        return call.execute().use { it.code to it.body?.string().orEmpty() }
    }

    private fun checkStatus(code: Int, body: String? = null) {
        if (code in 200..299) return
        val fallback = when (code) {
            401 -> R.string.chatgpt_connect_needed
            403 -> R.string.chatgpt_plan_permission_needed
            429 -> R.string.chatgpt_usage_limit
            else -> R.string.chatgpt_request_failed
        }
        val errorCode = runCatching { JSONObject(body.orEmpty()).optJSONObject("error")?.optString("code") }.getOrNull()
        throw ChatGptFailure(chatGptError(errorCode.orEmpty(), fallback))
    }

    inner class BrowserSignIn(private val previous: ChatGptAccount?) {
        private val operation = ChatGptRequest()
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 300_000 }
        private val attempt = OAuthAttempt("http://127.0.0.1:${server.localPort}/auth/callback", previous?.clientId)
        @Volatile var url: String? = null
            private set
        @Volatile var finished = false
            private set
        @Volatile var error: Int? = null
            private set
        var browserOpened = false
        var listener: (() -> Unit)? = null

        fun start() {
            Thread({
                try {
                    url = attempt.authorizationUrl(store.hostId, previous?.idToken)
                    notifyChanged()
                    val (code, clientId) = awaitCallback()
                    val tokens = exchange(FormBody.Builder().add("grant_type", "authorization_code")
                        .add("client_id", clientId).add("code", code).add("code_verifier", attempt.verifier)
                        .add("redirect_uri", attempt.redirectUri).add("resource", "https://api.openai.com/v1").build(), operation)
                    val account = credential(tokens, clientId, attempt.nonce, previous)
                    synchronized(stateLock) {
                        if (operation.cancelled) return@Thread
                        requests.forEach { it.cancel() }
                        store.save(account)
                    }
                    if (!account.connected) throw ChatGptFailure(R.string.chatgpt_plan_permission_needed)
                } catch (failure: Exception) {
                    error = (failure as? ChatGptFailure)?.messageResource ?: R.string.chatgpt_sign_in_failed
                } finally {
                    server.close()
                    finished = true
                    notifyChanged()
                }
            }, "Genie-ChatGPT-sign-in").start()
        }

        fun cancel() {
            operation.cancel()
            server.close()
        }

        private fun notifyChanged() { main.post { listener?.invoke() } }

        private fun awaitCallback(): Pair<String, String> {
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
            while (!operation.cancelled && System.nanoTime() < deadline) {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    try {
                        val input = socket.getInputStream()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n") && header.length < 16384) {
                            val next = input.read()
                            if (next < 0) break
                            header.append(next.toChar())
                        }
                        require(header.endsWith("\r\n\r\n"))
                        val request = header.lineSequence().first().split(' ')
                        require(request.size == 3 && request[0] == "GET")
                        val target = java.net.URI(request[1])
                        require(!target.isAbsolute)
                        if (target.path != "/auth/callback") {
                            respond(socket, "404 Not Found", "Not found")
                        } else {
                            val result = attempt.callback(request[1])
                            respond(socket, "200 OK", landingPage)
                            return result
                        }
                    } catch (failure: ChatGptFailure) {
                        respond(socket, "200 OK", landingPage)
                        throw failure
                    } catch (_: IllegalArgumentException) {
                        respond(socket, "400 Bad Request", "Invalid sign-in callback. Return to Genie and try again.")
                    }
                }
            }
            throw ChatGptFailure(R.string.chatgpt_sign_in_failed)
        }

        private fun respond(socket: Socket, status: String, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write(("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\n" +
                    "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                write(bytes)
                flush()
            }
        }
    }

    companion object {
        @Volatile private var instance: ChatGptClient? = null
        fun get(context: Context): ChatGptClient = instance ?: synchronized(this) {
            instance ?: ChatGptClient(context.applicationContext).also { instance = it }
        }

        private const val landingPage = """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><title>Genie</title></head><body style="font:18px sans-serif;padding:32px;background:#f5f4ef;color:#203e35"><h1>Return to Genie</h1><p>Your sign-in response was received. Return to Genie to finish connecting.</p><a href="genie://signin-complete">Return to Genie</a></body></html>"""
    }
}

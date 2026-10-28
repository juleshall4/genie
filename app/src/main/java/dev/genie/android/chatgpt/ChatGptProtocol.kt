package dev.genie.android.chatgpt

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import dev.genie.android.R
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class ChatGptFailure(val messageResource: Int) : IOException()

fun chatGptError(code: String, fallback: Int): Int = when (code) {
    "subscription_sharing_user_not_eligible" -> R.string.chatgpt_ineligible
    "subscription_sharing_usage_limit_exceeded" -> R.string.chatgpt_usage_limit
    "subscription_sharing_usage_unavailable" -> R.string.chatgpt_usage_unavailable
    "subscription_sharing_unsupported_capability" -> R.string.chatgpt_unsupported_request
    else -> fallback
}

class OAuthAttempt(val redirectUri: String, val clientId: String?) {
    val state = randomValue()
    val nonce = randomValue()
    val verifier = randomValue()

    fun authorizationUrl(hostId: String, idTokenHint: String? = null): String {
        val parameters = linkedMapOf(
            "client_id" to (clientId ?: "dynamic_agent_client"),
            "ext_agent_host_id" to hostId,
            "response_type" to "code",
            "redirect_uri" to redirectUri,
            "scope" to "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
            "resource" to "https://api.openai.com/v1",
            "state" to state,
            "nonce" to nonce,
            "code_challenge_method" to "S256",
            "code_challenge" to Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            )
        )
        if (clientId == null) parameters["agent_name_hint"] = "Genie"
        else if (!idTokenHint.isNullOrEmpty()) parameters["id_token_hint"] = idTokenHint
        return "https://auth.openai.com/api/accounts/authorize?" + parameters.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
    }

    fun callback(target: String): Pair<String, String> {
        val uri = URI(target)
        require(uri.path == "/auth/callback")
        val parameters = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
            val parts = it.split('=', limit = 2)
            java.net.URLDecoder.decode(parts[0], "UTF-8") to
                java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }
        require(parameters.map { it.first }.distinct().size == parameters.size)
        val query = parameters.toMap()
        require(MessageDigest.isEqual(state.toByteArray(), query["state"].orEmpty().toByteArray()))
        if (query.containsKey("error")) throw ChatGptFailure(R.string.chatgpt_sign_in_declined)
        val issuedId = query["client_id"] ?: clientId
        require(!issuedId.isNullOrBlank() && issuedId != "dynamic_agent_client")
        require(clientId == null || issuedId == clientId)
        val code = query["code"]
        require(!code.isNullOrBlank())
        return code to issuedId
    }

    private fun randomValue(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}

object OpenAiIdentity {
    fun validate(token: String, jwks: String, clientId: String, nonce: String?): Pair<String, String> {
        val jwt = SignedJWT.parse(token)
        require(jwt.header.algorithm == JWSAlgorithm.RS256)
        require(!jwt.header.keyID.isNullOrBlank())
        val key = JWKSet.parse(jwks).getKeyByKeyId(jwt.header.keyID) as? RSAKey
            ?: throw IllegalArgumentException("Unknown signing key")
        require(jwt.verify(RSASSAVerifier(key)))
        val claims = jwt.jwtClaimsSet
        require(claims.issuer == "https://auth.openai.com")
        require(clientId in claims.audience)
        val authorizedParty = claims.getStringClaim("azp")
        if (claims.audience.size > 1 || authorizedParty != null) require(authorizedParty == clientId)
        require(claims.expirationTime != null && claims.expirationTime.time > System.currentTimeMillis())
        require(claims.notBeforeTime == null || claims.notBeforeTime.time <= System.currentTimeMillis() + 60_000)
        if (nonce != null) require(MessageDigest.isEqual(nonce.toByteArray(), claims.getStringClaim("nonce").orEmpty().toByteArray()))
        require(!claims.subject.isNullOrBlank())
        return claims.subject to claims.getStringClaim("email").orEmpty()
    }
}

object ResponseStream {
    fun read(reader: BufferedReader, onText: (String) -> Unit): String {
        val text = StringBuilder()
        val data = mutableListOf<String>()
        var completed = false

        fun event() {
            if (data.isEmpty()) return
            val payload = data.joinToString("\n")
            data.clear()
            if (payload == "[DONE]") return
            val json = JSONObject(payload)
            when (json.optString("type")) {
                "response.output_text.delta", "response.refusal.delta" -> {
                    text.append(json.optString("delta"))
                    onText(text.toString())
                }
                "response.completed" -> {
                    val response = json.getJSONObject("response")
                    if (response.optString("status") != "completed") throw ChatGptFailure(R.string.chatgpt_interrupted)
                    if (text.isEmpty()) {
                        val output = response.optJSONArray("output")
                        for (i in 0 until (output?.length() ?: 0)) {
                            val content = output!!.getJSONObject(i).optJSONArray("content") ?: continue
                            for (j in 0 until content.length()) {
                                val item = content.getJSONObject(j)
                                text.append(if (item.optString("type") == "refusal") item.optString("refusal") else item.optString("text"))
                            }
                        }
                        onText(text.toString())
                    }
                    completed = true
                }
                "response.failed", "error" -> {
                    val error = json.optJSONObject("response")?.optJSONObject("error") ?: json.optJSONObject("error") ?: json
                    throw ChatGptFailure(chatGptError(error.optString("code"), R.string.chatgpt_request_failed))
                }
                "response.incomplete" -> throw ChatGptFailure(R.string.chatgpt_interrupted)
            }
        }

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) {
                event()
                if (completed) break
            } else if (line.startsWith("data:")) data.add(line.substring(5).trimStart())
        }
        event()
        if (!completed || text.isBlank()) throw ChatGptFailure(R.string.chatgpt_interrupted)
        return text.toString()
    }
}

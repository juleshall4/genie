package dev.genie.android.chatgpt

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.genie.android.R
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.util.Date

class ChatGptProtocolTest {
    @Test fun newRegistrationRequiresMatchingStateAndIssuedClient() {
        val attempt = OAuthAttempt("http://127.0.0.1:1455/auth/callback", null)
        val valid = "/auth/callback?code=example-code&state=${attempt.state}&client_id=oaiapp_genie"
        assertEquals("example-code" to "oaiapp_genie", attempt.callback(valid))
        for (target in listOf(
            valid.replace(attempt.state, "wrong-state"),
            valid.substringBefore("&client_id="),
            "$valid&state=${attempt.state}",
            valid.replace("oaiapp_genie", "dynamic_agent_client"),
            valid.replace("/auth/callback", "/callback")
        )) assertThrows(IllegalArgumentException::class.java) { attempt.callback(target) }
    }

    @Test fun reauthorizationKeepsItsRegisteredClient() {
        val attempt = OAuthAttempt("http://127.0.0.1:1455/auth/callback", "oaiapp_existing")
        assertEquals("oaiapp_existing", attempt.callback("/auth/callback?code=code&state=${attempt.state}").second)
        assertThrows(IllegalArgumentException::class.java) {
            attempt.callback("/auth/callback?code=code&state=${attempt.state}&client_id=oaiapp_other")
        }
        val url = attempt.authorizationUrl("urn:uuid:host")
        assertTrue(url.contains("client_id=oaiapp_existing"))
        assertFalse(url.contains("agent_name_hint"))
    }

    @Test fun decliningConsentDoesNotProduceAnAuthorizationCode() {
        val attempt = OAuthAttempt("http://127.0.0.1:1455/auth/callback", null)
        val error = assertThrows(ChatGptFailure::class.java) {
            attempt.callback("/auth/callback?error=access_denied&state=${attempt.state}&code=ignored")
        }
        assertEquals(R.string.chatgpt_sign_in_declined, error.messageResource)
    }

    @Test fun identityValidationRejectsForgedOrMismatchedTokens() {
        val key = RSAKeyGenerator(2048).keyID("test-key").generate()
        val jwks = JWKSet(key.toPublicJWK()).toString()
        fun token(issuer: String = "https://auth.openai.com", audience: String = "oaiapp_genie",
                  nonce: String = "expected-nonce", expires: Long = System.currentTimeMillis() + 60_000,
                  forged: Boolean = false, authorizedParty: String? = null, multipleAudiences: Boolean = false): String {
            val builder = JWTClaimsSet.Builder().issuer(issuer)
                .audience(if (multipleAudiences) listOf(audience, "other-client") else listOf(audience))
                .subject("test-subject").claim("nonce", nonce).claim("email", "test@example.com").expirationTime(Date(expires))
            if (authorizedParty != null) builder.claim("azp", authorizedParty)
            val claims = builder.build()
            return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID("test-key").build(), claims).apply {
                sign(RSASSASigner(if (forged) RSAKeyGenerator(2048).generate() else key))
            }.serialize()
        }
        assertEquals("test-subject" to "test@example.com", OpenAiIdentity.validate(token(), jwks, "oaiapp_genie", "expected-nonce"))
        for (invalid in listOf(token(issuer = "https://attacker.example"), token(audience = "other-client"),
            token(nonce = "other-nonce"), token(expires = System.currentTimeMillis() - 60_000), token(forged = true),
            token(authorizedParty = "other-client"), token(multipleAudiences = true))) {
            assertThrows(IllegalArgumentException::class.java) {
                OpenAiIdentity.validate(invalid, jwks, "oaiapp_genie", "expected-nonce")
            }
        }
    }

    @Test fun answerIsDeliveredIncrementallyAndOnlyCompletesAtTheTerminalEvent() {
        val updates = mutableListOf<String>()
        val result = ResponseStream.read(StringReader("""
            : heartbeat

            data: {"type":"response.output_text.delta","delta":"Hello"}

            data: {"type":"response.output_text.delta","delta":" there"}

            data: {"type":"response.completed","response":{"status":"completed"}}

        """.trimIndent()).buffered(), updates::add)
        assertEquals(listOf("Hello", "Hello there"), updates)
        assertEquals("Hello there", result)
    }

    @Test fun interruptedOrIncompleteAnswersAreNotReportedAsSuccessful() {
        for (terminal in listOf("", "data: [DONE]\n\n", "data: {\"type\":\"response.incomplete\"}\n\n")) {
            val updates = mutableListOf<String>()
            val error = assertThrows(ChatGptFailure::class.java) {
                ResponseStream.read(StringReader("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Partial\"}\n\n$terminal").buffered(), updates::add)
            }
            assertEquals(listOf("Partial"), updates)
            assertEquals(R.string.chatgpt_interrupted, error.messageResource)
        }
    }

    @Test fun usageLimitAfterSomeTextStillFailsTheRequest() {
        val error = assertThrows(ChatGptFailure::class.java) {
            ResponseStream.read(StringReader("""
                data: {"type":"response.output_text.delta","delta":"Partial"}

                data: {"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}}

            """.trimIndent()).buffered()) {}
        }
        assertEquals(R.string.chatgpt_usage_limit, error.messageResource)
    }

    @Test fun eligibilityAndTemporaryUsageFailuresHaveDifferentRecoveryMessages() {
        for ((code, expected) in mapOf(
            "subscription_sharing_user_not_eligible" to R.string.chatgpt_ineligible,
            "subscription_sharing_usage_unavailable" to R.string.chatgpt_usage_unavailable
        )) {
            val error = assertThrows(ChatGptFailure::class.java) {
                ResponseStream.read(StringReader("data: {\"type\":\"error\",\"code\":\"$code\"}\n\n").buffered()) {}
            }
            assertEquals(expected, error.messageResource)
            assertNotEquals(R.string.chatgpt_connect_needed, error.messageResource)
        }
    }
    @Test fun webSearchEventsDeliverCitationsWithoutAddingSourceMetadataToAnswerText() {
        val updates = mutableListOf<String>()
        var searching = false
        var citations = emptyList<WebCitation>()
        val result = ResponseStream.read(StringReader("""
            data: {"type":"response.web_search_call.in_progress"}

            data: {"type":"response.output_text.delta","delta":"Android launched."}

            data: {"type":"response.completed","response":{"status":"completed","output":[
            data: {"type":"web_search_call","action":{"type":"search"}},
            data: {"type":"message","content":[{"type":"output_text","text":"Android launched.","annotations":[
            data: {"type":"url_citation","start_index":0,"end_index":16,"url":"https://developer.android.com/","title":"Android"},
            data: {"type":"url_citation","start_index":0,"end_index":16,"url":"javascript:alert(1)","title":"Unsafe"}
            data: ]}]}]}}

        """.trimIndent()).buffered(), updates::add, { citations = it }, { searching = true })
        assertTrue(searching)
        assertEquals("Android launched.", result)
        assertEquals(listOf("Android launched."), updates)
        assertEquals(listOf(WebCitation(0, 16, "https://developer.android.com/", "Android")), citations)
    }

    @Test fun markdownSourceLinksRemainAvailableWhenStructuredAnnotationsAreMissing() {
        val text = "Released in June. [Android](https://developer.android.com/latest-updates)."
        val links = WebCitation.markdownLinks(text)
        assertEquals(1, links.size)
        assertEquals("Android", links.single().title)
        assertEquals("https://developer.android.com/latest-updates", links.single().url)
        assertEquals("[Android](https://developer.android.com/latest-updates)", text.substring(links.single().start, links.single().end))
        assertTrue(WebCitation.markdownLinks("[Unsafe](javascript:alert)").isEmpty())
    }

}

package dev.dotcompanion.identity

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

internal const val TEST_NOW = 1_800_000_000L
internal const val TEST_HOST = "urn:uuid:2e0014d3-cf9e-4865-9e8f-e9832e576e96"
internal const val TEST_CLIENT = "oaiapp_synthetic"
internal val TEST_REDIRECT = URI("http://127.0.0.1:54321/auth/callback")

internal fun attempt(client: String? = null, subject: String? = null): SignInAttempt =
    OpenAiSignIn.createAttempt(TEST_HOST, TEST_REDIRECT, client, subject, TEST_NOW)

internal fun callback(attempt: SignInAttempt, values: Map<String, String> = emptyMap()): URI =
    URI("${attempt.redirectUri}?${encodeForm(linkedMapOf("state" to attempt.state, "code" to "synthetic-code",
        "client_id" to (attempt.registeredClientId ?: TEST_CLIENT)).apply { putAll(values) })}")

internal fun expectFailure(reason: SignInFailure? = null, action: () -> Unit): SignInException {
    try { action() } catch (failure: SignInException) {
        if (reason != null) assertEquals(reason, failure.reason)
        assertNull(failure.cause)
        return failure
    }
    throw AssertionError("Expected a sanitized sign-in failure")
}

class OpenAiSignInTest {
    @Test fun `new registrations use fresh randomness and identity-only S256 PKCE`() {
        val first = attempt()
        val second = attempt()
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.nonce, second.nonce)
        assertNotEquals(first.verifier, second.verifier)
        assertTrue(first.verifier.length in 43..128)
        val uri = OpenAiSignIn.authorizationUri(first)
        val query = decodeQuery(uri.rawQuery)
        assertEquals("https://auth.openai.com/api/accounts/authorize", uri.toString().substringBefore('?'))
        assertEquals("dynamic_agent_client", query["client_id"])
        assertEquals("Dot Companion", query["agent_name_hint"])
        assertEquals(TEST_HOST, query["ext_agent_host_id"])
        assertEquals("openid profile email", query["scope"])
        assertEquals(OpenAiSignIn.RESOURCE, query["resource"])
        assertEquals(TEST_REDIRECT.toString(), query["redirect_uri"])
        assertEquals(first.state, query["state"])
        assertEquals(first.nonce, query["nonce"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(first.verifier.toByteArray(StandardCharsets.US_ASCII))), query["code_challenge"])
        assertFalse(uri.toString().contains(first.verifier))
        assertFalse(uri.toString().contains("offline_access"))
        assertFalse(uri.toString().contains("chatgpt.tokens.use.direct"))
    }

    @Test fun `returning registrations keep their issued ID and omit initial display hint`() {
        val query = decodeQuery(OpenAiSignIn.authorizationUri(attempt(TEST_CLIENT, "subject-1")).rawQuery)
        assertEquals(TEST_CLIENT, query["client_id"])
        assertFalse(query.containsKey("agent_name_hint"))
    }

    @Test fun `host identifiers and loopback redirects are validated strictly`() {
        for (host in listOf("", "urn:uuid:1-1-1-1-1", "host-one", "urn:uuid:00000000-0000-0000-0000-000000000000")) {
            expectFailure(SignInFailure.INVALID_CONFIGURATION) { OpenAiSignIn.createAttempt(host, TEST_REDIRECT) }
        }
        for (redirect in listOf("http://localhost:54321/auth/callback", "http://127.0.0.1/auth/callback",
            "https://127.0.0.1:54321/auth/callback", "http://127.0.0.1:0/auth/callback", "http://127.0.0.1:54321/callback",
            "http://127.0.0.1:54321/auth/%63allback", "http://127.0.0.1:54321/auth/callback?x=1",
            "http://127.0.0.1:54321/auth/callback#fragment", "http://user@127.0.0.1:54321/auth/callback")) {
            expectFailure(SignInFailure.INVALID_CONFIGURATION) { OpenAiSignIn.createAttempt(TEST_HOST, URI(redirect)) }
        }
    }

    @Test fun `new registration requires a real issued client ID`() {
        val pending = attempt()
        for (id in listOf("", "dynamic_agent_client", "invalid client")) {
            expectFailure(SignInFailure.INVALID_CALLBACK) { OpenAiSignIn.parseCallback(callback(pending, mapOf("client_id" to id)), pending, TEST_NOW) }
        }
        val missing = URI("$TEST_REDIRECT?${encodeForm(mapOf("state" to pending.state, "code" to "code"))}")
        expectFailure(SignInFailure.INVALID_CALLBACK) { OpenAiSignIn.parseCallback(missing, pending, TEST_NOW) }
        assertEquals(TEST_CLIENT, OpenAiSignIn.parseCallback(callback(pending), pending, TEST_NOW).clientId)
    }

    @Test fun `reauthorization can omit client ID but cannot change it`() {
        val pending = attempt(TEST_CLIENT, "subject-1")
        expectFailure(SignInFailure.INVALID_CALLBACK) {
            OpenAiSignIn.parseCallback(callback(pending, mapOf("client_id" to "oaiapp_other")), pending, TEST_NOW)
        }
        val omitted = URI("$TEST_REDIRECT?${encodeForm(mapOf("state" to pending.state, "code" to "code"))}")
        assertEquals(TEST_CLIENT, OpenAiSignIn.parseCallback(omitted, pending, TEST_NOW).clientId)
    }

    @Test fun `invalid state and path leave a valid attempt usable`() {
        val pending = attempt()
        expectFailure(SignInFailure.INVALID_STATE) { OpenAiSignIn.parseCallback(callback(pending, mapOf("state" to "wrong")), pending, TEST_NOW) }
        val valid = callback(pending)
        for (invalid in listOf(valid.toString().replace("54321", "54322"), valid.toString().replace("/auth/callback", "/callback"),
            valid.toString().replace("127.0.0.1", "localhost"), "${valid}#fragment")) {
            expectFailure(SignInFailure.INVALID_CALLBACK) { OpenAiSignIn.parseCallback(URI(invalid), pending, TEST_NOW) }
        }
        assertEquals("synthetic-code", OpenAiSignIn.parseCallback(valid, pending, TEST_NOW).code)
    }

    @Test fun `duplicate and encoded duplicate security fields are rejected`() {
        val pending = attempt()
        for (extra in listOf("state=bad", "st%61te=bad", "code=other", "client_id=oaiapp_other", "scope=openid", "error=access_denied")) {
            val base = if (extra.startsWith("scope")) callback(pending, mapOf("scope" to "openid"))
                else if (extra.startsWith("error")) callback(pending, mapOf("error" to "access_denied")) else callback(pending)
            expectFailure(SignInFailure.INVALID_CALLBACK) { OpenAiSignIn.parseCallback(URI("$base&$extra"), pending, TEST_NOW) }
        }
        OpenAiSignIn.parseCallback(callback(pending), pending, TEST_NOW)
    }

    @Test fun `malformed or oversized query values cannot consume the attempt`() {
        val pending = attempt()
        for (query in listOf("state=%FF&code=x", "state=${pending.state}&code=", "state=${pending.state}&code=abc%0Adef&client_id=$TEST_CLIENT",
            "state=${pending.state}&code=${"x".repeat(33_000)}&client_id=$TEST_CLIENT")) {
            expectFailure { OpenAiSignIn.parseCallback(URI("$TEST_REDIRECT?$query"), pending, TEST_NOW) }
        }
        OpenAiSignIn.parseCallback(callback(pending), pending, TEST_NOW)
    }

    @Test fun `the callback can be accepted only once`() {
        val pending = attempt()
        val uri = callback(pending)
        OpenAiSignIn.parseCallback(uri, pending, TEST_NOW)
        expectFailure(SignInFailure.ATTEMPT_ALREADY_FINISHED) { OpenAiSignIn.parseCallback(uri, pending, TEST_NOW) }
    }

    @Test fun `concurrent valid callbacks have exactly one winner`() {
        val pending = attempt()
        val uri = callback(pending)
        val workers = Executors.newFixedThreadPool(2)
        val gate = CountDownLatch(1)
        try {
            val outcomes = (1..2).map { workers.submit<Boolean> {
                gate.await()
                try { OpenAiSignIn.parseCallback(uri, pending, TEST_NOW); true } catch (_: SignInException) { false }
            } }
            gate.countDown()
            assertEquals(1, outcomes.count { it.get(3, TimeUnit.SECONDS) })
        } finally { workers.shutdownNow() }
    }

    @Test fun `attempts reject future start times expiry and explicit cancellation`() {
        expectFailure(SignInFailure.EXPIRED_ATTEMPT) { val p = attempt(); OpenAiSignIn.parseCallback(callback(p), p, TEST_NOW - 1) }
        expectFailure(SignInFailure.EXPIRED_ATTEMPT) { val p = attempt(); OpenAiSignIn.parseCallback(callback(p), p, TEST_NOW + 300) }
        expectFailure(SignInFailure.CANCELLED) { val p = attempt(); p.cancel(); OpenAiSignIn.parseCallback(callback(p), p, TEST_NOW) }
    }

    @Test fun `provider errors require matching state and return fixed messages`() {
        val pending = attempt()
        expectFailure(SignInFailure.INVALID_STATE) {
            OpenAiSignIn.parseCallback(callback(pending, mapOf("state" to "wrong", "error" to "access_denied")), pending, TEST_NOW)
        }
        val denial = expectFailure(SignInFailure.AUTHORIZATION_DENIED) {
            OpenAiSignIn.parseCallback(callback(pending, mapOf("error" to "access_denied", "error_description" to "synthetic-secret")), pending, TEST_NOW)
        }
        assertFalse(denial.toString().contains("synthetic-secret"))
        expectFailure(SignInFailure.ATTEMPT_ALREADY_FINISHED) { OpenAiSignIn.parseCallback(callback(pending), pending, TEST_NOW) }
        val other = attempt()
        val error = expectFailure(SignInFailure.AUTHORIZATION_FAILED) {
            OpenAiSignIn.parseCallback(callback(other, mapOf("error" to "synthetic-secret-provider-error")), other, TEST_NOW)
        }
        assertFalse(error.toString().contains("synthetic-secret"))
    }

    @Test fun `secret bearing values are redacted in string representations`() {
        val pending = attempt()
        val code = OpenAiSignIn.parseCallback(callback(pending), pending, TEST_NOW)
        val tokens = OAuthTokens("access-secret", "id-secret", "refresh-secret", setOf("openid"), TEST_NOW + 30, "Bearer")
        val identity = AccountIdentity(OpenAiSignIn.ISSUER, "subject-secret", TEST_CLIENT, "private@example.test", "Private Name", TEST_NOW + 30)
        val strings = listOf(pending.toString(), code.toString(), tokens.toString(), identity.toString(), SignInSession(identity, tokens).toString(),
            OAuthHttpRequest(URI("${OpenAiSignIn.ISSUER}/token"), "POST", "body-secret").toString(),
            OAuthHttpResponse(200, "body-secret").toString()).joinToString()
        for (secret in listOf(pending.state, pending.nonce, pending.verifier, code.code, "access-secret", "id-secret", "refresh-secret",
            "subject-secret", "private@example.test", "Private Name", "body-secret")) assertFalse(strings.contains(secret))
    }
}

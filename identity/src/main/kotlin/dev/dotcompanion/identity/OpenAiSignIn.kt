package dev.dotcompanion.identity

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Fixed reasons may be shown to users. Provider payloads and causes are never retained. */
enum class SignInFailure(val safeMessage: String) {
    INVALID_CONFIGURATION("The sign-in configuration is invalid."),
    INVALID_CALLBACK("The sign-in callback could not be verified."),
    INVALID_STATE("The sign-in callback could not be verified."),
    EXPIRED_ATTEMPT("Sign-in expired. Please start again."),
    ATTEMPT_ALREADY_FINISHED("This sign-in attempt has already ended."),
    AUTHORIZATION_DENIED("ChatGPT sign-in was declined."),
    AUTHORIZATION_FAILED("ChatGPT sign-in could not be completed."),
    CANCELLED("Sign-in was cancelled."),
    CALLBACK_TIMED_OUT("Sign-in timed out. Please start again."),
    CALLBACK_UNAVAILABLE("The sign-in callback listener is unavailable."),
    TRANSPORT_FAILURE("The secure sign-in request could not be completed."),
    PROVIDER_CONFIGURATION_INVALID("The sign-in provider configuration could not be verified."),
    TOKEN_EXCHANGE_FAILED("ChatGPT sign-in could not be completed. Please start again."),
    TOKEN_RESPONSE_INVALID("The sign-in response could not be verified."),
    IDENTITY_INVALID("The ChatGPT account identity could not be verified."),
    SCOPES_INVALID("The required identity permissions were not granted.")
}

class SignInException(val reason: SignInFailure) : Exception(reason.safeMessage)

/** Secrets are in memory only. This object must never be serialized or logged. */
class SignInAttempt internal constructor(
    val redirectUri: URI,
    val state: String,
    val nonce: String,
    val verifier: String,
    val hostId: String,
    val registeredClientId: String?,
    val expectedSubject: String?,
    val startedAtEpochSeconds: Long
) {
    private enum class Stage { PENDING, CALLBACK_ACCEPTED, EXCHANGING, COMPLETED, FAILED, CANCELLED }
    private val stage = AtomicReference(Stage.PENDING)

    /** Cancellation also prevents an in-flight exchange from activating an account. */
    fun cancel() {
        while (true) {
            val previous = stage.get()
            if (previous in setOf(Stage.COMPLETED, Stage.FAILED, Stage.CANCELLED)) return
            if (stage.compareAndSet(previous, Stage.CANCELLED)) return
        }
    }

    internal fun assertFresh(now: Long) {
        if (stage.get() == Stage.CANCELLED) fail(SignInFailure.CANCELLED)
        if (now < startedAtEpochSeconds || now - startedAtEpochSeconds >= OpenAiSignIn.ATTEMPT_TTL_SECONDS) {
            fail(SignInFailure.EXPIRED_ATTEMPT)
        }
    }

    internal fun acceptCallback() {
        if (!stage.compareAndSet(Stage.PENDING, Stage.CALLBACK_ACCEPTED)) finished()
    }

    internal fun beginExchange(code: AuthorizationCode, now: Long) {
        assertFresh(now)
        if (code.attempt !== this) fail(SignInFailure.INVALID_CALLBACK)
        if (!stage.compareAndSet(Stage.CALLBACK_ACCEPTED, Stage.EXCHANGING)) finished()
    }

    internal fun finishExchange(now: Long) {
        assertFresh(now)
        if (!stage.compareAndSet(Stage.EXCHANGING, Stage.COMPLETED)) finished()
    }

    internal fun failExchange() { stage.compareAndSet(Stage.EXCHANGING, Stage.FAILED) }
    internal fun failCallback() { stage.compareAndSet(Stage.CALLBACK_ACCEPTED, Stage.FAILED) }
    internal val isCancelled: Boolean get() = stage.get() == Stage.CANCELLED

    private fun finished(): Nothing = fail(
        if (isCancelled) SignInFailure.CANCELLED else SignInFailure.ATTEMPT_ALREADY_FINISHED
    )

    override fun toString(): String = "SignInAttempt([redacted])"
}

class AuthorizationCode internal constructor(
    val code: String,
    val clientId: String,
    internal val attempt: SignInAttempt
) {
    override fun toString(): String = "AuthorizationCode([redacted])"
}

data class AccountIdentity(
    val issuer: String,
    val subject: String,
    val clientId: String,
    val email: String?,
    val displayName: String?,
    val expiresAtEpochSeconds: Long
) {
    override fun toString(): String = "AccountIdentity([redacted])"
}

/** The identity-only contract may omit access and refresh tokens entirely. */
class OAuthTokens(
    val accessToken: String?,
    val idToken: String,
    val refreshToken: String?,
    grantedScopes: Set<String>,
    val expiresAtEpochSeconds: Long,
    val tokenType: String?
) {
    /** Provider-declared permissions only; empty means scope was not reported, not an inferred grant. */
    val grantedScopes: Set<String> = Collections.unmodifiableSet(LinkedHashSet(grantedScopes))
    override fun toString(): String = "OAuthTokens([redacted])"
}

class SignInSession(val identity: AccountIdentity, val tokens: OAuthTokens) {
    override fun toString(): String = "SignInSession([redacted])"
}

object OpenAiSignIn {
    const val ISSUER = "https://auth.openai.com"
    const val RESOURCE = "https://api.openai.com/v1"
    const val ATTEMPT_TTL_SECONDS = 300L
    val AUTHORIZATION_ENDPOINT: URI = URI("$ISSUER/api/accounts/authorize")
    internal val REQUIRED_SCOPES = setOf("openid", "profile", "email")
    private val random = SecureRandom()
    private val hostIdPattern = Regex("urn:uuid:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun createAttempt(
        hostId: String,
        redirectUri: URI,
        registeredClientId: String? = null,
        expectedSubject: String? = null,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000
    ): SignInAttempt {
        if (!hostIdPattern.matches(hostId) || hostId.endsWith("00000000-0000-0000-0000-000000000000")) {
            fail(SignInFailure.INVALID_CONFIGURATION)
        }
        // UUID.fromString alone accepts shortened groups on some runtimes.
        val uuid = try { UUID.fromString(hostId.removePrefix("urn:uuid:")) } catch (_: Exception) {
            fail(SignInFailure.INVALID_CONFIGURATION)
        }
        if (!uuid.toString().equals(hostId.removePrefix("urn:uuid:"), ignoreCase = true)) {
            fail(SignInFailure.INVALID_CONFIGURATION)
        }
        validateRedirect(redirectUri)
        if (registeredClientId != null && !validClientId(registeredClientId)) fail(SignInFailure.INVALID_CONFIGURATION)
        if (expectedSubject != null && (registeredClientId == null || !opaqueValue(expectedSubject, 1024))) {
            fail(SignInFailure.INVALID_CONFIGURATION)
        }
        if (nowEpochSeconds < 0) fail(SignInFailure.INVALID_CONFIGURATION)
        return SignInAttempt(redirectUri, randomValue(32), randomValue(32), randomValue(64),
            hostId, registeredClientId, expectedSubject, nowEpochSeconds)
    }

    fun authorizationUri(attempt: SignInAttempt): URI {
        val parameters = linkedMapOf(
            "client_id" to (attempt.registeredClientId ?: "dynamic_agent_client"),
            "ext_agent_host_id" to attempt.hostId,
            "response_type" to "code",
            "redirect_uri" to attempt.redirectUri.toASCIIString(),
            "scope" to "openid profile email",
            "resource" to RESOURCE,
            "state" to attempt.state,
            "nonce" to attempt.nonce,
            "code_challenge_method" to "S256",
            "code_challenge" to Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(attempt.verifier.toByteArray(StandardCharsets.US_ASCII)))
        )
        if (attempt.registeredClientId == null) parameters["agent_name_hint"] = "Dot Companion"
        return URI("${AUTHORIZATION_ENDPOINT.toASCIIString()}?${encodeForm(parameters)}")
    }

    fun parseCallback(
        uri: URI,
        attempt: SignInAttempt,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000
    ): AuthorizationCode {
        if (uri.scheme != "http" || uri.rawAuthority != attempt.redirectUri.rawAuthority ||
            uri.rawPath != "/auth/callback" || uri.rawFragment != null || uri.rawUserInfo != null || uri.isOpaque) {
            fail(SignInFailure.INVALID_CALLBACK)
        }
        val parameters = decodeQuery(uri.rawQuery ?: fail(SignInFailure.INVALID_CALLBACK))
        val returnedState = parameters["state"] ?: fail(SignInFailure.INVALID_STATE)
        if (!constantTimeEquals(returnedState, attempt.state)) fail(SignInFailure.INVALID_STATE)
        attempt.assertFresh(nowEpochSeconds)
        if (parameters.containsKey("error")) {
            // A verified provider denial ends the attempt. No provider text is retained.
            attempt.acceptCallback()
            attempt.failCallback()
            fail(if (parameters["error"] == "access_denied") SignInFailure.AUTHORIZATION_DENIED
                else SignInFailure.AUTHORIZATION_FAILED)
        }
        val code = parameters["code"]?.takeIf { opaqueValue(it, 16_384) }
            ?: fail(SignInFailure.INVALID_CALLBACK)
        val returnedClient = parameters["client_id"]
        val clientId = if (attempt.registeredClientId == null) {
            returnedClient?.takeIf(::validClientId) ?: fail(SignInFailure.INVALID_CALLBACK)
        } else {
            if (returnedClient != null && returnedClient != attempt.registeredClientId) fail(SignInFailure.INVALID_CALLBACK)
            attempt.registeredClientId
        }
        attempt.acceptCallback()
        return AuthorizationCode(code, clientId, attempt)
    }

    internal fun validateRedirect(uri: URI) {
        val expected = "http://127.0.0.1:${uri.port}/auth/callback"
        if (uri.port !in 1..65535 || uri.toASCIIString() != expected || uri.isOpaque) {
            fail(SignInFailure.INVALID_CONFIGURATION)
        }
    }

    internal fun validClientId(value: String): Boolean = value != "dynamic_agent_client" &&
        value.matches(Regex("[A-Za-z0-9_.-]{1,256}"))

    private fun randomValue(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }
}

internal fun fail(reason: SignInFailure): Nothing = throw SignInException(reason)

internal fun constantTimeEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
    left.toByteArray(StandardCharsets.UTF_8), right.toByteArray(StandardCharsets.UTF_8)
)

internal fun opaqueValue(value: String, limit: Int): Boolean = value.isNotBlank() && value.length <= limit &&
    value.none { it.isWhitespace() || it.isISOControl() }

internal fun encodeForm(parameters: Map<String, String>): String = parameters.entries.joinToString("&") {
    "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
}

/** Reject duplicate fields, including encoded aliases, rather than choosing the first or last. */
internal fun decodeQuery(query: String): Map<String, String> {
    if (query.length > 32_768) fail(SignInFailure.INVALID_CALLBACK)
    val parameters = linkedMapOf<String, String>()
    val entries = query.split('&')
    if (entries.size > 24) fail(SignInFailure.INVALID_CALLBACK)
    for (entry in entries) {
        if (entry.isEmpty() || !entry.contains('=')) fail(SignInFailure.INVALID_CALLBACK)
        val key = decodeComponent(entry.substringBefore('='))
        val value = decodeComponent(entry.substringAfter('='))
        if (key.isBlank() || key.length > 128 || parameters.put(key, value) != null) fail(SignInFailure.INVALID_CALLBACK)
    }
    return parameters
}

private fun decodeComponent(value: String): String {
    // URLDecoder silently replaces malformed UTF-8. Security parameters are opaque ASCII.
    if (value.any { it.code > 127 || it.isISOControl() }) fail(SignInFailure.INVALID_CALLBACK)
    val decoded = try { URLDecoder.decode(value, "UTF-8") } catch (_: Exception) {
        fail(SignInFailure.INVALID_CALLBACK)
    }
    if (decoded.any { it == '\uFFFD' || it.isISOControl() }) fail(SignInFailure.INVALID_CALLBACK)
    return decoded
}

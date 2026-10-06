package dev.dotcompanion.identity

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.util.JSONObjectUtils
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

private object SigningKeys {
    val rsa: RSAKey by lazy { RSAKeyGenerator(2048).keyID("synthetic-rsa").generate() }
    val otherRsa: RSAKey by lazy { RSAKeyGenerator(2048).keyID("synthetic-other").generate() }
    val ec: ECKey by lazy { ECKeyGenerator(Curve.P_256).keyID("synthetic-ec").generate() }
}

private class SyntheticProvider(val pending: SignInAttempt, val issuedClientId: String = TEST_CLIENT) : OAuthHttpTransport {
    val requests = mutableListOf<OAuthHttpRequest>()
    var key: JWK = SigningKeys.rsa
    var signatureKey: JWK? = null
    var header: JWSHeader? = null
    var rawIdToken: String? = null
    var rawTokenBody: String? = null
    var rawJwks: String? = null
    var tokenStatus = 200
    var revokeStatus = 200
    var throwOnPath: String? = null
    var onRequest: ((OAuthHttpRequest) -> Unit)? = null
    val claims: MutableMap<String, Any?> = linkedMapOf(
        "iss" to OpenAiSignIn.ISSUER, "sub" to "subject-1", "aud" to issuedClientId,
        "iat" to TEST_NOW, "exp" to TEST_NOW + 3600, "nonce" to pending.nonce,
        "email" to "person@example.test", "name" to "Synthetic Person"
    )
    val metadata: MutableMap<String, Any?> = linkedMapOf(
        "issuer" to OpenAiSignIn.ISSUER,
        "authorization_endpoint" to "${OpenAiSignIn.ISSUER}/api/accounts/authorize",
        "token_endpoint" to "${OpenAiSignIn.ISSUER}/api/accounts/oauth/token",
        "jwks_uri" to "${OpenAiSignIn.ISSUER}/.well-known/jwks.json",
        "revocation_endpoint" to "${OpenAiSignIn.ISSUER}/api/accounts/oauth/revoke",
        "id_token_signing_alg_values_supported" to listOf("RS256", "ES256")
    )
    val tokenValues: MutableMap<String, Any?> = linkedMapOf("access_token" to "synthetic-access", "refresh_token" to "synthetic-refresh",
        "token_type" to "Bearer", "expires_in" to 3600, "scope" to "openid profile email")

    override fun execute(request: OAuthHttpRequest): OAuthHttpResponse {
        requests.add(request)
        onRequest?.invoke(request)
        if (request.uri.path == throwOnPath) throw IllegalStateException("synthetic-secret-network-error")
        return when (request.uri.path) {
            "/.well-known/openid-configuration" -> OAuthHttpResponse(200, JSONObjectUtils.toJSONString(metadata))
            "/api/accounts/oauth/token" -> OAuthHttpResponse(tokenStatus, rawTokenBody ?: JSONObjectUtils.toJSONString(
                LinkedHashMap(tokenValues).apply { put("id_token", rawIdToken ?: signedToken()) }))
            "/.well-known/jwks.json" -> OAuthHttpResponse(200, rawJwks ?: JWKSet(key.toPublicJWK()).toString())
            "/api/accounts/oauth/revoke" -> OAuthHttpResponse(revokeStatus, "")
            else -> throw AssertionError("Unexpected synthetic endpoint")
        }
    }

    fun signedToken(): String {
        val signingKey = signatureKey ?: key
        val algorithm = if (signingKey is ECKey) JWSAlgorithm.ES256 else JWSAlgorithm.RS256
        val jws = JWSObject(header ?: JWSHeader.Builder(algorithm).keyID(key.keyID).type(JOSEObjectType.JWT).build(),
            Payload(JSONObjectUtils.toJSONString(claims)))
        jws.sign(when (signingKey) {
            is RSAKey -> RSASSASigner(signingKey)
            is ECKey -> ECDSASigner(signingKey)
            else -> throw AssertionError("Unexpected test key")
        })
        return jws.serialize()
    }

    fun complete(): SignInSession {
        val code = OpenAiSignIn.parseCallback(callback(pending, mapOf("client_id" to issuedClientId)), pending, TEST_NOW)
        return OpenAiIdentityClient(this) { TEST_NOW }.complete(pending, code)
    }
}

class OpenAiIdentityClientTest {
    @Test fun `verified RSA identity activates with exact public-client exchange fields`() {
        val pending = attempt()
        val provider = SyntheticProvider(pending)
        val session = provider.complete()
        assertEquals("subject-1", session.identity.subject)
        assertEquals(TEST_CLIENT, session.identity.clientId)
        assertEquals(OpenAiSignIn.ISSUER, session.identity.issuer)
        assertEquals("person@example.test", session.identity.email)
        assertEquals("Synthetic Person", session.identity.displayName)
        assertEquals(TEST_NOW + 3600, session.identity.expiresAtEpochSeconds)
        assertEquals(TEST_NOW + 3600, session.tokens.expiresAtEpochSeconds)
        assertEquals("synthetic-access", session.tokens.accessToken)
        assertEquals("synthetic-refresh", session.tokens.refreshToken)
        assertEquals(setOf("openid", "profile", "email"), session.tokens.grantedScopes)
        val exchange = provider.requests.single { it.method == "POST" }
        val form = decodeQuery(exchange.body!!)
        assertEquals(mapOf("grant_type" to "authorization_code", "client_id" to TEST_CLIENT, "code" to "synthetic-code",
            "code_verifier" to pending.verifier, "redirect_uri" to TEST_REDIRECT.toString(), "resource" to OpenAiSignIn.RESOURCE), form)
        assertTrue(provider.requests.all { it.uri.host == "auth.openai.com" })
        assertEquals(3, provider.requests.size)
        expectFailure(SignInFailure.ATTEMPT_ALREADY_FINISHED) {
            OpenAiSignIn.parseCallback(callback(pending), pending, TEST_NOW)
        }
    }

    @Test fun `verified EC identity and returning account preserve subject and issued registration`() {
        val provider = SyntheticProvider(attempt(TEST_CLIENT, "subject-1")).apply { key = SigningKeys.ec }
        val session = provider.complete()
        assertEquals("subject-1", session.identity.subject)
        assertEquals(TEST_CLIENT, session.identity.clientId)
    }

    @Test fun `identity-only response is valid without access refresh or token type`() {
        val provider = SyntheticProvider(attempt())
        provider.tokenValues.keys.removeAll(setOf("access_token", "refresh_token", "token_type", "expires_in"))
        val session = provider.complete()
        assertNull(session.tokens.accessToken)
        assertNull(session.tokens.refreshToken)
        assertNull(session.tokens.tokenType)
        assertEquals(TEST_NOW + 3600, session.tokens.expiresAtEpochSeconds)
        val requestCount = provider.requests.size
        assertFalse(OpenAiIdentityClient(provider) { TEST_NOW }.revoke(session))
        assertEquals(requestCount, provider.requests.size)
    }

    @Test fun `an ID-token-only response without scope establishes identity with an empty grant report`() {
        val provider = SyntheticProvider(attempt()).apply { tokenValues.clear() }
        val session = provider.complete()
        assertEquals("subject-1", session.identity.subject)
        assertEquals(TEST_CLIENT, session.identity.clientId)
        assertTrue(session.tokens.grantedScopes.isEmpty())
        assertNull(session.tokens.accessToken)
        assertNull(session.tokens.refreshToken)
        assertNull(session.tokens.tokenType)
        assertEquals(TEST_NOW + 3600, session.tokens.expiresAtEpochSeconds)
    }

    @Test fun `callback scope cannot supply any grant when an ID-token-only response omits scope`() {
        val pending = attempt()
        val code = OpenAiSignIn.parseCallback(callback(pending,
            mapOf("scope" to "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")), pending, TEST_NOW)
        val provider = SyntheticProvider(pending).apply { tokenValues.clear() }
        val session = OpenAiIdentityClient(provider) { TEST_NOW }.complete(pending, code)
        assertEquals("subject-1", session.identity.subject)
        assertTrue(session.tokens.grantedScopes.isEmpty())
        assertEquals(3, provider.requests.size)
    }

    @Test fun `present malformed or insufficient scope reports are rejected`() {
        for (scope in listOf(null, 12, listOf("openid", "profile", "email"), "", "openid", "profile email",
            "openid profile email\n", "openid profile email \\", "openid profile email \"invalid\"",
            "openid profile email ${"x".repeat(4096)}")) {
            val provider = SyntheticProvider(attempt()).apply { tokenValues["scope"] = scope }
            if (scope == null) {
                // Preserve an explicit JSON null even if a serializer omits null map entries.
                provider.rawTokenBody = JSONObjectUtils.toJSONString(mapOf("id_token" to provider.signedToken()))
                    .removeSuffix("}") + ",\"scope\":null}"
            }
            expectFailure(SignInFailure.SCOPES_INVALID) { provider.complete() }
        }
    }

    @Test fun `session expiry follows the shorter access and ID-token lifetime`() {
        val provider = SyntheticProvider(attempt()).apply { tokenValues["expires_in"] = 60 }
        val session = provider.complete()
        assertEquals(TEST_NOW + 60, session.tokens.expiresAtEpochSeconds)
        assertEquals(TEST_NOW + 3600, session.identity.expiresAtEpochSeconds)
    }

    @Test fun `callback scope does not authorize missing identity permissions`() {
        val pending = attempt()
        val code = OpenAiSignIn.parseCallback(callback(pending, mapOf("scope" to "openid profile email chatgpt.tokens.use.direct")), pending, TEST_NOW)
        val provider = SyntheticProvider(pending).apply { tokenValues["scope"] = "openid email" }
        expectFailure(SignInFailure.SCOPES_INVALID) { OpenAiIdentityClient(provider) { TEST_NOW }.complete(pending, code) }
        val fresh = attempt()
        val goodCode = OpenAiSignIn.parseCallback(callback(fresh, mapOf("scope" to "chatgpt.tokens.use.direct")), fresh, TEST_NOW)
        val session = OpenAiIdentityClient(SyntheticProvider(fresh)) { TEST_NOW }.complete(fresh, goodCode)
        assertEquals(setOf("openid", "profile", "email"), session.tokens.grantedScopes)
    }

    @Test fun `unexpected token response types omissions and duplicate fields fail safely`() {
        val badBodies = listOf("{}", "{\"id_token\":12}", "{\"id_token\":\"synthetic-secret\",\"id_token\":\"other\"}",
            "{\"id_token\":\"synthetic-secret\",\"scope\":\"openid profile email\",\"sc\\u006fpe\":\"openid\"}")
        for (body in badBodies) {
            val provider = SyntheticProvider(attempt()).apply { rawTokenBody = body }
            val error = expectFailure(SignInFailure.TOKEN_RESPONSE_INVALID) { provider.complete() }
            assertFalse(error.toString().contains("synthetic-secret"))
        }
        for (field in listOf("access_token", "refresh_token", "token_type", "expires_in")) {
            val provider = SyntheticProvider(attempt()).apply { tokenValues[field] = false }
            expectFailure(SignInFailure.TOKEN_RESPONSE_INVALID) { provider.complete() }
        }
    }

    @Test fun `discovery cannot move any credential endpoint to another host or scheme`() {
        for (field in listOf("authorization_endpoint", "token_endpoint", "jwks_uri", "revocation_endpoint")) {
            for (malicious in listOf("https://evil.example/token", "http://auth.openai.com/token", "https://user@auth.openai.com/token",
                "https://auth.openai.com:8443/token", "https://auth.openai.com/token#fragment", "https://auth.openai.com/token?next=evil",
                "https://auth.openai.com/../token", "https://auth.openai.com/%2f/token")) {
                val provider = SyntheticProvider(attempt()).apply { metadata[field] = malicious }
                expectFailure(SignInFailure.PROVIDER_CONFIGURATION_INVALID) { provider.complete() }
                assertEquals(1, provider.requests.size)
            }
        }
        val provider = SyntheticProvider(attempt()).apply { metadata["issuer"] = "https://evil.example" }
        expectFailure(SignInFailure.PROVIDER_CONFIGURATION_INVALID) { provider.complete() }
    }

    @Test fun `redirects and token exchange failures never activate or expose provider text`() {
        for (status in listOf(302, 400, 401, 500)) {
            val provider = SyntheticProvider(attempt()).apply { tokenStatus = status; rawTokenBody = "synthetic-secret-server-error" }
            val error = expectFailure(SignInFailure.TOKEN_EXCHANGE_FAILED) { provider.complete() }
            assertFalse(error.toString().contains("synthetic-secret"))
            assertEquals(2, provider.requests.size)
        }
    }

    @Test fun `transport exceptions are sanitized and no throwable cause retains a token`() {
        val provider = SyntheticProvider(attempt()).apply { throwOnPath = "/api/accounts/oauth/token" }
        val error = expectFailure(SignInFailure.TRANSPORT_FAILURE) { provider.complete() }
        assertFalse(error.toString().contains("synthetic-secret"))
        assertNull(error.cause)
    }

    @Test fun `invalid signature unknown or ambiguous kid and private JWKS are rejected`() {
        val wrong = SyntheticProvider(attempt()).apply { signatureKey = SigningKeys.otherRsa }
        expectFailure(SignInFailure.IDENTITY_INVALID) { wrong.complete() }
        val unknown = SyntheticProvider(attempt()).apply {
            header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID("unknown").build()
        }
        expectFailure(SignInFailure.IDENTITY_INVALID) { unknown.complete() }
        val ambiguous = SyntheticProvider(attempt()).apply {
            val second = RSAKey.Builder(SigningKeys.otherRsa.toRSAPublicKey()).keyID(key.keyID).build()
            rawJwks = JWKSet(listOf(key.toPublicJWK(), second)).toString()
        }
        expectFailure(SignInFailure.IDENTITY_INVALID) { ambiguous.complete() }
        val privateKey = SyntheticProvider(attempt()).apply {
            rawJwks = JSONObjectUtils.toJSONString(JWKSet(key).toJSONObject(false))
        }
        expectFailure(SignInFailure.IDENTITY_INVALID) { privateKey.complete() }
    }

    @Test fun `unadvertised algorithms HMAC and unsigned tokens are rejected`() {
        val ec = SyntheticProvider(attempt()).apply {
            key = SigningKeys.ec
            metadata["id_token_signing_alg_values_supported"] = listOf("RS256")
        }
        expectFailure(SignInFailure.IDENTITY_INVALID) { ec.complete() }
        val hmac = SyntheticProvider(attempt())
        val signed = JWSObject(JWSHeader.Builder(JWSAlgorithm.HS256).keyID("synthetic-rsa").build(),
            Payload(JSONObjectUtils.toJSONString(hmac.claims)))
        signed.sign(MACSigner(ByteArray(32) { 7 }))
        hmac.rawIdToken = signed.serialize()
        expectFailure(SignInFailure.IDENTITY_INVALID) { hmac.complete() }
        val plain = SyntheticProvider(attempt()).apply { rawIdToken = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJzdWJqZWN0LTEifQ." }
        expectFailure(SignInFailure.IDENTITY_INVALID) { plain.complete() }
        val noSecureAlg = SyntheticProvider(attempt()).apply { metadata["id_token_signing_alg_values_supported"] = listOf("none", "HS256") }
        expectFailure(SignInFailure.PROVIDER_CONFIGURATION_INVALID) { noSecureAlg.complete() }
    }

    @Test fun `key URL embedded key and critical header injection cannot influence verification`() {
        val headers = listOf(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID("synthetic-rsa").jwkURL(URI("https://evil.example/key")).build(),
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID("synthetic-rsa").x509CertURL(URI("https://evil.example/cert")).build(),
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID("synthetic-rsa").jwk(SigningKeys.rsa.toPublicJWK()).build(),
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID("synthetic-rsa").criticalParams(setOf("synthetic")).customParam("synthetic", true).build()
        )
        for (maliciousHeader in headers) {
            val provider = SyntheticProvider(attempt()).apply { header = maliciousHeader }
            expectFailure(SignInFailure.IDENTITY_INVALID) { provider.complete() }
        }
    }

    @Test fun `wrong issuer audience nonce subject and invalid time claims fail verification`() {
        val invalidClaims = listOf("iss" to "https://evil.example", "aud" to "oaiapp_other", "nonce" to "wrong",
            "sub" to "other-subject", "exp" to TEST_NOW - 60, "iat" to TEST_NOW + 60,
            "iat" to TEST_NOW - 60, "nbf" to TEST_NOW + 60, "iat" to null, "exp" to "not-a-number",
            "exp" to (TEST_NOW + 60.5), "sub" to "", "aud" to listOf(TEST_CLIENT, TEST_CLIENT),
            "email" to mapOf("address" to "person@example.test"))
        for ((claim, value) in invalidClaims) {
            val provider = SyntheticProvider(attempt(TEST_CLIENT, "subject-1")).apply {
                if (value == null) claims.remove(claim) else claims[claim] = value
            }
            expectFailure(SignInFailure.IDENTITY_INVALID) { provider.complete() }
        }
    }

    @Test fun `multiple audiences require matching authorized party even when expected audience exists`() {
        for (azp in listOf<String?>(null, "oaiapp_other")) {
            val provider = SyntheticProvider(attempt()).apply {
                claims["aud"] = listOf(TEST_CLIENT, "oaiapp_other")
                if (azp != null) claims["azp"] = azp
            }
            expectFailure(SignInFailure.IDENTITY_INVALID) { provider.complete() }
        }
        val good = SyntheticProvider(attempt()).apply {
            claims["aud"] = listOf(TEST_CLIENT, "oaiapp_other")
            claims["azp"] = TEST_CLIENT
        }
        assertEquals("subject-1", good.complete().identity.subject)
        val wrongSingleParty = SyntheticProvider(attempt()).apply { claims["azp"] = "oaiapp_other" }
        expectFailure(SignInFailure.IDENTITY_INVALID) { wrongSingleParty.complete() }
    }

    @Test fun `unusual key use and undersized RSA keys fail before account activation`() {
        val encryption = SyntheticProvider(attempt()).apply {
            rawJwks = JWKSet(RSAKey.Builder(SigningKeys.rsa.toRSAPublicKey()).keyID(key.keyID).keyUse(KeyUse.ENCRYPTION).build()).toString()
        }
        expectFailure(SignInFailure.IDENTITY_INVALID) { encryption.complete() }
        val small = SyntheticProvider(attempt()).apply {
            val generator = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
            val publicKey = generator.generateKeyPair().public as java.security.interfaces.RSAPublicKey
            rawJwks = JWKSet(RSAKey.Builder(publicKey).keyID(key.keyID).build()).toString()
        }
        expectFailure(SignInFailure.IDENTITY_INVALID) { small.complete() }
    }

    @Test fun `an accepted code is bound to one attempt and one exchange`() {
        val pending = attempt()
        val code = OpenAiSignIn.parseCallback(callback(pending), pending, TEST_NOW)
        val wrongAttempt = attempt()
        val provider = SyntheticProvider(pending)
        val client = OpenAiIdentityClient(provider) { TEST_NOW }
        expectFailure(SignInFailure.INVALID_CALLBACK) { client.complete(wrongAttempt, code) }
        assertTrue(provider.requests.isEmpty())
        client.complete(pending, code)
        val size = provider.requests.size
        expectFailure(SignInFailure.ATTEMPT_ALREADY_FINISHED) { client.complete(pending, code) }
        assertEquals(size, provider.requests.size)
    }

    @Test fun `cancellation during exchange prevents account activation`() {
        val pending = attempt()
        val provider = SyntheticProvider(pending).apply {
            onRequest = { if (it.uri.path == "/.well-known/jwks.json") pending.cancel() }
        }
        expectFailure(SignInFailure.CANCELLED) { provider.complete() }
    }

    @Test fun `cancellation during discovery prevents token exchange`() {
        val pending = attempt()
        val provider = SyntheticProvider(pending).apply {
            onRequest = { if (it.uri.path == "/.well-known/openid-configuration") pending.cancel() }
        }
        expectFailure(SignInFailure.CANCELLED) { provider.complete() }
        assertEquals(listOf("/.well-known/openid-configuration"), provider.requests.map { it.uri.path })
        assertTrue(provider.requests.none { it.method == "POST" })
    }

    @Test fun `cancellation during token exchange prevents JWKS fetch and activation`() {
        val pending = attempt()
        val provider = SyntheticProvider(pending).apply {
            onRequest = { if (it.uri.path == "/api/accounts/oauth/token") pending.cancel() }
        }
        expectFailure(SignInFailure.CANCELLED) { provider.complete() }
        assertEquals(listOf("/.well-known/openid-configuration", "/api/accounts/oauth/token"), provider.requests.map { it.uri.path })
        assertTrue(provider.requests.none { it.uri.path == "/.well-known/jwks.json" })
    }

    @Test fun `expiry during discovery prevents token exchange`() {
        val pending = attempt()
        var now = TEST_NOW
        val provider = SyntheticProvider(pending).apply {
            onRequest = { if (it.uri.path == "/.well-known/openid-configuration") now = TEST_NOW + 300 }
        }
        val code = OpenAiSignIn.parseCallback(callback(pending), pending, TEST_NOW)
        expectFailure(SignInFailure.EXPIRED_ATTEMPT) { OpenAiIdentityClient(provider) { now }.complete(pending, code) }
        assertEquals(listOf("/.well-known/openid-configuration"), provider.requests.map { it.uri.path })
    }

    @Test fun `revocation uses discovery issued ID and refresh token with proper hint`() {
        val provider = SyntheticProvider(attempt())
        val session = provider.complete()
        assertTrue(OpenAiIdentityClient(provider) { TEST_NOW }.revoke(session))
        val revoke = provider.requests.last()
        assertEquals("${OpenAiSignIn.ISSUER}/api/accounts/oauth/revoke", revoke.uri.toString())
        assertEquals(mapOf("token" to "synthetic-refresh", "token_type_hint" to "refresh_token", "client_id" to TEST_CLIENT), decodeQuery(revoke.body!!))
        assertEquals("synthetic-refresh", session.tokens.refreshToken)
    }

    @Test fun `revocation falls back to access token and reports unconfirmed failure`() {
        val provider = SyntheticProvider(attempt()).apply { tokenValues.remove("refresh_token") }
        val session = provider.complete()
        val client = OpenAiIdentityClient(provider) { TEST_NOW }
        assertTrue(client.revoke(session))
        assertEquals("access_token", decodeQuery(provider.requests.last().body!!)["token_type_hint"])
        provider.revokeStatus = 500
        assertFalse(client.revoke(session))
        provider.revokeStatus = 302
        assertFalse(client.revoke(session))
        provider.throwOnPath = "/api/accounts/oauth/revoke"
        assertFalse(client.revoke(session))
        provider.throwOnPath = null
        provider.metadata["revocation_endpoint"] = "https://evil.example/revoke"
        val count = provider.requests.size
        assertFalse(client.revoke(session))
        assertEquals(count + 1, provider.requests.size)
    }
}

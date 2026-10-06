package dev.dotcompanion.identity

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyOperation
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Identity only: this client never calls an API resource, refreshes, or opens authorization. */
class OpenAiIdentityClient(
    private val transport: OAuthHttpTransport = BoundedHttpsTransport(),
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    fun complete(attempt: SignInAttempt, code: AuthorizationCode): SignInSession {
        attempt.beginExchange(code, nowEpochSeconds())
        try {
            val discovery = discover()
            attempt.assertFresh(nowEpochSeconds())
            val exchange = OAuthHttpRequest(discovery.tokenEndpoint, "POST", encodeForm(linkedMapOf(
                "grant_type" to "authorization_code",
                "client_id" to code.clientId,
                "code" to code.code,
                "code_verifier" to attempt.verifier,
                "redirect_uri" to attempt.redirectUri.toASCIIString(),
                "resource" to OpenAiSignIn.RESOURCE
            )))
            attempt.assertFresh(nowEpochSeconds())
            val response = request(exchange)
            attempt.assertFresh(nowEpochSeconds())
            if (response.statusCode != 200) fail(SignInFailure.TOKEN_EXCHANGE_FAILED)
            val tokens = jsonObject(response.body, SignInFailure.TOKEN_RESPONSE_INVALID)
            val idToken = requiredSecret(tokens, "id_token", 262_144)
            val receivedAt = nowEpochSeconds()
            attempt.assertFresh(receivedAt)
            val jwks = request(OAuthHttpRequest(discovery.jwksUri))
            attempt.assertFresh(nowEpochSeconds())
            if (jwks.statusCode != 200) fail(SignInFailure.IDENTITY_INVALID)
            val identity = verifyIdentity(idToken, jwks.body, discovery, attempt, code.clientId, nowEpochSeconds())
            attempt.assertFresh(nowEpochSeconds())
            val scopes = grantedScopes(tokens)
            val accessToken = optionalSecret(tokens, "access_token", 65_536)
            val refreshToken = optionalSecret(tokens, "refresh_token", 65_536)
            val tokenType = optionalSecret(tokens, "token_type", 32)
            if ((accessToken != null && !tokenType.equals("Bearer", ignoreCase = true)) ||
                (refreshToken != null && accessToken == null) ||
                (tokenType != null && !tokenType.equals("Bearer", ignoreCase = true))) {
                fail(SignInFailure.TOKEN_RESPONSE_INVALID)
            }
            val accessExpires = if (accessToken != null) {
                val lifetime = integer(tokens["expires_in"], SignInFailure.TOKEN_RESPONSE_INVALID)
                if (lifetime !in 1..31_536_000 || receivedAt > Long.MAX_VALUE - lifetime) fail(SignInFailure.TOKEN_RESPONSE_INVALID)
                receivedAt + lifetime
            } else identity.expiresAtEpochSeconds
            val session = SignInSession(identity, OAuthTokens(accessToken, idToken, refreshToken, scopes,
                minOf(accessExpires, identity.expiresAtEpochSeconds), tokenType?.let { "Bearer" }))
            // Cancellation during any network/verification step still prevents activation.
            val activationTime = nowEpochSeconds()
            attempt.assertFresh(activationTime)
            if (session.tokens.expiresAtEpochSeconds <= activationTime) fail(SignInFailure.IDENTITY_INVALID)
            attempt.finishExchange(activationTime)
            return session
        } catch (failure: SignInException) {
            attempt.failExchange()
            throw failure
        } catch (_: Exception) {
            attempt.failExchange()
            fail(SignInFailure.IDENTITY_INVALID)
        }
    }

    /** Caller clears local credentials even when remote revocation cannot be confirmed. */
    fun revoke(session: SignInSession): Boolean {
        return try {
            if (session.identity.issuer != OpenAiSignIn.ISSUER || !OpenAiSignIn.validClientId(session.identity.clientId)) return false
            val token = session.tokens.refreshToken ?: session.tokens.accessToken ?: return false
            if (!opaqueValue(token, 65_536)) return false
            val endpoint = discover().revocationEndpoint ?: return false
            val response = request(OAuthHttpRequest(endpoint, "POST", encodeForm(linkedMapOf(
                "token" to token,
                "token_type_hint" to if (session.tokens.refreshToken != null) "refresh_token" else "access_token",
                "client_id" to session.identity.clientId
            ))))
            response.statusCode == 200
        } catch (_: Exception) {
            false
        }
    }

    private class Discovery(
        val tokenEndpoint: URI,
        val jwksUri: URI,
        val revocationEndpoint: URI?,
        val signingAlgorithms: Set<String>
    )

    private fun discover(): Discovery {
        val response = request(OAuthHttpRequest(URI("${OpenAiSignIn.ISSUER}/.well-known/openid-configuration")))
        if (response.statusCode != 200) fail(SignInFailure.PROVIDER_CONFIGURATION_INVALID)
        val metadata = jsonObject(response.body, SignInFailure.PROVIDER_CONFIGURATION_INVALID)
        if (metadata["issuer"] != OpenAiSignIn.ISSUER) fail(SignInFailure.PROVIDER_CONFIGURATION_INVALID)
        endpoint(metadata["authorization_endpoint"])
        val token = endpoint(metadata["token_endpoint"])
        val jwks = endpoint(metadata["jwks_uri"])
        val revoke = metadata["revocation_endpoint"]?.let(::endpoint)
        val advertised = metadata["id_token_signing_alg_values_supported"] as? List<*>
            ?: fail(SignInFailure.PROVIDER_CONFIGURATION_INVALID)
        if (advertised.size > 32 || advertised.any { it !is String }) fail(SignInFailure.PROVIDER_CONFIGURATION_INVALID)
        val algorithms = advertised.filterIsInstance<String>().toSet().intersect(setOf("RS256", "ES256"))
        if (algorithms.isEmpty()) fail(SignInFailure.PROVIDER_CONFIGURATION_INVALID)
        return Discovery(token, jwks, revoke, algorithms)
    }

    private fun endpoint(value: Any?): URI {
        val uri = try { URI(value as? String ?: fail(SignInFailure.PROVIDER_CONFIGURATION_INVALID)) }
        catch (_: Exception) { fail(SignInFailure.PROVIDER_CONFIGURATION_INVALID) }
        requireAuthEndpoint(uri)
        return uri
    }

    private fun request(request: OAuthHttpRequest): OAuthHttpResponse {
        // Keep endpoint validation even when the caller injects another transport.
        requireAuthEndpoint(request.uri)
        return try {
            transport.execute(request).also {
                if (it.body.toByteArray(StandardCharsets.UTF_8).size > 1_048_576) fail(SignInFailure.TRANSPORT_FAILURE)
            }
        } catch (_: Exception) {
            fail(SignInFailure.TRANSPORT_FAILURE)
        }
    }

    private fun verifyIdentity(
        idToken: String,
        jwksJson: String,
        discovery: Discovery,
        attempt: SignInAttempt,
        clientId: String,
        now: Long
    ): AccountIdentity {
        try {
            val parts = idToken.split('.')
            if (parts.size != 3 || parts.any { it.isEmpty() || !it.matches(Regex("[A-Za-z0-9_-]+")) }) {
                fail(SignInFailure.IDENTITY_INVALID)
            }
            val headerObject = SecurityJson.objectValue(decodeJwtPart(parts[0]))
            val claims = SecurityJson.objectValue(decodeJwtPart(parts[1]))
            if (headerObject.keys.any { it in setOf("jku", "jwk", "x5u", "x5c") }) fail(SignInFailure.IDENTITY_INVALID)
            val jwt = SignedJWT.parse(idToken)
            val header = jwt.header
            val algorithm = header.algorithm.name
            if (algorithm !in discovery.signingAlgorithms || algorithm !in setOf("RS256", "ES256") ||
                !header.criticalParams.isNullOrEmpty() || !header.isBase64URLEncodePayload ||
                (header.type != null && header.type.toString() != "JWT")) fail(SignInFailure.IDENTITY_INVALID)
            val kid = header.keyID?.takeIf { opaqueValue(it, 256) } ?: fail(SignInFailure.IDENTITY_INVALID)
            val published = SecurityJson.objectValue(jwksJson)["keys"] as? List<*>
                ?: fail(SignInFailure.IDENTITY_INVALID)
            if (published.size !in 1..32) fail(SignInFailure.IDENTITY_INVALID)
            val allKeys = JWKSet.parse(jwksJson).keys
            val keys = allKeys.filter { it.keyID == kid }
            // A kid must identify exactly one public key, even across different key types.
            if (keys.size != 1) fail(SignInFailure.IDENTITY_INVALID)
            val key = keys.single()
            if (key.isPrivate || (key.algorithm != null && key.algorithm.name != algorithm) ||
                (key.keyUse != null && key.keyUse != KeyUse.SIGNATURE) ||
                (!key.keyOperations.isNullOrEmpty() && KeyOperation.VERIFY !in key.keyOperations)) {
                fail(SignInFailure.IDENTITY_INVALID)
            }
            val verified = when {
                algorithm == JWSAlgorithm.RS256.name && key is RSAKey -> {
                    // Check encoded bounds before allocating oversized RSA integers.
                    if (key.modulus.toString().length > 1366 || key.publicExponent.toString().length > 6) {
                        fail(SignInFailure.IDENTITY_INVALID)
                    }
                    val rsa = key.toRSAPublicKey()
                    if (rsa.modulus.bitLength() !in 2048..8192 || rsa.publicExponent.bitLength() > 32 ||
                        rsa.publicExponent < java.math.BigInteger.valueOf(65537)) {
                        fail(SignInFailure.IDENTITY_INVALID)
                    }
                    jwt.verify(RSASSAVerifier(rsa))
                }
                algorithm == JWSAlgorithm.ES256.name && key is ECKey && key.curve == Curve.P_256 ->
                    jwt.verify(ECDSAVerifier(key.toECPublicKey()))
                else -> false
            }
            if (!verified || claims["iss"] != OpenAiSignIn.ISSUER) fail(SignInFailure.IDENTITY_INVALID)
            val audience = when (val aud = claims["aud"]) {
                is String -> listOf(aud)
                is List<*> -> {
                    if (aud.any { it !is String }) fail(SignInFailure.IDENTITY_INVALID)
                    aud.filterIsInstance<String>()
                }
                else -> fail(SignInFailure.IDENTITY_INVALID)
            }
            if (audience.isEmpty() || audience.size > 16 || audience.distinct().size != audience.size ||
                clientId !in audience || audience.any { !opaqueValue(it, 256) }) fail(SignInFailure.IDENTITY_INVALID)
            val authorizedParty = claims["azp"]
            if ((audience.size > 1 && authorizedParty != clientId) ||
                (authorizedParty != null && authorizedParty != clientId)) fail(SignInFailure.IDENTITY_INVALID)
            val subject = (claims["sub"] as? String)?.takeIf { opaqueValue(it, 1024) }
                ?: fail(SignInFailure.IDENTITY_INVALID)
            if (attempt.expectedSubject != null && !constantTimeEquals(subject, attempt.expectedSubject)) fail(SignInFailure.IDENTITY_INVALID)
            val nonce = claims["nonce"] as? String ?: fail(SignInFailure.IDENTITY_INVALID)
            if (!constantTimeEquals(nonce, attempt.nonce)) fail(SignInFailure.IDENTITY_INVALID)
            val expiresAt = integer(claims["exp"], SignInFailure.IDENTITY_INVALID)
            val issuedAt = integer(claims["iat"], SignInFailure.IDENTITY_INVALID)
            val notBefore = claims["nbf"]?.let { integer(it, SignInFailure.IDENTITY_INVALID) }
            if (now < 0 || expiresAt <= now - CLOCK_TOLERANCE_SECONDS || expiresAt <= issuedAt ||
                issuedAt > now + CLOCK_TOLERANCE_SECONDS || issuedAt < attempt.startedAtEpochSeconds - CLOCK_TOLERANCE_SECONDS ||
                (notBefore != null && (notBefore > now + CLOCK_TOLERANCE_SECONDS || notBefore >= expiresAt))) {
                fail(SignInFailure.IDENTITY_INVALID)
            }
            return AccountIdentity(OpenAiSignIn.ISSUER, subject, clientId,
                optionalProfile(claims, "email", 320), optionalProfile(claims, "name", 512), expiresAt)
        } catch (_: Exception) {
            fail(SignInFailure.IDENTITY_INVALID)
        }
    }

    /**
     * Standard identity-only sign-in may return only id_token, without scope:
     * https://developers.openai.com/siwc/website#validate-the-token-response
     * An absent grant report stays empty; neither requested nor callback scopes supply permissions.
     * Present scope values still need to report the identity permissions this attempt requested.
     */
    private fun grantedScopes(tokens: Map<String, Any?>): Set<String> {
        if (!tokens.containsKey("scope")) return emptySet()
        val value = tokens["scope"] as? String ?: fail(SignInFailure.SCOPES_INVALID)
        if (value.length > 4096 || value.any { it.code !in 32..126 || it == '"' || it == '\\' }) {
            fail(SignInFailure.SCOPES_INVALID)
        }
        val scopes = value.split(' ').filter(String::isNotEmpty).toSet()
        if (scopes.size > 32 || scopes.any { it.length > 256 } || !scopes.containsAll(OpenAiSignIn.REQUIRED_SCOPES)) {
            fail(SignInFailure.SCOPES_INVALID)
        }
        return scopes
    }

    private fun requiredSecret(values: Map<String, Any?>, key: String, limit: Int): String =
        optionalSecret(values, key, limit) ?: fail(SignInFailure.TOKEN_RESPONSE_INVALID)

    private fun optionalSecret(values: Map<String, Any?>, key: String, limit: Int): String? {
        val value = values[key] ?: return null
        return (value as? String)?.takeIf { opaqueValue(it, limit) } ?: fail(SignInFailure.TOKEN_RESPONSE_INVALID)
    }

    private fun optionalProfile(values: Map<String, Any?>, key: String, limit: Int): String? {
        val value = values[key] ?: return null
        if (value !is String || value.length > limit || value.any { it.isISOControl() }) fail(SignInFailure.IDENTITY_INVALID)
        return value.takeIf(String::isNotBlank)
    }

    private fun integer(value: Any?, reason: SignInFailure): Long {
        val number = value as? Number ?: fail(reason)
        return number.toString().toLongOrNull()?.takeIf { it in 0..253_402_300_799L } ?: fail(reason)
    }

    private fun decodeJwtPart(value: String): String {
        val bytes = Base64.getUrlDecoder().decode(value)
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun jsonObject(value: String, reason: SignInFailure): Map<String, Any?> = try {
        SecurityJson.objectValue(value)
    } catch (_: Exception) {
        fail(reason)
    }

    private companion object { const val CLOCK_TOLERANCE_SECONDS = 5L }
}

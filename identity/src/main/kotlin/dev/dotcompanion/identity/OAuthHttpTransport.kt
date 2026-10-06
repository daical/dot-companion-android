package dev.dotcompanion.identity

import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class OAuthHttpRequest(val uri: URI, val method: String = "GET", val body: String? = null) {
    override fun toString(): String = "OAuthHttpRequest([redacted])"
}

class OAuthHttpResponse(val statusCode: Int, val body: String) {
    override fun toString(): String = "OAuthHttpResponse(statusCode=$statusCode, body=[redacted])"
}

/** Implementations must not log the request or response. Tests inject only synthetic transport. */
fun interface OAuthHttpTransport {
    fun execute(request: OAuthHttpRequest): OAuthHttpResponse
}

class BoundedHttpsTransport(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 10_000,
    private val maxResponseBytes: Int = 1_048_576,
    private val connectionFactory: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection }
) : OAuthHttpTransport {
    init {
        require(connectTimeoutMillis in 1..15_000 && readTimeoutMillis in 1..15_000)
        require(maxResponseBytes in 1..1_048_576)
    }

    override fun execute(request: OAuthHttpRequest): OAuthHttpResponse {
        var connection: HttpURLConnection? = null
        try {
            requireAuthEndpoint(request.uri)
            if (request.method !in setOf("GET", "POST") ||
                (request.method == "GET" && request.body != null) ||
                (request.method == "POST" && request.body == null)) fail(SignInFailure.TRANSPORT_FAILURE)
            val payload = request.body?.toByteArray(StandardCharsets.UTF_8)
            if (payload != null && payload.size > 65_536) fail(SignInFailure.TRANSPORT_FAILURE)
            val deadline = System.nanoTime() + (connectTimeoutMillis.toLong() + readTimeoutMillis) * 1_000_000
            connection = connectionFactory(request.uri)
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.requestMethod = request.method
            connection.setRequestProperty("Accept", "application/json")
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
            }
            val status = connection.responseCode
            // Redirects and error bodies are not followed or retained.
            if (status !in 200..299) return OAuthHttpResponse(status, "")
            if (connection.contentLengthLong > maxResponseBytes) fail(SignInFailure.TRANSPORT_FAILURE)
            val output = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val remaining = (deadline - System.nanoTime()) / 1_000_000
                    if (remaining <= 0) fail(SignInFailure.TRANSPORT_FAILURE)
                    connection.readTimeout = minOf(readTimeoutMillis.toLong(), remaining).toInt().coerceAtLeast(1)
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (output.size().toLong() + count > maxResponseBytes) fail(SignInFailure.TRANSPORT_FAILURE)
                    output.write(buffer, 0, count)
                }
            }
            val body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
            return OAuthHttpResponse(status, body)
        } catch (_: Exception) {
            fail(SignInFailure.TRANSPORT_FAILURE)
        } finally {
            connection?.disconnect()
        }
    }
}

/** Only the fixed production issuer may receive codes, verifiers, or tokens. */
internal fun requireAuthEndpoint(uri: URI) {
    if (uri.scheme != "https" || uri.host != "auth.openai.com" ||
        uri.rawAuthority !in setOf("auth.openai.com", "auth.openai.com:443") ||
        uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null || uri.isOpaque ||
        uri.rawPath.isNullOrEmpty() || !uri.rawPath.matches(Regex("/[A-Za-z0-9/_.-]+")) ||
        uri.normalize() != uri || uri.rawPath.startsWith("//") ||
        uri.rawPath.split('/').any { it == "." || it == ".." }) {
        fail(SignInFailure.PROVIDER_CONFIGURATION_INVALID)
    }
}

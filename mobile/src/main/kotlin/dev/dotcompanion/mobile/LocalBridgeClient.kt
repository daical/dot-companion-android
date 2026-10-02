package dev.dotcompanion.mobile

import dev.dotcompanion.core.CompanionMessage
import dev.dotcompanion.core.CompanionQueue
import dev.dotcompanion.core.MessageStatus
import dev.dotcompanion.shared.readBounded
import dev.dotcompanion.shared.parseJsonObject
import dev.dotcompanion.shared.requiredString
import dev.dotcompanion.shared.requiredBoolean
import dev.dotcompanion.shared.nullableString
import java.net.HttpURLConnection
import java.net.URI
import org.json.JSONObject

/** Development transport only. No production login or native dot API is configured. */
interface BridgeTransport {
    suspend fun verifyStatus()
    suspend fun submit(message: CompanionMessage)
    suspend fun messages(): List<CompanionMessage>
}

class LocalBridgeClient(endpoint: String, private val token: String, allowEmulatorHttp: Boolean,
    private val connectionFactory: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
) : BridgeTransport {
    private val base = validateEndpoint(endpoint, allowEmulatorHttp)

    init {
        require(token.length in 16..4096 && token.all { it.code in 33..126 }) { "Enter a valid development token" }
    }

    override suspend fun verifyStatus() {
        val json = request("GET", "/v1/status", maximum = 16 * 1024)
        require(json.requiredString("mode") == "local_development" && !json.requiredBoolean("liveDotConnected")) {
            "This client supports only the local development harness"
        }
    }

    override suspend fun submit(message: CompanionMessage) {
        CompanionQueue.validate(message)
        val json = request("POST", "/v1/messages", JSONObject().put("requestId", message.requestId)
            .put("conversationId", message.conversationId).put("text", message.text).put("createdAt", message.createdAt), 16 * 1024)
        require(json.requiredString("requestId") == message.requestId && json.requiredString("status") in setOf("queued", "awaiting_reply", "replied", "failed")) {
            "Bridge acknowledgement did not match the request"
        }
    }

    override suspend fun messages(): List<CompanionMessage> {
        val array = request("GET", "/v1/messages?conversationId=${CompanionQueue.CONVERSATION_ID}", maximum = 12 * 1024 * 1024)
            .getJSONArray("messages")
        require(array.length() <= CompanionQueue.MAX_MESSAGES) { "Bridge history exceeds the local bound" }
        val parsed = (0 until array.length()).map {
            val json = array.getJSONObject(it)
            val status = when (json.requiredString("status")) {
                "queued" -> MessageStatus.QUEUED
                "awaiting_reply" -> MessageStatus.AWAITING_REPLY
                "replied" -> MessageStatus.REPLIED
                "failed" -> MessageStatus.FAILED
                else -> throw IllegalArgumentException("Unknown bridge delivery state")
            }
            val reply = json.nullableString("reply")
            require((status == MessageStatus.REPLIED) == (reply != null))
            if (reply != null) require(reply.isNotBlank() && reply.toByteArray(Charsets.UTF_8).size <= CompanionQueue.MAX_TEXT_BYTES)
            CompanionMessage(json.requiredString("requestId"), json.requiredString("conversationId"), json.requiredString("text"),
                json.requiredString("createdAt"), status, reply).also(CompanionQueue::validate)
        }
        require(parsed.map { it.requestId }.toSet().size == parsed.size) { "Duplicate bridge requests" }
        return parsed
    }

    private fun request(method: String, path: String, body: JSONObject? = null, maximum: Int): JSONObject {
        val connection = connectionFactory(base.resolve(path))
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            require(connection.responseCode in 200..299) { "Local bridge rejected the request (${connection.responseCode})" }
            require(connection.contentType?.substringBefore(';')?.trim()?.lowercase() == "application/json") { "Unexpected bridge response type" }
            return connection.inputStream.use { parseJsonObject(readBounded(it, maximum)) }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun validateEndpoint(endpoint: String, allowEmulatorHttp: Boolean): URI {
            val uri = URI(endpoint.trim())
            require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) { "Use an endpoint without credentials, query or fragment" }
            require(uri.path.isNullOrEmpty() || uri.path == "/") { "Use the endpoint's base URL" }
            require(uri.port == -1 || uri.port in 1..65535) { "Invalid endpoint port" }
            require(uri.scheme == "https" || (allowEmulatorHttp && uri.scheme == "http" && uri.host == "10.0.2.2")) {
                "HTTPS is required; debug HTTP is limited to 10.0.2.2"
            }
            return uri
        }
    }
}

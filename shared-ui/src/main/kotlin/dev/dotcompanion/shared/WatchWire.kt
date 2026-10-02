package dev.dotcompanion.shared

import dev.dotcompanion.core.CompanionMessage
import dev.dotcompanion.core.ConnectionMode
import dev.dotcompanion.core.MessageStatus
import dev.dotcompanion.core.ReplyProvenance
import dev.dotcompanion.core.WatchEnvelope
import dev.dotcompanion.core.CompanionQueue
import dev.dotcompanion.core.FailureKind
import java.util.UUID
import org.json.JSONObject

data class PhoneReceipt(val requestId: String)
data class PhoneMessageState(
    val requestId: String,
    val status: MessageStatus,
    val reply: String?,
    val replyProvenance: ReplyProvenance?,
    val mode: ConnectionMode,
    val online: Boolean,
    val failureKind: FailureKind?,
)

/** Tiny allowlisted protocol. Bearer tokens, endpoint URLs and private context are never fields. */
object WatchWire {
    const val REQUEST_PATH = "/dot-companion/v1/request"
    const val ACK_PATH = "/dot-companion/v1/ack"
    const val STATE_PATH = "/dot-companion/v1/state"
    const val PHONE_CAPABILITY = "dot_companion_phone_v1"
    const val WATCH_CAPABILITY = "dot_companion_watch_v1"
    const val MAX_PACKET_BYTES = 64 * 1024

    fun request(message: CompanionMessage): ByteArray {
        CompanionQueue.validate(message)
        return bytes(JSONObject().put("schemaVersion", 1).put("requestId", message.requestId)
            .put("text", message.text).put("createdAt", message.createdAt))
    }

    fun decodeRequest(data: ByteArray): WatchEnvelope {
        val json = parse(data, setOf("schemaVersion", "requestId", "text", "createdAt"))
        return WatchEnvelope(json.requiredInt("schemaVersion"), json.requiredString("requestId"), json.requiredString("text"), json.requiredString("createdAt"))
            .also { it.asMessage() }
    }

    fun receipt(requestId: String): ByteArray {
        validId(requestId)
        return bytes(JSONObject().put("schemaVersion", 1).put("requestId", requestId).put("phase", "durably_queued"))
    }

    fun decodeReceipt(data: ByteArray): PhoneReceipt {
        val json = parse(data, setOf("schemaVersion", "requestId", "phase"))
        require(json.requiredString("phase") == "durably_queued")
        return PhoneReceipt(json.requiredString("requestId").also(::validId))
    }

    fun state(message: CompanionMessage, mode: ConnectionMode, online: Boolean): ByteArray = bytes(JSONObject()
        .put("schemaVersion", 1).put("requestId", message.requestId).put("status", message.status.name)
        .put("reply", message.reply ?: JSONObject.NULL).put("replyProvenance", message.replyProvenance?.name ?: JSONObject.NULL)
        .put("mode", mode.name).put("online", online).put("failureKind", message.failureKind?.name ?: JSONObject.NULL))

    fun decodeState(data: ByteArray): PhoneMessageState {
        val json = parse(data, setOf("schemaVersion", "requestId", "status", "reply", "replyProvenance", "mode", "online", "failureKind"))
        val status = MessageStatus.valueOf(json.requiredString("status"))
        val reply = json.nullableString("reply")
        val provenance = json.nullableString("replyProvenance")?.let(ReplyProvenance::valueOf)
        val failureKind = json.nullableString("failureKind")?.let(FailureKind::valueOf)
        require(if (status == MessageStatus.REPLIED) reply != null && provenance != null else reply == null && provenance == null)
        require((status == MessageStatus.FAILED) == (failureKind != null))
        if (reply != null) require(reply.isNotBlank() && reply.toByteArray(Charsets.UTF_8).size <= CompanionQueue.MAX_TEXT_BYTES)
        return PhoneMessageState(json.requiredString("requestId").also(::validId), status, reply, provenance,
            ConnectionMode.valueOf(json.requiredString("mode")), json.requiredBoolean("online"), failureKind)
    }

    private fun parse(data: ByteArray, fields: Set<String>): JSONObject {
        require(data.size <= MAX_PACKET_BYTES) { "Watch packet is too large" }
        return parseJsonObject(data).also {
            require(it.keys().asSequence().toSet() == fields) { "Unexpected watch protocol fields" }
            require(it.requiredInt("schemaVersion") == 1) { "Unsupported watch protocol" }
        }
    }

    private fun bytes(json: JSONObject) = json.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_PACKET_BYTES) }
    private fun validId(id: String) { require(UUID.fromString(id).toString() == id) { "Invalid request ID" } }
}

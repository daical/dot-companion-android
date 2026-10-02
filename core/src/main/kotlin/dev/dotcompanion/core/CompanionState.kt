package dev.dotcompanion.core

import java.time.Instant
import java.util.UUID

enum class ConnectionMode { LOCAL_PREVIEW, DISCONNECTED, LOCAL_BRIDGE }
enum class MessageStatus { QUEUED, SENDING, AWAITING_REPLY, REPLIED, FAILED }
enum class MessageSource { PHONE, WATCH }
enum class ReplyProvenance { LOCAL_SYNTHETIC, LOCAL_BRIDGE }
enum class FailureKind { TRANSPORT, REMOTE }

data class CompanionMessage(
    val requestId: String,
    val conversationId: String,
    val text: String,
    val createdAt: String,
    val status: MessageStatus = MessageStatus.QUEUED,
    val reply: String? = null,
    val attempts: Int = 0,
    val nextAttemptAtMillis: Long = 0,
    val source: MessageSource = MessageSource.PHONE,
    val replyProvenance: ReplyProvenance? = null,
    val failureKind: FailureKind? = null,
)

data class CompanionState(
    val mode: ConnectionMode = ConnectionMode.LOCAL_PREVIEW,
    val messages: List<CompanionMessage> = emptyList(),
    val online: Boolean = true,
) {
    // A successful local HTTP request does not prove a dot is connected.
    val liveDotConnected: Boolean get() = false
    val label: String get() = (when (mode) {
        ConnectionMode.LOCAL_PREVIEW -> "Local preview · synthetic replies"
        ConnectionMode.DISCONNECTED -> "Disconnected · messages stay on this device"
        ConnectionMode.LOCAL_BRIDGE -> "Local bridge · dot connection unverified"
    }) + if (!online) " · offline" else ""
}

object CompanionQueue {
    const val CONVERSATION_ID = "local-preview"
    const val MAX_TEXT_BYTES = 8192
    const val MAX_MESSAGES = 100
    const val MAX_ATTEMPTS = 5

    fun validate(message: CompanionMessage) {
        require(UUID.fromString(message.requestId).toString() == message.requestId) { "Invalid request ID" }
        require(message.conversationId == CONVERSATION_ID) { "Unknown conversation" }
        require(message.text.isNotBlank() && message.text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) {
            "Message must contain 1–8192 UTF-8 bytes"
        }
        Instant.parse(message.createdAt)
    }

    fun enqueue(state: CompanionState, message: CompanionMessage): CompanionState {
        validate(message)
        val previous = state.messages.find { it.requestId == message.requestId }
        if (previous != null) {
            require(previous.conversationId == message.conversationId && previous.text == message.text &&
                previous.createdAt == message.createdAt) { "Request ID conflicts with an existing message" }
            return state
        }
        require(state.messages.size < MAX_MESSAGES) { "Local queue is full" }
        return state.copy(messages = state.messages + message.copy(status = MessageStatus.QUEUED,
            attempts = 0, reply = null, replyProvenance = null, failureKind = null, nextAttemptAtMillis = 0))
    }

    fun startAttempt(state: CompanionState, requestId: String, nowMillis: Long): CompanionState {
        if (!state.online || state.mode == ConnectionMode.DISCONNECTED) return state
        return update(state, requestId) { message ->
            if (message.status != MessageStatus.QUEUED || message.nextAttemptAtMillis > nowMillis) message
            else if (message.attempts >= MAX_ATTEMPTS) message.copy(status = MessageStatus.FAILED,
                failureKind = FailureKind.TRANSPORT)
            else message.copy(status = MessageStatus.SENDING, attempts = message.attempts + 1)
        }
    }

    fun accepted(state: CompanionState, requestId: String): CompanionState = update(state, requestId) {
        if (it.status == MessageStatus.SENDING || it.status == MessageStatus.QUEUED ||
            (it.status == MessageStatus.FAILED && it.failureKind == FailureKind.TRANSPORT))
            it.copy(status = MessageStatus.AWAITING_REPLY, failureKind = null, nextAttemptAtMillis = 0) else it
    }

    fun transportFailed(state: CompanionState, requestId: String, nowMillis: Long): CompanionState =
        update(state, requestId) {
            if (it.status != MessageStatus.SENDING) it
            else if (it.attempts >= MAX_ATTEMPTS) it.copy(status = MessageStatus.FAILED, failureKind = FailureKind.TRANSPORT)
            else it.copy(status = MessageStatus.QUEUED, nextAttemptAtMillis = nowMillis +
                (1000L shl (it.attempts - 1).coerceIn(0, 4)))
        }

    fun replied(state: CompanionState, requestId: String, reply: String,
        provenance: ReplyProvenance = if (state.mode == ConnectionMode.LOCAL_PREVIEW)
            ReplyProvenance.LOCAL_SYNTHETIC else ReplyProvenance.LOCAL_BRIDGE): CompanionState {
        require(reply.isNotBlank() && reply.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) { "Invalid reply" }
        return update(state, requestId) {
            if (it.status == MessageStatus.REPLIED) {
                require(it.reply == reply && it.replyProvenance == provenance) { "Reply conflicts with an existing reply" }
                it
            } else it.copy(status = MessageStatus.REPLIED, reply = reply, replyProvenance = provenance, failureKind = null)
        }
    }

    fun remoteFailed(state: CompanionState, requestId: String): CompanionState = update(state, requestId) {
        if (it.status == MessageStatus.REPLIED) it else it.copy(status = MessageStatus.FAILED, failureKind = FailureKind.REMOTE)
    }

    fun retry(state: CompanionState, requestId: String): CompanionState = update(state, requestId) {
        if (it.status == MessageStatus.FAILED && it.failureKind == FailureKind.TRANSPORT)
            it.copy(status = MessageStatus.QUEUED, attempts = 0, nextAttemptAtMillis = 0, failureKind = null) else it
    }

    fun recoverAfterRestart(state: CompanionState,
        syntheticRequestIds: Set<String> = emptySet()): CompanionState = state.copy(messages = state.messages.map {
        if (it.status == MessageStatus.SENDING ||
            (it.status == MessageStatus.AWAITING_REPLY && it.requestId in syntheticRequestIds))
            it.copy(status = MessageStatus.QUEUED) else it
    })

    private fun update(state: CompanionState, requestId: String,
        transform: (CompanionMessage) -> CompanionMessage): CompanionState {
        require(state.messages.any { it.requestId == requestId }) { "Unknown request" }
        return state.copy(messages = state.messages.map { if (it.requestId == requestId) transform(it) else it })
    }
}

data class WatchEnvelope(val schemaVersion: Int, val requestId: String, val text: String, val createdAt: String) {
    fun asMessage(): CompanionMessage {
        require(schemaVersion == 1) { "Unsupported watch protocol" }
        return CompanionMessage(requestId, CompanionQueue.CONVERSATION_ID, text, createdAt,
            source = MessageSource.WATCH).also(CompanionQueue::validate)
    }
}

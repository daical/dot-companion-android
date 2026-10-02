package dev.dotcompanion.core

import org.junit.Assert.*
import org.junit.Test

class CompanionQueueTest {
    private fun message(text: String = "Hello") = CompanionMessage(
        "6b805c43-4b2b-4f02-8dd4-6ec60a944cf1", CompanionQueue.CONVERSATION_ID,
        text, "2026-10-01T12:00:00Z")

    @Test fun offlineQueuesWithoutClaimingDelivery() {
        val state = CompanionQueue.enqueue(CompanionState(online = false), message())
        assertEquals(state, CompanionQueue.startAttempt(state, message().requestId, 0))
        assertEquals(MessageStatus.QUEUED, state.messages.single().status)
        assertFalse(state.liveDotConnected)
    }
    @Test fun duplicateRequestIsIdempotentButChangedContentConflicts() {
        val state = CompanionQueue.enqueue(CompanionState(), message())
        assertEquals(state, CompanionQueue.enqueue(state, message()))
        assertThrows(IllegalArgumentException::class.java) { CompanionQueue.enqueue(state, message("Changed")) }
    }
    @Test fun receiptIsNotReply() {
        var state = CompanionQueue.enqueue(CompanionState(), message())
        state = CompanionQueue.startAttempt(state, message().requestId, 0)
        state = CompanionQueue.accepted(state, message().requestId)
        assertEquals(MessageStatus.AWAITING_REPLY, state.messages.single().status)
        assertNull(state.messages.single().reply)
        state = CompanionQueue.replied(state, message().requestId, "Synthetic fixture reply")
        assertEquals(MessageStatus.REPLIED, state.messages.single().status)
        assertEquals(state, CompanionQueue.replied(state, message().requestId, "Synthetic fixture reply"))
    }
    @Test fun retryIsBoundedAndBacksOff() {
        var state = CompanionQueue.enqueue(CompanionState(), message())
        var now = 0L
        repeat(CompanionQueue.MAX_ATTEMPTS) { attempt ->
            state = CompanionQueue.startAttempt(state, message().requestId, now)
            state = CompanionQueue.transportFailed(state, message().requestId, now)
            if (attempt < CompanionQueue.MAX_ATTEMPTS - 1) {
                assertEquals(state, CompanionQueue.startAttempt(state, message().requestId, now))
                now = state.messages.single().nextAttemptAtMillis
            }
        }
        assertEquals(MessageStatus.FAILED, state.messages.single().status)
        assertEquals(CompanionQueue.MAX_ATTEMPTS, state.messages.single().attempts)
        assertEquals(FailureKind.TRANSPORT, state.messages.single().failureKind)
        assertEquals(MessageStatus.QUEUED, CompanionQueue.retry(state, message().requestId).messages.single().status)
    }
    @Test fun restartRecoversSendingWithSameRequestId() {
        val queued = CompanionQueue.enqueue(CompanionState(), message())
        val interrupted = CompanionQueue.startAttempt(queued, message().requestId, 0)
        val recovered = CompanionQueue.recoverAfterRestart(interrupted)
        assertEquals(MessageStatus.QUEUED, recovered.messages.single().status)
        assertEquals(message().requestId, recovered.messages.single().requestId)
    }
    @Test fun rejectMalformedAndOversizeWatchInput() {
        assertThrows(IllegalArgumentException::class.java) { WatchEnvelope(2, message().requestId, "Hi", message().createdAt).asMessage() }
        assertThrows(IllegalArgumentException::class.java) { CompanionQueue.validate(message("👋".repeat(3000))) }
        assertThrows(IllegalArgumentException::class.java) { CompanionQueue.validate(message().copy(requestId = "not-a-uuid")) }
        assertThrows(IllegalArgumentException::class.java) { CompanionQueue.validate(message().copy(conversationId = "someone-else")) }
    }
    @Test fun syntheticReplyKeepsProvenanceAcrossModeChange() {
        var state = CompanionQueue.enqueue(CompanionState(), message())
        state = CompanionQueue.replied(state, message().requestId, "Synthetic fixture")
        state = state.copy(mode = ConnectionMode.LOCAL_BRIDGE)
        assertEquals(ReplyProvenance.LOCAL_SYNTHETIC, state.messages.single().replyProvenance)
        assertFalse(state.liveDotConnected)
        assertEquals(MessageSource.WATCH,
            WatchEnvelope(1, message().requestId, "Watch", message().createdAt).asMessage().source)
    }
    @Test fun remoteFailureCannotDowngradeReply() {
        val queued = CompanionQueue.enqueue(CompanionState(), message())
        val replied = CompanionQueue.replied(queued, message().requestId, "Synthetic fixture")
        assertEquals(replied, CompanionQueue.remoteFailed(replied, message().requestId))
        assertEquals(MessageStatus.FAILED, CompanionQueue.remoteFailed(queued, message().requestId).messages.single().status)
        val failed = CompanionQueue.remoteFailed(queued, message().requestId)
        assertEquals(FailureKind.REMOTE, failed.messages.single().failureKind)
        assertEquals(failed, CompanionQueue.retry(failed, message().requestId))
    }
    @Test fun interruptedSyntheticReplyResumesButRemoteWaitDoesNotResubmit() {
        val queued = CompanionQueue.enqueue(CompanionState(), message())
        val awaiting = CompanionQueue.accepted(CompanionQueue.startAttempt(queued, message().requestId, 0), message().requestId)
        assertEquals(MessageStatus.AWAITING_REPLY,
            CompanionQueue.recoverAfterRestart(awaiting).messages.single().status)
        val previewRecovery = CompanionQueue.recoverAfterRestart(awaiting, setOf(message().requestId))
        assertEquals(MessageStatus.QUEUED, previewRecovery.messages.single().status)
        assertEquals(message().requestId, previewRecovery.messages.single().requestId)
    }
    @Test fun delayedDurableReceiptRecoversTransportTimeoutWithoutDowngradingTerminalStates() {
        val queued = CompanionQueue.enqueue(CompanionState(), message())
        val afterTimeout = CompanionQueue.transportFailed(
            CompanionQueue.startAttempt(queued, message().requestId, 0), message().requestId, 0)
        assertEquals(MessageStatus.AWAITING_REPLY,
            CompanionQueue.accepted(afterTimeout, message().requestId).messages.single().status)
        val exhausted = queued.copy(messages = listOf(message().copy(status = MessageStatus.FAILED,
            attempts = CompanionQueue.MAX_ATTEMPTS, failureKind = FailureKind.TRANSPORT)))
        assertEquals(MessageStatus.AWAITING_REPLY,
            CompanionQueue.accepted(exhausted, message().requestId).messages.single().status)
        val remoteFailure = CompanionQueue.remoteFailed(queued, message().requestId)
        assertEquals(remoteFailure, CompanionQueue.accepted(remoteFailure, message().requestId))
        val replied = CompanionQueue.replied(queued, message().requestId, "Synthetic fixture")
        assertEquals(replied, CompanionQueue.accepted(replied, message().requestId))
    }
}

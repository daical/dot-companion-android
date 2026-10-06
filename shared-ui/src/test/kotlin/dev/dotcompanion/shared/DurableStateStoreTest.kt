package dev.dotcompanion.shared

import dev.dotcompanion.core.CompanionMessage
import dev.dotcompanion.core.CompanionQueue
import dev.dotcompanion.core.CompanionState
import dev.dotcompanion.core.ConnectionMode
import dev.dotcompanion.core.FailureKind
import dev.dotcompanion.core.MessageSource
import dev.dotcompanion.core.MessageStatus
import dev.dotcompanion.core.ReplyProvenance
import java.io.ByteArrayInputStream
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DurableStateStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val message = CompanionMessage("9f13e909-fb28-479a-9b99-de401517eaa1", CompanionQueue.CONVERSATION_ID, "Preview", "2026-01-01T00:00:00Z")

    @Test @Config(sdk = [26]) fun provenanceAndAcknowledgementSurviveModeChangeAndReload() {
        val file = File(temporary.root, "queue.json")
        val replied = message.copy(status = MessageStatus.REPLIED, reply = "[Synthetic fixture] Local", replyProvenance = ReplyProvenance.LOCAL_SYNTHETIC)
        val snapshot = PersistentSnapshot(CompanionState(ConnectionMode.LOCAL_BRIDGE, listOf(replied)),
            syntheticRequestIds = setOf(message.requestId), phoneAcknowledgedIds = setOf(message.requestId), peerNodeIds = mapOf(message.requestId to "synthetic-peer"))
        val store = DurableStateStore(file)
        store.update { snapshot }
        val restored = DurableStateStore(file).snapshots.value
        assertEquals(ConnectionMode.DISCONNECTED, restored.state.mode)
        assertEquals(ReplyProvenance.LOCAL_SYNTHETIC, restored.state.messages.single().replyProvenance)
        assertEquals(snapshot.phoneAcknowledgedIds, restored.phoneAcknowledgedIds)
        assertFalse(restored.state.liveDotConnected)
    }
    @Test fun interruptedSyntheticReplyRecoversWithTheSameRequestId() {
        val file = File(temporary.root, "queue.json")
        val store = DurableStateStore(file)
        store.update { PersistentSnapshot(CompanionState(messages = listOf(message.copy(status = MessageStatus.AWAITING_REPLY))), syntheticRequestIds = setOf(message.requestId)) }
        val restored = DurableStateStore(file).snapshots.value
        assertEquals(message.requestId, restored.state.messages.single().requestId)
        assertEquals(MessageStatus.QUEUED, restored.state.messages.single().status)
    }
    @Test fun watchPhoneLinkPolicyRetainsQueueRecoveryAndDeliveryOwnership() {
        val file = File(temporary.root, "watch-queue.json")
        val interrupted = message.copy(source = MessageSource.WATCH, status = MessageStatus.SENDING,
            attempts = 2, nextAttemptAtMillis = 5500)
        val awaiting = message.copy(requestId = "2a384828-547d-4c7f-ac26-46aa41998811", source = MessageSource.WATCH,
            status = MessageStatus.AWAITING_REPLY, attempts = 1)
        val phoneFailed = message.copy(requestId = "9622894e-2c65-4b7c-bb97-709fdb66d517", source = MessageSource.WATCH,
            status = MessageStatus.FAILED, attempts = 3, failureKind = FailureKind.TRANSPORT)
        val synthetic = message.copy(requestId = "56371eba-d117-4114-9833-5e35bdc4057f", source = MessageSource.WATCH,
            status = MessageStatus.REPLIED, reply = "[Synthetic fixture] Retained preview", replyProvenance = ReplyProvenance.LOCAL_SYNTHETIC)
        val snapshot = PersistentSnapshot(CompanionState(ConnectionMode.LOCAL_BRIDGE, listOf(interrupted, awaiting, phoneFailed, synthetic)),
            syntheticRequestIds = setOf(synthetic.requestId), phoneAcknowledgedIds = setOf(awaiting.requestId),
            peerNodeIds = listOf(interrupted, awaiting, phoneFailed).associate { it.requestId to "fixture-phone" },
            phoneReportedFailureIds = setOf(phoneFailed.requestId))
        DurableStateStore(file).update { snapshot }

        val reopened = DurableStateStore(file, disconnectLocalBridgeOnRestart = false)
        assertEquals(snapshot.copy(state = snapshot.state.copy(messages = listOf(
            interrupted.copy(status = MessageStatus.QUEUED), awaiting, phoneFailed, synthetic))), reopened.snapshots.value)
        assertTrue(reopened.writable)
        assertNull(reopened.loadError)
        assertFalse(reopened.snapshots.value.state.liveDotConnected)
    }
    @Test fun watchPhoneLinkPolicyStillFailsClosedOnCorruptHistory() {
        val file = File(temporary.root, "watch-queue.json").apply { writeText("broken") }
        val reopened = DurableStateStore(file, disconnectLocalBridgeOnRestart = false)
        assertEquals(ConnectionMode.DISCONNECTED, reopened.snapshots.value.state.mode)
        assertFalse(reopened.writable)
        assertNotNull(reopened.loadError)
        assertThrows(IllegalStateException::class.java) { reopened.update { it } }
        assertEquals("broken", file.readText())
    }
    @Test fun corruptedHistoryIsPreservedUntilExplicitDeletion() {
        val file = File(temporary.root, "queue.json").apply { writeText("broken") }
        val store = DurableStateStore(file)
        assertNotNull(store.loadError)
        assertThrows(IllegalStateException::class.java) { store.update { it } }
        assertEquals("broken", file.readText())
        store.deleteHistory()
        assertNull(store.loadError)
        assertFalse(file.exists())
    }
    @Test fun failedWriteDoesNotPublishAnUnpersistedQueue() {
        val parent = File(temporary.root, "not-a-directory").apply { writeText("fixture") }
        val store = DurableStateStore(File(parent, "queue.json"))
        assertThrows(Exception::class.java) { store.update { it.copy(state = CompanionQueue.enqueue(it.state, message)) } }
        assertTrue(store.snapshots.value.state.messages.isEmpty())
    }
    @Test fun metadataAndQueueAreBoundedAndMustShareRequestIds() {
        val snapshot = PersistentSnapshot(CompanionState(messages = listOf(message)), phoneAcknowledgedIds = setOf("unknown"))
        assertThrows(IllegalArgumentException::class.java) { StateCodec.encode(snapshot) }
        assertThrows(IllegalArgumentException::class.java) { readBounded(ByteArrayInputStream(ByteArray(11)), 10) }
    }
    @Test fun persistedNumericAndBooleanFieldsRejectCoercion() {
        val original = StateCodec.encode(PersistentSnapshot(CompanionState(messages = listOf(message))))
        val json = JSONObject(String(original)).put("schemaVersion", "1")
        assertThrows(IllegalArgumentException::class.java) { StateCodec.decode(json.toString().toByteArray()) }
        json.put("schemaVersion", 1).put("online", "true")
        assertThrows(IllegalArgumentException::class.java) { StateCodec.decode(json.toString().toByteArray()) }
        json.put("online", true).getJSONArray("messages").getJSONObject(0).put("attempts", 1.5)
        assertThrows(IllegalArgumentException::class.java) { StateCodec.decode(json.toString().toByteArray()) }
    }
}

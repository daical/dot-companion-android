package dev.dotcompanion.wear

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.wearable.MessageEvent
import dev.dotcompanion.core.CompanionQueue
import dev.dotcompanion.core.ConnectionMode
import dev.dotcompanion.core.MessageStatus
import dev.dotcompanion.core.ReplyProvenance
import dev.dotcompanion.core.FailureKind
import dev.dotcompanion.shared.WatchWire
import dev.dotcompanion.shared.DurableStateStore
import dev.dotcompanion.shared.StateFileOperations
import dev.dotcompanion.shared.CheckedStateFileOperations
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class WatchRepositoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun clearFixtureState() {
        val file = File(context.filesDir, "companion-state-v1.json")
        file.delete(); File(file.path + ".bak").delete(); File(file.path + ".new").delete()
    }
    private class FakePhone : WatchPhoneTransport {
        var discovery: CompletableDeferred<Unit>? = null
        var sending: CompletableDeferred<Unit>? = null
        var sends = 0
        var lookups = 0
        override suspend fun peers(): List<PhonePeer> { lookups++; discovery?.await(); return listOf(PhonePeer("fixture-phone", true)) }
        override suspend fun send(nodeId: String, path: String, bytes: ByteArray) { sends++; sending?.await() }
    }
    private fun event(path: String, bytes: ByteArray, source: String = "fixture-phone") = object : MessageEvent {
        override fun getRequestId() = 1
        override fun getPath() = path
        override fun getData() = bytes
        override fun getSourceNodeId() = source
    }
    @Test fun transportCompletionNeverBecomesAPhoneReceiptOrReply() = runTest {
        val phone = FakePhone()
        val repo = WatchRepository(context, backgroundScope, phone) { 1000 + testScheduler.currentTime }
        repo.selectMode(ConnectionMode.LOCAL_BRIDGE); runCurrent(); repo.send("A local link fixture"); runCurrent()
        repo.startActive(); runCurrent()
        val message = repo.saved.state.messages.single()
        assertEquals(MessageStatus.SENDING, message.status)
        assertTrue(repo.saved.phoneAcknowledgedIds.isEmpty())
        assertNull(message.reply)
        repo.receivePhone(event(WatchWire.ACK_PATH, WatchWire.receipt(message.requestId)))
        assertEquals(MessageStatus.AWAITING_REPLY, repo.saved.state.messages.single().status)
        assertTrue(message.requestId in repo.saved.phoneAcknowledgedIds)
        assertNull(repo.saved.state.messages.single().reply)
    }
    @Test fun discoveryTimeoutKeepsForegroundWorkerAliveForRecovery() = runTest {
        val phone = FakePhone().apply { discovery = CompletableDeferred() }
        val repo = WatchRepository(context, backgroundScope, phone) { 1000 + testScheduler.currentTime }
        repo.selectMode(ConnectionMode.LOCAL_BRIDGE); runCurrent(); repo.send("Discovery timeout fixture"); runCurrent(); repo.startActive(); runCurrent()
        advanceTimeBy(5100); runCurrent()
        assertEquals(MessageStatus.QUEUED, repo.saved.state.messages.single().status)
        phone.discovery!!.complete(Unit)
        advanceTimeBy(2100); runCurrent()
        assertTrue(phone.lookups >= 2)
        assertEquals(1, phone.sends)
        assertEquals(MessageStatus.SENDING, repo.saved.state.messages.single().status)
    }
    @Test fun sendTimeoutReturnsToBoundedQueueAndCanRetryWithoutRestart() = runTest {
        val phone = FakePhone().apply { sending = CompletableDeferred() }
        val repo = WatchRepository(context, backgroundScope, phone) { 1000 + testScheduler.currentTime }
        repo.selectMode(ConnectionMode.LOCAL_BRIDGE); runCurrent(); repo.send("Send timeout fixture"); runCurrent(); repo.startActive(); runCurrent()
        val id = repo.saved.state.messages.single().requestId
        advanceTimeBy(5100); runCurrent()
        assertEquals(MessageStatus.QUEUED, repo.saved.state.messages.single().status)
        assertEquals(1, repo.saved.state.messages.single().attempts)
        phone.sending = null
        advanceTimeBy(2100); runCurrent()
        assertEquals(2, phone.sends)
        assertEquals(id, repo.saved.state.messages.single().requestId)
        assertEquals(MessageStatus.SENDING, repo.saved.state.messages.single().status)
    }
    @Test fun delayedDurableReceiptAfterTimeoutStopsResendingAndKeepsItsIdentity() = runTest {
        val phone = FakePhone()
        val repo = WatchRepository(context, backgroundScope, phone) { 1000 + testScheduler.currentTime }
        repo.selectMode(ConnectionMode.LOCAL_BRIDGE); runCurrent(); repo.send("Dropped ACK fixture"); runCurrent(); repo.startActive(); runCurrent()
        val id = repo.saved.state.messages.single().requestId
        advanceTimeBy(10_001); runCurrent()
        assertEquals(MessageStatus.QUEUED, repo.saved.state.messages.single().status)
        repo.receivePhone(event(WatchWire.ACK_PATH, WatchWire.receipt(id)))
        assertEquals(MessageStatus.AWAITING_REPLY, repo.saved.state.messages.single().status)
        assertTrue(id in repo.saved.phoneAcknowledgedIds)
        assertEquals(0L, repo.saved.state.messages.single().nextAttemptAtMillis)
    }
    @Test fun replyAndLateQueueSnapshotRemainRepliedEvenWhenTheAckArrivesLast() = runTest {
        val phone = FakePhone()
        val repo = WatchRepository(context, backgroundScope, phone) { 1000 + testScheduler.currentTime }
        repo.selectMode(ConnectionMode.LOCAL_BRIDGE); runCurrent(); repo.send("Reordered reply fixture"); runCurrent(); repo.startActive(); runCurrent()
        val original = repo.saved.state.messages.single()
        val replied = original.copy(status = MessageStatus.REPLIED, reply = "[Local bridge test fixture] Reply", replyProvenance = ReplyProvenance.LOCAL_BRIDGE)
        repo.receivePhone(event(WatchWire.STATE_PATH, WatchWire.state(replied, ConnectionMode.LOCAL_BRIDGE, true)))
        assertTrue(repo.saved.phoneAcknowledgedIds.isEmpty())
        repo.receivePhone(event(WatchWire.STATE_PATH, WatchWire.state(original.copy(status = MessageStatus.QUEUED), ConnectionMode.DISCONNECTED, false)))
        repo.receivePhone(event(WatchWire.ACK_PATH, WatchWire.receipt(original.requestId)))
        assertEquals(MessageStatus.REPLIED, repo.saved.state.messages.single().status)
        assertEquals(replied.reply, repo.saved.state.messages.single().reply)
        assertTrue(original.requestId in repo.saved.phoneAcknowledgedIds)
    }
    @Test fun unrelatedPeerCannotAcknowledgeAKnownRequest() = runTest {
        val repo = WatchRepository(context, backgroundScope, FakePhone()) { 1000 + testScheduler.currentTime }
        repo.selectMode(ConnectionMode.LOCAL_BRIDGE); runCurrent(); repo.send("Wrong peer fixture"); runCurrent(); repo.startActive(); runCurrent()
        val id = repo.saved.state.messages.single().requestId
        try { repo.receivePhone(event(WatchWire.ACK_PATH, WatchWire.receipt(id), "untrusted-peer")); fail("Expected peer refusal") } catch (_: IllegalArgumentException) { }
        assertTrue(repo.saved.phoneAcknowledgedIds.isEmpty())
    }
    @Test fun lateTransportFailureCannotDowngradeARemoteTerminalFailure() = runTest {
        val repo = WatchRepository(context, backgroundScope, FakePhone()) { 1000 + testScheduler.currentTime }
        repo.selectMode(ConnectionMode.LOCAL_BRIDGE); runCurrent(); repo.send("Terminal failure fixture"); runCurrent(); repo.startActive(); runCurrent()
        val original = repo.saved.state.messages.single()
        repo.receivePhone(event(WatchWire.STATE_PATH, WatchWire.state(original.copy(status = MessageStatus.FAILED, failureKind = FailureKind.REMOTE), ConnectionMode.LOCAL_BRIDGE, true)))
        repo.receivePhone(event(WatchWire.STATE_PATH, WatchWire.state(original.copy(status = MessageStatus.FAILED, failureKind = FailureKind.TRANSPORT), ConnectionMode.LOCAL_BRIDGE, true)))
        assertEquals(FailureKind.REMOTE, repo.saved.state.messages.single().failureKind)
        repo.retry(original.requestId); runCurrent()
        assertEquals(MessageStatus.FAILED, repo.saved.state.messages.single().status)
        assertTrue(original.requestId in repo.saved.phoneReportedFailureIds)
    }
    @Test fun localSyntheticReplyResumesAfterForegroundPause() = runTest {
        val repo = WatchRepository(context, backgroundScope, FakePhone()) { 1000 + testScheduler.currentTime }
        repo.send("Synthetic lifecycle fixture"); repo.startActive(); runCurrent(); advanceTimeBy(300); runCurrent()
        val id = repo.saved.state.messages.single().requestId
        assertEquals(MessageStatus.AWAITING_REPLY, repo.saved.state.messages.single().status)
        repo.stopActive(); runCurrent(); repo.startActive(); runCurrent(); advanceTimeBy(650); runCurrent()
        assertEquals(id, repo.saved.state.messages.single().requestId)
        assertEquals(ReplyProvenance.LOCAL_SYNTHETIC, repo.saved.state.messages.single().replyProvenance)
    }
    @Test fun staleQueueSnapshotCannotExposeWatchRetryForAPhoneTransportFailure() = runTest {
        val repo = WatchRepository(context, backgroundScope, FakePhone()) { 1000 + testScheduler.currentTime }
        repo.selectMode(ConnectionMode.LOCAL_BRIDGE); runCurrent(); repo.send("Phone transport failure fixture"); runCurrent(); repo.startActive(); runCurrent()
        val original = repo.saved.state.messages.single()
        repo.receivePhone(event(WatchWire.STATE_PATH, WatchWire.state(original.copy(status = MessageStatus.FAILED, failureKind = FailureKind.TRANSPORT), ConnectionMode.LOCAL_BRIDGE, true)))
        repo.receivePhone(event(WatchWire.STATE_PATH, WatchWire.state(original.copy(status = MessageStatus.QUEUED), ConnectionMode.LOCAL_BRIDGE, true)))
        assertTrue(repo.saved.phoneAcknowledgedIds.isEmpty())
        assertTrue(original.requestId in repo.saved.phoneReportedFailureIds)
        repo.retry(original.requestId); runCurrent()
        assertEquals(MessageStatus.FAILED, repo.saved.state.messages.single().status)
    }
    @Test fun failedDeletionRetainsWatchHistoryAndSurfacesAnIssue() = runTest {
        var failDelete = false
        val operations = object : StateFileOperations by CheckedStateFileOperations {
            override fun delete(file: File) {
                if (failDelete) throw IOException("Injected deletion failure")
                CheckedStateFileOperations.delete(file)
            }
        }
        val repo = WatchRepository(context, backgroundScope, FakePhone(), storeFactory = { DurableStateStore(it, operations) }) { 1000 + testScheduler.currentTime }
        repo.send("Retained watch deletion fixture"); runCurrent()
        val before = repo.saved
        failDelete = true
        repo.deleteHistory(); runCurrent()
        assertEquals(before, repo.saved)
        assertTrue(repo.ui.value.issue.orEmpty().contains("Deletion could not be confirmed"))
        repo.startActive(); runCurrent(); advanceTimeBy(2100); runCurrent()
        assertEquals(before, repo.saved)
    }
}

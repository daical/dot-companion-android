package dev.dotcompanion.mobile

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.wearable.MessageEvent
import dev.dotcompanion.core.CompanionMessage
import dev.dotcompanion.core.CompanionQueue
import dev.dotcompanion.core.ConnectionMode
import dev.dotcompanion.core.FailureKind
import dev.dotcompanion.core.MessageStatus
import dev.dotcompanion.core.ReplyProvenance
import dev.dotcompanion.shared.DurableStateStore
import dev.dotcompanion.shared.WatchWire
import dev.dotcompanion.shared.StateFileOperations
import dev.dotcompanion.shared.CheckedStateFileOperations
import java.io.File
import java.io.FileOutputStream
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
class PhoneRepositoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val file get() = File(context.filesDir, "companion-state-v1.json")
    @Before fun clearFixtureState() { file.delete(); File(file.path + ".bak").delete(); File(file.path + ".new").delete() }

    private class FakeBridge : BridgeTransport {
        var verification: CompletableDeferred<Unit>? = null
        var submission: CompletableDeferred<Unit>? = null
        var failed = false
        var submissions = 0
        val accepted = mutableMapOf<String, CompanionMessage>()
        override suspend fun verifyStatus() { verification?.await() }
        override suspend fun submit(message: CompanionMessage) { submissions++; submission?.await(); accepted[message.requestId] = message }
        override suspend fun messages() = accepted.values.map { it.copy(status = if (failed) MessageStatus.FAILED else MessageStatus.AWAITING_REPLY) }
    }
    private class FakeWatch : PhoneWatchTransport {
        override val available = true
        val sent = mutableListOf<Pair<String, ByteArray>>()
        var onSend: ((String, ByteArray) -> Unit)? = null
        override suspend fun trustedWatch(nodeId: String) = true
        override suspend fun send(nodeId: String, path: String, bytes: ByteArray) { onSend?.invoke(path, bytes); sent += path to bytes }
    }
    private fun event(message: CompanionMessage, node: String = "fixture-watch") = object : MessageEvent {
        override fun getRequestId() = 1
        override fun getPath() = WatchWire.REQUEST_PATH
        override fun getData() = WatchWire.request(message)
        override fun getSourceNodeId() = node
    }

    @Test fun delayedVerificationCannotReconnectAfterDisconnect() = runTest {
        val bridge = FakeBridge().apply { verification = CompletableDeferred() }
        val repo = PhoneRepository(context, backgroundScope, { _, _ -> bridge }, onlineCheck = { true })
        repo.selectMode(ConnectionMode.DISCONNECTED); runCurrent()
        repo.send("A queued local request"); runCurrent()
        repo.connect("https://example.invalid", "fixture-development-token"); runCurrent()
        repo.selectMode(ConnectionMode.DISCONNECTED); runCurrent()
        bridge.verification!!.complete(Unit); runCurrent()
        repo.startActive(); runCurrent(); advanceTimeBy(2000); runCurrent()
        assertEquals(ConnectionMode.DISCONNECTED, repo.saved.state.mode)
        assertEquals(MessageStatus.QUEUED, repo.saved.state.messages.single().status)
        assertEquals(0, bridge.submissions)
    }
    @Test fun previewResumesAfterPauseDuringSyntheticAwaitingReply() = runTest {
        val repo = PhoneRepository(context, backgroundScope, onlineCheck = { true })
        repo.send("A synthetic preview"); repo.startActive(); runCurrent()
        advanceTimeBy(300); runCurrent()
        val requestId = repo.saved.state.messages.single().requestId
        assertEquals(MessageStatus.AWAITING_REPLY, repo.saved.state.messages.single().status)
        repo.stopActive(); runCurrent()
        assertEquals(MessageStatus.QUEUED, repo.saved.state.messages.single().status)
        repo.startActive(); runCurrent(); advanceTimeBy(650); runCurrent()
        assertEquals(requestId, repo.saved.state.messages.single().requestId)
        assertEquals(MessageStatus.REPLIED, repo.saved.state.messages.single().status)
        assertEquals(ReplyProvenance.LOCAL_SYNTHETIC, repo.saved.state.messages.single().replyProvenance)
    }
    @Test fun cancelledTransportRestartsWithStableIdAndAcceptedMessageIsNotResubmitted() = runTest {
        val bridge = FakeBridge().apply { submission = CompletableDeferred() }
        val repo = PhoneRepository(context, backgroundScope, { _, _ -> bridge }, onlineCheck = { true })
        repo.selectMode(ConnectionMode.DISCONNECTED); runCurrent()
        repo.send("A bridge request"); runCurrent()
        repo.connect("https://example.invalid", "fixture-development-token"); runCurrent()
        repo.startActive(); runCurrent()
        val id = repo.saved.state.messages.single().requestId
        assertEquals(MessageStatus.SENDING, repo.saved.state.messages.single().status)
        repo.stopActive(); runCurrent()
        assertEquals(MessageStatus.QUEUED, repo.saved.state.messages.single().status)
        bridge.submission = null
        repo.startActive(); runCurrent()
        assertEquals(id, repo.saved.state.messages.single().requestId)
        assertEquals(MessageStatus.AWAITING_REPLY, repo.saved.state.messages.single().status)
        repo.stopActive(); runCurrent(); repo.startActive(); runCurrent(); advanceTimeBy(2000); runCurrent()
        assertEquals(2, bridge.submissions)
    }
    @Test fun remoteFailureCannotBePresentedAsRetryableTransportFailure() = runTest {
        val bridge = FakeBridge().apply { failed = true }
        val repo = PhoneRepository(context, backgroundScope, { _, _ -> bridge }, onlineCheck = { true })
        repo.selectMode(ConnectionMode.DISCONNECTED); runCurrent(); repo.send("Bridge failure fixture"); runCurrent()
        repo.connect("https://example.invalid", "fixture-development-token"); runCurrent(); repo.startActive(); runCurrent()
        val id = repo.saved.state.messages.single().requestId
        assertEquals(FailureKind.REMOTE, repo.saved.state.messages.single().failureKind)
        repo.retry(id); runCurrent()
        assertEquals(MessageStatus.FAILED, repo.saved.state.messages.single().status)
        assertEquals(1, bridge.submissions)
    }
    @Test fun phoneAcknowledgesOnlyAfterAtomicPersistenceAndDuplicateKeepsPreviewOrigin() = runTest {
        val watch = FakeWatch()
        val repo = PhoneRepository(context, backgroundScope, watchTransport = watch, onlineCheck = { true })
        val message = CompanionMessage("9f13e909-fb28-479a-9b99-de401517eaa1", CompanionQueue.CONVERSATION_ID, "From fixture watch", "2026-01-01T00:00:00Z")
        watch.onSend = { path, _ ->
            if (path == WatchWire.ACK_PATH) assertEquals(message.requestId, DurableStateStore(file).snapshots.value.state.messages.single().requestId)
        }
        repo.receiveWatch(event(message))
        repo.selectMode(ConnectionMode.DISCONNECTED); runCurrent()
        repo.receiveWatch(event(message))
        assertEquals(1, repo.saved.state.messages.size)
        assertTrue(message.requestId in repo.saved.syntheticRequestIds)
        assertEquals(2, watch.sent.count { it.first == WatchWire.ACK_PATH })
    }
    @Test fun unreadableStoreNeverAcknowledgesOrOverwritesAWatchRequest() = runTest {
        file.writeText("corrupted fixture")
        val watch = FakeWatch()
        val repo = PhoneRepository(context, backgroundScope, watchTransport = watch)
        val message = CompanionMessage("9f13e909-fb28-479a-9b99-de401517eaa1", CompanionQueue.CONVERSATION_ID, "Not persisted", "2026-01-01T00:00:00Z")
        try { repo.receiveWatch(event(message)); fail("Expected persistence refusal") } catch (_: IllegalStateException) { }
        assertTrue(watch.sent.none { it.first == WatchWire.ACK_PATH })
        assertEquals("corrupted fixture", file.readText())
    }
    @Test fun deletingHistoryInvalidatesDelayedConnectionAndPreservesEmptyQueue() = runTest {
        val bridge = FakeBridge().apply { verification = CompletableDeferred() }
        val repo = PhoneRepository(context, backgroundScope, { _, _ -> bridge }, onlineCheck = { true })
        repo.send("To delete"); runCurrent(); repo.startActive(); runCurrent()
        repo.connect("https://example.invalid", "fixture-development-token"); runCurrent()
        repo.deleteHistory(); runCurrent()
        bridge.verification!!.complete(Unit); runCurrent(); advanceTimeBy(2000); runCurrent()
        assertTrue(repo.saved.state.messages.isEmpty())
        assertEquals(ConnectionMode.DISCONNECTED, repo.saved.state.mode)
        assertEquals(0, bridge.submissions)
    }
    @Test fun syncRenameAndDirectorySyncFailuresNeverIssueANewDurablePhoneReceipt() = runTest {
        listOf("file_sync", "rename", "post_rename", "directory_sync").forEach { fault ->
            val history = File(context.cacheDir, "phone-$fault.json")
            history.delete(); File(history.path + ".new").delete(); File(history.path + ".bak").delete()
            var armed = false
            val operations = object : StateFileOperations by CheckedStateFileOperations {
                override fun syncFile(stream: FileOutputStream) {
                    if (armed && fault == "file_sync") throw IOException("Injected sync failure")
                    CheckedStateFileOperations.syncFile(stream)
                }
                override fun rename(source: File, target: File) {
                    if (armed && fault == "rename") throw IOException("Injected rename failure")
                    CheckedStateFileOperations.rename(source, target)
                    if (armed && fault == "post_rename") throw IOException("Injected post-rename failure")
                }
                override fun syncDirectory(directory: File) {
                    if (armed && fault == "directory_sync") throw IOException("Injected directory sync failure")
                    CheckedStateFileOperations.syncDirectory(directory)
                }
            }
            val watch = FakeWatch()
            val repo = PhoneRepository(context, backgroundScope, watchTransport = watch,
                storeFactory = { DurableStateStore(history, operations) }, onlineCheck = { true })
            val first = CompanionMessage("9f13e909-fb28-479a-9b99-de401517eaa1", CompanionQueue.CONVERSATION_ID, "Confirmed fixture", "2026-01-01T00:00:00Z")
            repo.receiveWatch(event(first)); runCurrent()
            watch.sent.clear()
            armed = true
            val next = first.copy(requestId = "2a384828-547d-4c7f-ac26-46aa41998811", text = "Unconfirmed fixture")
            try { repo.receiveWatch(event(next)); fail("Expected commit failure") } catch (_: IOException) { }
            runCurrent()
            assertEquals(listOf(first.requestId), repo.saved.state.messages.map { it.requestId })
            assertTrue(watch.sent.none { it.first == WatchWire.ACK_PATH })
            assertNotNull(repo.ui.value.issue)
            val observed = history.readBytes()
            repo.send("A stale-memory overwrite must be refused"); runCurrent()
            assertArrayEquals(observed, history.readBytes())
        }
    }
    @Test fun failedDeletionRetainsHistoryAndSurfacesAnIssueWithoutUnsafeWorkerRestart() = runTest {
        var failDelete = false
        val operations = object : StateFileOperations by CheckedStateFileOperations {
            override fun delete(file: File) {
                if (failDelete) throw IOException("Injected deletion failure")
                CheckedStateFileOperations.delete(file)
            }
        }
        val repo = PhoneRepository(context, backgroundScope, storeFactory = { DurableStateStore(it, operations) }, onlineCheck = { true })
        repo.send("Retained deletion fixture"); runCurrent()
        val before = repo.saved
        failDelete = true
        repo.deleteHistory(); runCurrent()
        assertEquals(before, repo.saved)
        assertTrue(repo.ui.value.issue.orEmpty().contains("Deletion could not be confirmed"))
        repo.startActive(); runCurrent(); advanceTimeBy(2000); runCurrent()
        assertEquals(before, repo.saved)
    }
}

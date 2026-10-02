package dev.dotcompanion.wear

import android.app.Application
import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.dotcompanion.core.CompanionMessage
import dev.dotcompanion.core.CompanionQueue
import dev.dotcompanion.core.ConnectionMode
import dev.dotcompanion.core.FailureKind
import dev.dotcompanion.core.MessageSource
import dev.dotcompanion.core.MessageStatus
import dev.dotcompanion.core.ReplyProvenance
import dev.dotcompanion.shared.DurableStateStore
import dev.dotcompanion.shared.PersistentSnapshot
import dev.dotcompanion.shared.PhoneMessageState
import dev.dotcompanion.shared.WatchWire
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

data class WatchUiState(
    val saved: PersistentSnapshot,
    val phoneReachable: Boolean = false,
    val phoneMode: ConnectionMode? = null,
    val phoneOnline: Boolean? = null,
    val issue: String? = null,
) {
    val connectionLabel: String get() = when (saved.state.mode) {
        ConnectionMode.LOCAL_PREVIEW -> "Local preview · synthetic"
        ConnectionMode.DISCONNECTED -> "Disconnected · saved on watch"
        ConnectionMode.LOCAL_BRIDGE -> when {
            !phoneReachable -> "Phone offline · saved on watch"
            phoneMode == ConnectionMode.DISCONNECTED -> "Phone paused · open phone"
            phoneMode == ConnectionMode.LOCAL_PREVIEW -> "Phone preview · synthetic replies"
            phoneOnline == false -> "Phone network offline · delivery paused"
            else -> "Phone link · dot unverified"
        }
    }
}

class WatchApplication : Application() {
    val repository: WatchRepository by lazy { WatchRepository(this) }
}

data class PhonePeer(val id: String, val nearby: Boolean)
interface WatchPhoneTransport {
    suspend fun peers(): List<PhonePeer>
    suspend fun send(nodeId: String, path: String, bytes: ByteArray)
}
private class GoogleWatchPhoneTransport(private val context: Context) : WatchPhoneTransport {
    override suspend fun peers(): List<PhonePeer> {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) return emptyList()
        return Wearable.getCapabilityClient(context).getCapability(WatchWire.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await()
            .nodes.map { PhonePeer(it.id, it.isNearby) }.sortedWith(compareByDescending<PhonePeer> { it.nearby }.thenBy { it.id })
    }
    override suspend fun send(nodeId: String, path: String, bytes: ByteArray) {
        Wearable.getMessageClient(context).sendMessage(nodeId, path, bytes).await()
    }
}

class WatchRepository(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val transport: WatchPhoneTransport = GoogleWatchPhoneTransport(context),
    private val storeFactory: (File) -> DurableStateStore = { DurableStateStore(it) },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val store = storeFactory(File(context.filesDir, "companion-state-v1.json"))
    private val reachable = MutableStateFlow(false)
    private val phoneMode = MutableStateFlow<ConnectionMode?>(null)
    private val phoneOnline = MutableStateFlow<Boolean?>(null)
    private val issue = MutableStateFlow(store.loadError)
    val ui = combine(store.snapshots, reachable, phoneMode, phoneOnline, issue) { saved, connected, mode, online, error -> WatchUiState(saved, connected, mode, online, error) }
        .stateIn(scope, SharingStarted.Eagerly, WatchUiState(store.snapshots.value, issue = store.loadError))
    private val lifecycleLock = Mutex()
    private val modeLock = Any()
    @Volatile private var foregroundRequested = false
    @Volatile private var generation = 0L
    private var worker: Job? = null
    private var lastQueriedId: String? = null
    internal val saved get() = store.snapshots.value

    fun startActive() {
        foregroundRequested = true
        scope.launch { lifecycleLock.withLock { if (foregroundRequested && worker?.isActive != true) startWorker() } }
    }
    fun stopActive() {
        foregroundRequested = false
        scope.launch { lifecycleLock.withLock {
            if (foregroundRequested) return@withLock
            worker?.cancelAndJoin(); worker = null
            recover()
        } }
    }
    private fun startWorker() {
        recover()
        worker = scope.launch {
            while (isActive) {
                try { processQueue() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { issue.value = store.loadError ?: "Link unavailable. Your watch queue is preserved." }
                delay(2000)
            }
        }
    }

    fun selectMode(mode: ConnectionMode) {
        val change = synchronized(modeLock) { generation += 1; generation }
        scope.launch { synchronized(modeLock) {
            if (generation == change) safely { store.update {
                it.copy(state = CompanionQueue.recoverAfterRestart(it.state, it.syntheticRequestIds).copy(mode = mode))
            } }
        } }
    }
    fun send(text: String) = scope.launch {
        safely {
            val message = CompanionMessage(UUID.randomUUID().toString(), CompanionQueue.CONVERSATION_ID,
                text.trim(), Instant.now().toString(), source = MessageSource.WATCH)
            store.update { snapshot ->
                val synthetic = snapshot.state.mode == ConnectionMode.LOCAL_PREVIEW
                snapshot.copy(state = CompanionQueue.enqueue(snapshot.state, message),
                    syntheticRequestIds = if (synthetic) snapshot.syntheticRequestIds + message.requestId else snapshot.syntheticRequestIds)
            }
        }
    }
    fun retry(requestId: String) = scope.launch {
        if (requestId in store.snapshots.value.phoneReportedFailureIds) return@launch
        safely { store.update { it.copy(state = CompanionQueue.retry(it.state, requestId)) } }
    }
    fun deleteHistory() {
        synchronized(modeLock) { generation += 1 }
        scope.launch { lifecycleLock.withLock {
            worker?.cancelAndJoin(); worker = null
            try { store.deleteHistory(); issue.value = null }
            catch (_: Exception) { issue.value = "Deletion could not be confirmed. Displayed history is retained; restart before editing it." }
            if (foregroundRequested) startWorker()
        } }
    }

    suspend fun receivePhone(event: MessageEvent) {
        if (event.path != WatchWire.ACK_PATH && event.path != WatchWire.STATE_PATH) return
        // Validate bounded wire bytes before making a capability lookup.
        val receipt = if (event.path == WatchWire.ACK_PATH) WatchWire.decodeReceipt(event.data) else null
        val remote = if (event.path == WatchWire.STATE_PATH) WatchWire.decodeState(event.data) else null
        val requestId = receipt?.requestId ?: requireNotNull(remote).requestId
        val snapshot = store.snapshots.value
        require(snapshot.peerNodeIds[requestId] == event.sourceNodeId && requestId !in snapshot.syntheticRequestIds) { "Unknown phone request" }
        require(withTimeout(5000) { transport.peers().any { it.id == event.sourceNodeId } }) { "Unknown phone peer" }
        store.update { current ->
            require(current.peerNodeIds[requestId] == event.sourceNodeId && current.state.messages.any { it.requestId == requestId })
            if (receipt != null) current.copy(
                state = CompanionQueue.accepted(current.state, requestId),
                phoneAcknowledgedIds = current.phoneAcknowledgedIds + requestId,
            ) else applyPhoneState(current, requireNotNull(remote))
        }
        if (remote != null) { phoneMode.value = remote.mode; phoneOnline.value = remote.online }
        reachable.value = true
    }

    private fun applyPhoneState(snapshot: PersistentSnapshot, remote: PhoneMessageState): PersistentSnapshot {
        val local = snapshot.state.messages.first { it.requestId == remote.requestId }
        // Late queued/sending snapshots never overwrite a terminal reply or a confirmed durable receipt.
        val state = when (remote.status) {
            MessageStatus.REPLIED -> CompanionQueue.replied(snapshot.state, remote.requestId, requireNotNull(remote.reply), requireNotNull(remote.replyProvenance))
            MessageStatus.FAILED -> if (remote.failureKind == FailureKind.REMOTE) CompanionQueue.remoteFailed(snapshot.state, remote.requestId)
                else if (local.status == MessageStatus.REPLIED || local.failureKind == FailureKind.REMOTE) snapshot.state else snapshot.state.copy(messages = snapshot.state.messages.map {
                    if (it.requestId == remote.requestId) it.copy(status = MessageStatus.FAILED, failureKind = FailureKind.TRANSPORT) else it
                })
            else -> if (remote.requestId in snapshot.phoneAcknowledgedIds && local.status != MessageStatus.REPLIED && local.failureKind != FailureKind.REMOTE)
                snapshot.state.copy(messages = snapshot.state.messages.map {
                    if (it.requestId == remote.requestId) it.copy(status = MessageStatus.AWAITING_REPLY, failureKind = null) else it
                }) else if (remote.status == MessageStatus.AWAITING_REPLY) CompanionQueue.accepted(snapshot.state, remote.requestId) else snapshot.state
        }
        val resulting = state.messages.first { it.requestId == remote.requestId }
        val reportedFailures = when {
            remote.status == MessageStatus.FAILED -> snapshot.phoneReportedFailureIds + remote.requestId
            resulting.status != MessageStatus.FAILED -> snapshot.phoneReportedFailureIds - remote.requestId
            else -> snapshot.phoneReportedFailureIds
        }
        return snapshot.copy(state = state, phoneReportedFailureIds = reportedFailures)
    }

    private suspend fun processQueue() {
        val snapshot = store.snapshots.value
        if (!store.writable || snapshot.state.mode == ConnectionMode.DISCONNECTED) return
        val revision = generation
        if (snapshot.state.mode == ConnectionMode.LOCAL_PREVIEW) {
            val message = snapshot.state.messages.firstOrNull { it.requestId in snapshot.syntheticRequestIds && it.status == MessageStatus.QUEUED } ?: return
            if (!updateCurrent(revision, ConnectionMode.LOCAL_PREVIEW) { it.copy(state = CompanionQueue.startAttempt(it.state.copy(online = true), message.requestId, nowMillis()).copy(online = it.state.online)) }) return
            if (store.snapshots.value.state.messages.first { it.requestId == message.requestId }.status != MessageStatus.SENDING) return
            delay(250)
            if (!updateCurrent(revision, ConnectionMode.LOCAL_PREVIEW) { it.copy(state = CompanionQueue.accepted(it.state, message.requestId)) }) return
            delay(350)
            updateCurrent(revision, ConnectionMode.LOCAL_PREVIEW) { it.copy(state = CompanionQueue.replied(it.state, message.requestId,
                "[Synthetic fixture] A local watch preview. No dot or model was contacted.", ReplyProvenance.LOCAL_SYNTHETIC)) }
            return
        }
        val peers = withTimeoutOrNull(5000) { transport.peers() } ?: emptyList()
        currentCoroutineContext().ensureActive()
        reachable.value = peers.isNotEmpty()
        updateCurrent(revision, ConnectionMode.LOCAL_BRIDGE) { it.copy(state = it.state.copy(online = peers.isNotEmpty())) }
        if (peers.isEmpty()) return
        val now = nowMillis()
        snapshot.state.messages.filter { it.requestId !in snapshot.syntheticRequestIds && it.status == MessageStatus.SENDING &&
            it.nextAttemptAtMillis > 0 && it.nextAttemptAtMillis <= now }.forEach { message ->
            updateCurrent(revision, ConnectionMode.LOCAL_BRIDGE) { it.copy(state = CompanionQueue.transportFailed(it.state, message.requestId, now)) }
        }
        val current = store.snapshots.value
        val awaiting = current.state.messages.filter { it.requestId !in current.syntheticRequestIds && it.status == MessageStatus.AWAITING_REPLY }
        val nextAwaiting = if (awaiting.isEmpty()) null else awaiting[(awaiting.indexOfFirst { it.requestId == lastQueriedId } + 1).mod(awaiting.size)]
        val pending = current.state.messages.firstOrNull { it.requestId !in current.syntheticRequestIds && it.status == MessageStatus.QUEUED && it.nextAttemptAtMillis <= now }
            ?: nextAwaiting
            ?: return
        if (pending.status == MessageStatus.AWAITING_REPLY) lastQueriedId = pending.requestId
        val pinned = current.peerNodeIds[pending.requestId]
        val peer = if (pinned == null) peers.first() else peers.find { it.id == pinned } ?: return
        if (pending.status == MessageStatus.QUEUED) {
            if (!updateCurrent(revision, ConnectionMode.LOCAL_BRIDGE) { it.copy(
                state = CompanionQueue.startAttempt(it.state, pending.requestId, now), peerNodeIds = it.peerNodeIds + (pending.requestId to peer.id)) }) return
            if (store.snapshots.value.state.messages.first { it.requestId == pending.requestId }.status != MessageStatus.SENDING) return
        }
        try {
            val delivered = withTimeoutOrNull(5000) { transport.send(peer.id, WatchWire.REQUEST_PATH, WatchWire.request(pending)); true } == true
            if (!delivered) throw java.io.IOException("Phone transport timed out")
            currentCoroutineContext().ensureActive()
            updateCurrent(revision, ConnectionMode.LOCAL_BRIDGE) { it.copy(state = it.state.copy(messages = it.state.messages.map { message ->
                if (message.requestId == pending.requestId && message.status == MessageStatus.SENDING) message.copy(nextAttemptAtMillis = nowMillis() + 10_000) else message
            })) }
            // The MessageClient completion is only transport delivery. Await the separate durable-phone ACK.
            issue.value = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            updateCurrent(revision, ConnectionMode.LOCAL_BRIDGE) { it.copy(state = CompanionQueue.transportFailed(it.state, pending.requestId, nowMillis())) }
            issue.value = "Phone delivery paused. The request remains on this watch."
        }
    }

    private fun updateCurrent(revision: Long, mode: ConnectionMode, transform: (PersistentSnapshot) -> PersistentSnapshot): Boolean = synchronized(modeLock) {
        if (!foregroundRequested || generation != revision || store.snapshots.value.state.mode != mode) false
        else { store.update(transform); true }
    }
    private fun recover() {
        if (store.writable) safely { store.update { it.copy(state = CompanionQueue.recoverAfterRestart(it.state, it.syntheticRequestIds)) } }
    }
    private fun safely(action: () -> Unit) {
        try { action(); issue.value = null }
        catch (_: Exception) { issue.value = store.loadError ?: "Could not save the watch queue. Check message length, queue capacity or storage." }
    }
}

class WatchPhoneListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchWire.ACK_PATH && event.path != WatchWire.STATE_PATH) return
        try { runBlocking { (application as WatchApplication).repository.receivePhone(event) } }
        catch (_: Exception) { /* Untrusted or malformed packets are ignored without logging content. */ }
    }
}

package dev.dotcompanion.mobile

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.dotcompanion.core.CompanionMessage
import dev.dotcompanion.core.CompanionQueue
import dev.dotcompanion.core.ConnectionMode
import dev.dotcompanion.core.MessageStatus
import dev.dotcompanion.core.ReplyProvenance
import dev.dotcompanion.core.MessageSource
import dev.dotcompanion.shared.DurableStateStore
import dev.dotcompanion.shared.PersistentSnapshot
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

data class PhoneUiState(val saved: PersistentSnapshot, val issue: String? = null, val connecting: Boolean = false)

class CompanionApplication : Application() {
    val repository: PhoneRepository by lazy { PhoneRepository(this) }
}

interface PhoneWatchTransport {
    val available: Boolean
    suspend fun trustedWatch(nodeId: String): Boolean
    suspend fun send(nodeId: String, path: String, bytes: ByteArray)
}

private class GooglePhoneWatchTransport(private val context: Context) : PhoneWatchTransport {
    override val available get() = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    override suspend fun trustedWatch(nodeId: String) = available && Wearable.getCapabilityClient(context)
        .getCapability(WatchWire.WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes.any { it.id == nodeId }
    override suspend fun send(nodeId: String, path: String, bytes: ByteArray) {
        Wearable.getMessageClient(context).sendMessage(nodeId, path, bytes).await()
    }
}

class PhoneRepository(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val transportFactory: (String, String) -> BridgeTransport = { endpoint, token -> LocalBridgeClient(endpoint, token, BuildConfig.DEBUG) },
    private val watchTransport: PhoneWatchTransport = GooglePhoneWatchTransport(context),
    private val storeFactory: (File) -> DurableStateStore = { DurableStateStore(it) },
    private val onlineCheck: () -> Boolean = {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    },
) {
    private val store = storeFactory(File(context.filesDir, "companion-state-v1.json"))
    private val issue = MutableStateFlow(store.loadError)
    private val connecting = MutableStateFlow(false)
    val ui = combine(store.snapshots, issue, connecting) { saved, error, busy -> PhoneUiState(saved, error, busy) }
        .stateIn(scope, SharingStarted.Eagerly, PhoneUiState(store.snapshots.value, store.loadError))
    private data class BridgeSession(val generation: Long, val transport: BridgeTransport)
    private val configurationLock = Any()
    @Volatile private var configurationGeneration = 0L
    @Volatile private var client: BridgeSession? = null
    @Volatile private var foregroundRequested = false
    private val lifecycleLock = Mutex()
    private var worker: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    internal val saved get() = store.snapshots.value

    init {
        scope.launch {
            val lastSent = mutableMapOf<String, String>()
            store.snapshots.collect { snapshot ->
                snapshot.peerNodeIds.forEach { (requestId, nodeId) ->
                    val message = snapshot.state.messages.find { it.requestId == requestId } ?: return@forEach
                    val data = WatchWire.state(message, snapshot.state.mode, snapshot.state.online)
                    val fingerprint = data.contentHashCode().toString() + nodeId
                    if (lastSent[requestId] != fingerprint && watchTransport.available) {
                        try {
                            watchTransport.send(nodeId, WatchWire.STATE_PATH, data)
                            lastSent[requestId] = fingerprint
                        } catch (_: Exception) { /* The watch resends its original ID to recover status. */ }
                    }
                }
                lastSent.keys.retainAll(snapshot.peerNodeIds.keys)
            }
        }
    }

    fun startActive() {
        foregroundRequested = true
        scope.launch { lifecycleLock.withLock { if (foregroundRequested) startWorkerLocked() } }
    }

    private fun startWorkerLocked() {
        if (worker?.isActive == true) return
        recoverQueue()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { refreshNetwork() }
            override fun onLost(network: Network) { refreshNetwork() }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { refreshNetwork() }
        }
        if (networkCallback == null) {
            networkCallback = callback
            try { connectivity.registerDefaultNetworkCallback(callback) } catch (_: Exception) { }
        }
        refreshNetwork()
        worker = scope.launch {
            while (isActive) {
                try { processQueue() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { issue.value = store.loadError ?: "Synchronization paused. Your local queue is preserved." }
                delay(1500)
            }
        }
    }

    fun stopActive() {
        foregroundRequested = false
        scope.launch { lifecycleLock.withLock {
            if (foregroundRequested) return@withLock
            worker?.cancelAndJoin()
            worker = null
            unregisterNetwork()
            recoverQueue()
        } }
    }

    fun send(text: String) = scope.launch {
        safely {
            val message = CompanionMessage(UUID.randomUUID().toString(), CompanionQueue.CONVERSATION_ID, text.trim(), Instant.now().toString())
            store.update { snapshot ->
                val preview = snapshot.state.mode == ConnectionMode.LOCAL_PREVIEW
                snapshot.copy(state = CompanionQueue.enqueue(snapshot.state, message),
                    syntheticRequestIds = if (preview) snapshot.syntheticRequestIds + message.requestId else snapshot.syntheticRequestIds)
            }
        }
    }

    fun selectMode(mode: ConnectionMode) {
        if (mode == ConnectionMode.LOCAL_BRIDGE) return
        val generation = invalidateConfiguration()
        scope.launch { synchronized(configurationLock) {
            if (generation == configurationGeneration) safely { store.update {
                it.copy(state = CompanionQueue.recoverAfterRestart(it.state, it.syntheticRequestIds).copy(mode = mode))
            } }
        } }
    }

    fun connect(endpoint: String, token: String) {
        if (!BuildConfig.DEBUG) return
        val generation = invalidateConfiguration()
        connecting.value = true
        scope.launch {
        try {
            val candidate = transportFactory(endpoint, token)
            candidate.verifyStatus()
            currentCoroutineContext().ensureActive()
            synchronized(configurationLock) {
                if (generation == configurationGeneration) {
                    store.update { it.copy(state = CompanionQueue.recoverAfterRestart(it.state, it.syntheticRequestIds).copy(mode = ConnectionMode.LOCAL_BRIDGE)) }
                    client = BridgeSession(generation, candidate)
                    issue.value = null
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (generation == configurationGeneration) issue.value = "Local bridge connection failed. Check the endpoint and development token."
        } finally {
            if (generation == configurationGeneration) connecting.value = false
        }
        }
    }

    fun retry(requestId: String) = scope.launch { safely { store.update { it.copy(state = CompanionQueue.retry(it.state, requestId)) } } }
    fun deleteHistory() {
        invalidateConfiguration()
        scope.launch { lifecycleLock.withLock {
            worker?.cancelAndJoin()
            worker = null
            unregisterNetwork()
            try {
                synchronized(configurationLock) {
                    store.deleteHistory(ConnectionMode.DISCONNECTED)
                }
                issue.value = null
            } catch (_: Exception) {
                issue.value = "Deletion could not be confirmed. Displayed history is retained; restart before editing it."
            }
            if (foregroundRequested) startWorkerLocked()
        } }
    }

    suspend fun receiveWatch(event: MessageEvent) {
        if (event.path != WatchWire.REQUEST_PATH || !watchTransport.available) return
        val message = WatchWire.decodeRequest(event.data).asMessage()
        require(withTimeout(5000) { watchTransport.trustedWatch(event.sourceNodeId) }) { "Unknown watch peer" }
        val saved = try { store.update { snapshot ->
            val oldPeer = snapshot.peerNodeIds[message.requestId]
            require(oldPeer == null || oldPeer == event.sourceNodeId) { "Request belongs to another peer" }
            require(snapshot.state.messages.find { it.requestId == message.requestId }?.source.let { it == null || it == MessageSource.WATCH }) { "Request belongs to the phone" }
            val isNewPreview = snapshot.state.mode == ConnectionMode.LOCAL_PREVIEW && snapshot.state.messages.none { it.requestId == message.requestId }
            snapshot.copy(state = CompanionQueue.enqueue(snapshot.state, message),
                peerNodeIds = snapshot.peerNodeIds + (message.requestId to event.sourceNodeId),
                syntheticRequestIds = if (isNewPreview) snapshot.syntheticRequestIds + message.requestId else snapshot.syntheticRequestIds)
        } } catch (error: Exception) { issue.value = store.loadError ?: "The watch request could not be saved. No receipt was issued."; throw error }
        // Only the completed atomic write above authorizes this acknowledgement.
        withTimeout(5000) { watchTransport.send(event.sourceNodeId, WatchWire.ACK_PATH, WatchWire.receipt(message.requestId)) }
        val queued = saved.state.messages.first { it.requestId == message.requestId }
        withTimeout(5000) { watchTransport.send(event.sourceNodeId, WatchWire.STATE_PATH, WatchWire.state(queued, saved.state.mode, saved.state.online)) }
    }

    private suspend fun processQueue() {
        val snapshot = store.snapshots.value
        if (!store.writable || snapshot.state.mode == ConnectionMode.DISCONNECTED) return
        if (snapshot.state.mode == ConnectionMode.LOCAL_PREVIEW) {
            val preview = snapshot.state.messages.firstOrNull { it.requestId in snapshot.syntheticRequestIds && it.status == MessageStatus.QUEUED } ?: return
            val generation = configurationGeneration
            if (!updatePreview(generation) { it.copy(state = CompanionQueue.startAttempt(it.state.copy(online = true), preview.requestId, System.currentTimeMillis()).copy(online = it.state.online)) }) return
            if (store.snapshots.value.state.messages.first { it.requestId == preview.requestId }.status != MessageStatus.SENDING) return
            delay(250)
            if (!updatePreview(generation) { it.copy(state = CompanionQueue.accepted(it.state, preview.requestId)) }) return
            delay(350)
            updatePreview(generation) { it.copy(state = CompanionQueue.replied(it.state, preview.requestId,
                "[Synthetic fixture] This reply was generated locally to preview the interface. No dot, model or network call produced it.", ReplyProvenance.LOCAL_SYNTHETIC)) }
            return
        }
        val activeClient = client ?: return
        if (!snapshot.state.online) return
        snapshot.state.messages.filter { it.requestId !in snapshot.syntheticRequestIds && it.status == MessageStatus.QUEUED }.forEach { message ->
            val current = updateSession(activeClient) { it.copy(state = CompanionQueue.startAttempt(it.state, message.requestId, System.currentTimeMillis())) } ?: return
            val sending = current.state.messages.first { it.requestId == message.requestId }
            if (sending.status != MessageStatus.SENDING) return@forEach
            try {
                activeClient.transport.submit(sending)
                currentCoroutineContext().ensureActive()
                updateSession(activeClient) { it.copy(state = CompanionQueue.accepted(it.state, message.requestId)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (updateSession(activeClient) { it.copy(state = CompanionQueue.transportFailed(it.state, message.requestId, System.currentTimeMillis())) } != null)
                    issue.value = "Local bridge unavailable. Delivery retries are bounded; messages remain saved."
            }
        }
        if (store.snapshots.value.state.messages.none { it.status == MessageStatus.AWAITING_REPLY && it.requestId !in store.snapshots.value.syntheticRequestIds }) return
        try {
            val remote = activeClient.transport.messages()
            currentCoroutineContext().ensureActive()
            val updated = updateSession(activeClient) { current ->
                var state = current.state
                remote.forEach { response ->
                    val local = state.messages.find { it.requestId == response.requestId } ?: return@forEach
                    if (response.requestId in current.syntheticRequestIds) return@forEach
                    if (local.status != MessageStatus.AWAITING_REPLY && local.status != MessageStatus.REPLIED) return@forEach
                    require(local.text == response.text && local.createdAt == response.createdAt && local.conversationId == response.conversationId)
                    state = when (response.status) {
                        MessageStatus.REPLIED -> CompanionQueue.replied(state, response.requestId, requireNotNull(response.reply), ReplyProvenance.LOCAL_BRIDGE)
                        MessageStatus.FAILED -> CompanionQueue.remoteFailed(state, response.requestId)
                        else -> state
                    }
                }
                current.copy(state = state)
            }
            if (updated != null) issue.value = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (client === activeClient) issue.value = "Waiting for the local bridge. A receipt is not a reply." }
    }

    private fun refreshNetwork() = scope.launch {
        if (!store.writable || !foregroundRequested) return@launch
        val online = onlineCheck()
        if (store.snapshots.value.state.online != online) safely { store.update { it.copy(state = it.state.copy(online = online)) } }
    }

    private fun safely(action: () -> Unit) {
        try { action(); issue.value = null }
        catch (_: Exception) { issue.value = store.loadError ?: "Could not update the saved queue. Check the message length, queue limit or device storage." }
    }
    private fun invalidateConfiguration(): Long = synchronized(configurationLock) {
        configurationGeneration += 1
        client = null
        connecting.value = false
        configurationGeneration
    }

    private fun recoverQueue() {
        if (store.writable) safely { store.update { it.copy(state = CompanionQueue.recoverAfterRestart(it.state, it.syntheticRequestIds)) } }
    }

    private fun unregisterNetwork() {
        networkCallback?.let { try { connectivity.unregisterNetworkCallback(it) } catch (_: Exception) { } }
        networkCallback = null
    }

    private fun updateSession(session: BridgeSession, transform: (PersistentSnapshot) -> PersistentSnapshot): PersistentSnapshot? = synchronized(configurationLock) {
        if (client !== session || configurationGeneration != session.generation || !foregroundRequested) null else store.update(transform)
    }

    private fun updatePreview(generation: Long, transform: (PersistentSnapshot) -> PersistentSnapshot): Boolean = synchronized(configurationLock) {
        if (generation != configurationGeneration || !foregroundRequested || store.snapshots.value.state.mode != ConnectionMode.LOCAL_PREVIEW) false
        else { store.update(transform); true }
    }
}

class PhoneWearListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchWire.REQUEST_PATH) return
        try { runBlocking { (application as CompanionApplication).repository.receiveWatch(event) } }
        catch (_: Exception) { /* Invalid/unsaved packets are not acknowledged; no message text is logged. */ }
    }
}

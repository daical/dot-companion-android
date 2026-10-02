package dev.dotcompanion.shared

import dev.dotcompanion.core.CompanionMessage
import dev.dotcompanion.core.CompanionQueue
import dev.dotcompanion.core.CompanionState
import dev.dotcompanion.core.ConnectionMode
import dev.dotcompanion.core.MessageSource
import dev.dotcompanion.core.MessageStatus
import dev.dotcompanion.core.ReplyProvenance
import dev.dotcompanion.core.FailureKind
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class PersistentSnapshot(
    val state: CompanionState = CompanionState(),
    val syntheticRequestIds: Set<String> = emptySet(),
    val phoneAcknowledgedIds: Set<String> = emptySet(),
    val peerNodeIds: Map<String, String> = emptyMap(),
    val phoneReportedFailureIds: Set<String> = emptySet(),
)

/** Every persistence boundary throws on failure; no Android AtomicFile silent-error helpers. */
interface StateFileOperations {
    fun exists(file: File): Boolean
    fun requireRegular(file: File)
    fun syncFile(stream: FileOutputStream)
    fun rename(source: File, target: File)
    fun syncDirectory(directory: File)
    fun delete(file: File)
}

object CheckedStateFileOperations : StateFileOperations {
    override fun exists(file: File): Boolean = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        true
    } catch (_: NoSuchFileException) { false }

    override fun requireRegular(file: File) {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isRegularFile && !attributes.isSymbolicLink) { "Unsafe history entry" }
    }
    override fun syncFile(stream: FileOutputStream) { stream.fd.sync() }
    override fun rename(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    override fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
    override fun delete(file: File) {
        Files.deleteIfExists(file.toPath())
        check(!exists(file)) { "History entry was not removed" }
    }
}

/** Publish only after file sync, atomic rename, byte verification, and parent-directory sync. */
class DurableStateStore(private val file: File, private val operations: StateFileOperations = CheckedStateFileOperations) {
    private val temporary = File(file.path + ".new")
    private val backup = File(file.path + ".bak")
    private val parent = requireNotNull(file.absoluteFile.parentFile)
    private val lock = Any()
    @Volatile var loadError: String? = null
        private set
    @Volatile private var recoveryRequired = false
    val writable get() = loadError == null && !recoveryRequired
    private val mutable = MutableStateFlow(load())
    val snapshots: StateFlow<PersistentSnapshot> = mutable.asStateFlow()

    fun update(transform: (PersistentSnapshot) -> PersistentSnapshot): PersistentSnapshot = synchronized(lock) {
        check(writable) { "History needs recovery before another mutation" }
        val next = transform(mutable.value)
        val bytes = StateCodec.encode(next)
        if (next == mutable.value) return@synchronized next
        try {
            listOf(file, temporary, backup).filter(operations::exists).forEach(operations::requireRegular)
            check(!operations.exists(backup)) { "A backup needs recovery before writing" }
            // A stale .new is removed only while a previously validated committed base exists.
            if (operations.exists(temporary)) {
                check(operations.exists(file)) { "An uncommitted history entry needs recovery" }
                operations.delete(temporary)
                operations.syncDirectory(parent)
            }
            FileOutputStream(temporary).use { stream -> stream.write(bytes); operations.syncFile(stream) }
            operations.rename(temporary, file)
            operations.requireRegular(file)
            val committed = file.inputStream().use { readBounded(it, StateCodec.MAX_FILE_BYTES) }
            check(committed.contentEquals(bytes)) { "Committed history did not match the intended bytes" }
            operations.syncDirectory(parent)
        } catch (error: Exception) {
            // Rename may already have happened. Never overwrite uncertain disk state from old memory.
            recoveryRequired = true
            loadError = "Local history commit could not be confirmed. Sending is paused; restart the app to recover."
            throw IOException("Local history commit was not confirmed", error)
        }
        mutable.value = next
        next
    }

    /** Called only from the explicit delete-history control. */
    fun deleteHistory(modeAfterDelete: ConnectionMode? = null) = synchronized(lock) {
        try {
            listOf(temporary, backup, file).forEach { if (operations.exists(it)) operations.delete(it) }
            check(listOf(file, temporary, backup).none(operations::exists)) { "History entries remain" }
            if (parent.isDirectory) operations.syncDirectory(parent)
        } catch (error: Exception) {
            // Keep the prior snapshot and loadError. Partial deletion blocks future stale-memory writes.
            recoveryRequired = true
            throw IOException("History deletion was not confirmed", error)
        }
        recoveryRequired = false
        loadError = null
        mutable.value = PersistentSnapshot(state = mutable.value.state.copy(messages = emptyList(), mode = modeAfterDelete ?: mutable.value.state.mode))
    }

    private fun load(): PersistentSnapshot = try {
        val present = listOf(file, temporary, backup).filter(operations::exists)
        if (present.isEmpty()) PersistentSnapshot() else {
            present.forEach(operations::requireRegular)
            if (operations.exists(backup)) {
                // Legacy AtomicFile backups win over an interrupted replacement.
                val recovered = backup.inputStream().use { readBounded(it, StateCodec.MAX_FILE_BYTES) }
                StateCodec.decode(recovered)
                operations.rename(backup, file)
                check(file.inputStream().use { readBounded(it, StateCodec.MAX_FILE_BYTES) }.contentEquals(recovered))
                operations.syncDirectory(parent)
            }
            check(operations.exists(file)) { "Only uncommitted history exists" }
            val stored = file.inputStream().use { StateCodec.decode(readBounded(it, StateCodec.MAX_FILE_BYTES)) }
            operations.syncDirectory(parent)
            if (operations.exists(temporary)) {
                operations.delete(temporary)
                operations.syncDirectory(parent)
            }
            stored.copy(state = CompanionQueue.recoverAfterRestart(stored.state, stored.syntheticRequestIds).let {
                // Development tokens are memory-only; a restart cannot reconnect a bridge.
                if (it.mode == ConnectionMode.LOCAL_BRIDGE) it.copy(mode = ConnectionMode.DISCONNECTED) else it
            })
        }
    } catch (_: Exception) {
        recoveryRequired = true
        loadError = "Saved history needs recovery. Sending is paused; restart or explicitly delete local history."
        PersistentSnapshot(state = CompanionState(mode = ConnectionMode.DISCONNECTED))
    }
}

object StateCodec {
    // JSON escaping can expand bounded text up to sixfold. Still a finite disk/read limit.
    const val MAX_FILE_BYTES = 12 * 1024 * 1024
    fun encode(snapshot: PersistentSnapshot): ByteArray {
        validate(snapshot)
        val state = snapshot.state
        val json = JSONObject().put("schemaVersion", 1).put("mode", state.mode.name).put("online", state.online)
            .put("messages", JSONArray(state.messages.map(::messageJson)))
            .put("syntheticRequestIds", JSONArray(snapshot.syntheticRequestIds.toList().sorted()))
            .put("phoneAcknowledgedIds", JSONArray(snapshot.phoneAcknowledgedIds.toList().sorted()))
            .put("peerNodeIds", JSONObject(snapshot.peerNodeIds))
            .put("phoneReportedFailureIds", JSONArray(snapshot.phoneReportedFailureIds.toList().sorted()))
        return json.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_FILE_BYTES) }
    }

    fun decode(bytes: ByteArray): PersistentSnapshot {
        require(bytes.size <= MAX_FILE_BYTES) { "Saved queue is too large" }
        val json = parseJsonObject(bytes)
        require(json.keys().asSequence().toSet() == setOf("schemaVersion", "mode", "online", "messages", "syntheticRequestIds", "phoneAcknowledgedIds", "peerNodeIds", "phoneReportedFailureIds"))
        require(json.requiredInt("schemaVersion") == 1) { "Unknown saved queue version" }
        val messages = json.getJSONArray("messages")
        require(messages.length() <= CompanionQueue.MAX_MESSAGES)
        val peers = json.getJSONObject("peerNodeIds")
        val snapshot = PersistentSnapshot(
            state = CompanionState(ConnectionMode.valueOf(json.requiredString("mode")),
                (0 until messages.length()).map { parseMessage(messages.getJSONObject(it)) }, json.requiredBoolean("online")),
            syntheticRequestIds = stringSet(json.getJSONArray("syntheticRequestIds")),
            phoneAcknowledgedIds = stringSet(json.getJSONArray("phoneAcknowledgedIds")),
            peerNodeIds = peers.keys().asSequence().associateWith { peers.requiredString(it) },
            phoneReportedFailureIds = stringSet(json.getJSONArray("phoneReportedFailureIds")),
        )
        validate(snapshot)
        return snapshot
    }

    private fun messageJson(message: CompanionMessage) = JSONObject()
        .put("requestId", message.requestId).put("conversationId", message.conversationId)
        .put("text", message.text).put("createdAt", message.createdAt).put("status", message.status.name)
        .put("reply", message.reply ?: JSONObject.NULL).put("attempts", message.attempts)
        .put("nextAttemptAtMillis", message.nextAttemptAtMillis).put("source", message.source.name)
        .put("replyProvenance", message.replyProvenance?.name ?: JSONObject.NULL)
        .put("failureKind", message.failureKind?.name ?: JSONObject.NULL)

    private fun parseMessage(json: JSONObject): CompanionMessage {
        require(json.keys().asSequence().toSet() == setOf("requestId", "conversationId", "text", "createdAt", "status", "reply", "attempts", "nextAttemptAtMillis", "source", "replyProvenance", "failureKind"))
        return CompanionMessage(
            requestId = json.requiredString("requestId"), conversationId = json.requiredString("conversationId"),
            text = json.requiredString("text"), createdAt = json.requiredString("createdAt"),
            status = MessageStatus.valueOf(json.requiredString("status")), reply = json.nullableString("reply"),
            attempts = json.requiredInt("attempts"), nextAttemptAtMillis = json.requiredLong("nextAttemptAtMillis"),
            source = MessageSource.valueOf(json.requiredString("source")),
            replyProvenance = json.nullableString("replyProvenance")?.let(ReplyProvenance::valueOf),
            failureKind = json.nullableString("failureKind")?.let(FailureKind::valueOf),
        )
    }

    private fun stringSet(array: JSONArray): Set<String> {
        require(array.length() <= CompanionQueue.MAX_MESSAGES)
        val values = (0 until array.length()).map { require(array.get(it) is String); array.get(it) as String }
        require(values.toSet().size == values.size)
        return values.toSet()
    }

    private fun validate(snapshot: PersistentSnapshot) {
        val messages = snapshot.state.messages
        require(messages.size <= CompanionQueue.MAX_MESSAGES)
        val ids = messages.map { it.requestId }.toSet()
        require(ids.size == messages.size)
        messages.forEach {
            CompanionQueue.validate(it)
            require(it.attempts in 0..CompanionQueue.MAX_ATTEMPTS && it.nextAttemptAtMillis >= 0)
            require(if (it.status == MessageStatus.REPLIED) it.reply != null && it.replyProvenance != null
                else it.reply == null && it.replyProvenance == null)
            require((it.status == MessageStatus.FAILED) == (it.failureKind != null))
            it.reply?.let { reply -> require(reply.isNotBlank() && reply.toByteArray(Charsets.UTF_8).size <= CompanionQueue.MAX_TEXT_BYTES) }
        }
        require(ids.containsAll(snapshot.syntheticRequestIds) && ids.containsAll(snapshot.phoneAcknowledgedIds))
        require(ids.containsAll(snapshot.peerNodeIds.keys))
        require(ids.containsAll(snapshot.phoneReportedFailureIds))
        require(snapshot.peerNodeIds.values.all { it.isNotBlank() && it.length <= 255 })
    }
}

fun readBounded(stream: java.io.InputStream, maximum: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        require(output.size() + count <= maximum) { "Response exceeds the allowed size" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

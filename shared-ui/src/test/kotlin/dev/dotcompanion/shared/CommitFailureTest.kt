package dev.dotcompanion.shared

import dev.dotcompanion.core.CompanionMessage
import dev.dotcompanion.core.CompanionQueue
import dev.dotcompanion.core.CompanionState
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CommitFailureTest {
    @get:Rule val temporary = TemporaryFolder()
    private val first = CompanionMessage("9f13e909-fb28-479a-9b99-de401517eaa1", CompanionQueue.CONVERSATION_ID, "Confirmed fixture", "2026-01-01T00:00:00Z")
    private val second = first.copy(requestId = "2a384828-547d-4c7f-ac26-46aa41998811", text = "Unacknowledged fixture")
    private enum class Fault { FILE_SYNC, BEFORE_RENAME, AFTER_RENAME, DIRECTORY_SYNC }
    private class Faults : StateFileOperations by CheckedStateFileOperations {
        var fail: Fault? = null
        var failDelete = false
        override fun syncFile(stream: FileOutputStream) {
            if (fail == Fault.FILE_SYNC) throw IOException("Injected file sync failure")
            CheckedStateFileOperations.syncFile(stream)
        }
        override fun rename(source: File, target: File) {
            if (fail == Fault.BEFORE_RENAME) throw IOException("Injected rename failure")
            CheckedStateFileOperations.rename(source, target)
            if (fail == Fault.AFTER_RENAME) throw IOException("Injected post-rename failure")
        }
        override fun syncDirectory(directory: File) {
            if (fail == Fault.DIRECTORY_SYNC) throw IOException("Injected directory sync failure")
            CheckedStateFileOperations.syncDirectory(directory)
        }
        override fun delete(file: File) {
            if (failDelete) throw IOException("Injected unlink failure")
            CheckedStateFileOperations.delete(file)
        }
    }
    @Test fun everyCommitFailureRetainsVisibleStateAndBlocksStaleMemoryWrites() {
        Fault.entries.forEach { fault ->
            val file = File(temporary.newFolder(fault.name), "queue.json")
            val operations = Faults()
            val store = DurableStateStore(file, operations)
            store.update { it.copy(state = CompanionQueue.enqueue(it.state, first)) }
            val before = store.snapshots.value
            operations.fail = fault
            assertThrows(IOException::class.java) { store.update { it.copy(state = CompanionQueue.enqueue(it.state, second)) } }
            assertEquals(before, store.snapshots.value)
            assertFalse(store.writable)
            assertNotNull(store.loadError)
            val observedBytes = if (file.exists()) file.readBytes() else byteArrayOf()
            assertThrows(IllegalStateException::class.java) { store.update { it.copy(state = it.state.copy(messages = emptyList())) } }
            assertArrayEquals(observedBytes, if (file.exists()) file.readBytes() else byteArrayOf())
            // Reopen with working operations resolves the observed atomic base, never stale memory.
            val reopened = DurableStateStore(file)
            assertNull(reopened.loadError)
            val expected = if (fault == Fault.FILE_SYNC || fault == Fault.BEFORE_RENAME) listOf(first) else listOf(first, second)
            assertEquals(expected, reopened.snapshots.value.state.messages)
        }
    }
    @Test fun failedUnlinkRetainsStateAndPriorReadErrorWithoutClaimingDeletion() {
        val file = File(temporary.root, "queue.json")
        val operations = Faults()
        val store = DurableStateStore(file, operations)
        store.update { it.copy(state = CompanionQueue.enqueue(it.state, first)) }
        val before = store.snapshots.value
        operations.failDelete = true
        assertThrows(IOException::class.java) { store.deleteHistory() }
        assertEquals(before, store.snapshots.value)
        assertNull(store.loadError)
        assertFalse(store.writable)
        assertTrue(file.exists())
        assertThrows(IllegalStateException::class.java) { store.update { it } }
        operations.failDelete = false
        store.deleteHistory()
        assertTrue(store.snapshots.value.state.messages.isEmpty())
        assertTrue(store.writable)
    }
    @Test fun uncertainDeletionAfterDirectorySyncFailureDoesNotClearVisibleHistory() {
        val file = File(temporary.root, "queue.json")
        val operations = Faults()
        val store = DurableStateStore(file, operations)
        store.update { it.copy(state = CompanionQueue.enqueue(it.state, first)) }
        val before = store.snapshots.value
        operations.fail = Fault.DIRECTORY_SYNC
        assertThrows(IOException::class.java) { store.deleteHistory() }
        assertEquals(before, store.snapshots.value)
        assertFalse(store.writable)
        assertNull(store.loadError)
    }
    @Test fun directoriesSymlinksAndUncommittedOnlyHistoryAreNeverMistakenForAbsence() {
        val directory = File(temporary.newFolder("directory"), "queue.json").apply { mkdir() }
        val directoryStore = DurableStateStore(directory)
        assertNotNull(directoryStore.loadError)
        assertThrows(IllegalStateException::class.java) { directoryStore.update { it } }
        val link = File(temporary.newFolder("symlink"), "queue.json")
        val target = File(temporary.root, "target.json").apply { writeBytes(StateCodec.encode(PersistentSnapshot())) }
        Files.createSymbolicLink(link.toPath(), target.toPath())
        assertNotNull(DurableStateStore(link).loadError)
        val uncommitted = File(temporary.newFolder("uncommitted"), "queue.json")
        File(uncommitted.path + ".new").writeBytes(StateCodec.encode(PersistentSnapshot()))
        assertNotNull(DurableStateStore(uncommitted).loadError)
        assertTrue(File(uncommitted.path + ".new").exists())
    }
    @Test @Config(sdk = [28]) fun validLegacyBackupRestoresBeforeStaleTemporaryIsDiscarded() {
        val file = File(temporary.root, "queue.json").apply { writeText("interrupted fixture") }
        val recovered = PersistentSnapshot(CompanionState(messages = listOf(first)))
        File(file.path + ".bak").writeBytes(StateCodec.encode(recovered))
        File(file.path + ".new").writeText("stale fixture")
        val store = DurableStateStore(file)
        assertNull(store.loadError)
        assertEquals(recovered, store.snapshots.value)
        assertEquals(recovered, DurableStateStore(file).snapshots.value)
        assertFalse(File(file.path + ".bak").exists())
        assertFalse(File(file.path + ".new").exists())
    }
    @Test fun failedDeletePreservesAnExistingCorruptionError() {
        val file = File(temporary.root, "queue.json").apply { writeText("corrupted fixture") }
        val operations = Faults().apply { failDelete = true }
        val store = DurableStateStore(file, operations)
        val before = store.loadError
        assertNotNull(before)
        assertThrows(IOException::class.java) { store.deleteHistory() }
        assertEquals(before, store.loadError)
        assertEquals("corrupted fixture", file.readText())
    }
}

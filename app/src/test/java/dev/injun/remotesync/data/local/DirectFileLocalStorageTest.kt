package dev.injun.remotesync.data.local

import dev.injun.remotesync.core.exec.SyncExecutor
import dev.injun.remotesync.core.exec.SyncResult
import dev.injun.remotesync.core.model.Snapshot
import dev.injun.remotesync.core.port.AncestorRecord
import dev.injun.remotesync.core.port.AncestorStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Exercises the real [DirectFileLocalStorage] (actual files, atomic temp+rename) end
 * to end through the tested [SyncExecutor]. Two temp directories stand in for the two
 * replicas, so this validates the production local-storage path on a real filesystem
 * without needing Android, SMB, or a device.
 */
class DirectFileLocalStorageTest {

    private class MemAncestor : AncestorStore {
        private val map = HashMap<String, AncestorRecord>()
        override suspend fun load() = map.toMap()
        override suspend fun put(path: String, record: AncestorRecord?) {
            if (record == null) map.remove(path) else map[path] = record
        }
    }

    private fun write(dir: File, path: String, content: String) =
        File(dir, path).apply { parentFile?.mkdirs() }.writeText(content)

    private fun read(dir: File, path: String): String? =
        File(dir, path).let { if (it.isFile) it.readText() else null }

    @Test
    fun `two-way sync on real files converges and preserves conflicts`(
        @TempDir a: File,
        @TempDir b: File,
    ) = runTest {
        val local = DirectFileLocalStorage(a)
        val remote = DirectFileLocalStorage(b)
        val exec = SyncExecutor(local, remote, MemAncestor())

        // New nested file on local propagates to remote.
        write(a, "vault/db.kdbx", "v0")
        exec.sync()
        assertEquals("v0", read(b, "vault/db.kdbx"))

        // Divergent edits → conflict, both preserved on both sides.
        write(a, "vault/db.kdbx", "localEdit")
        write(b, "vault/db.kdbx", "remoteEdit")
        val result = exec.sync() as SyncResult.Success
        assertTrue(result.hadConflicts)
        val copy = requireNotNull(result.conflicts.single().conflictCopyPath)

        assertEquals("localEdit", read(a, "vault/db.kdbx"))
        assertEquals("localEdit", read(b, "vault/db.kdbx"))
        assertEquals("remoteEdit", read(a, copy))
        assertEquals("remoteEdit", read(b, copy))

        // Stable afterwards.
        assertEquals(0, (exec.sync() as SyncResult.Success).actionsApplied)
    }

    @Test
    fun `atomic writes leave no temp files behind`(@TempDir a: File, @TempDir b: File) = runTest {
        val exec = SyncExecutor(DirectFileLocalStorage(a), DirectFileLocalStorage(b), MemAncestor())
        write(a, "a.txt", "hello")
        write(a, "sub/b.txt", "world")
        exec.sync()

        val leftovers = b.walkTopDown().filter { it.isFile && it.name.contains(".tmp-") }.toList()
        assertTrue(leftovers.isEmpty(), "temp files left behind: $leftovers")
        assertEquals("hello", read(b, "a.txt"))
        assertEquals("world", read(b, "sub/b.txt"))
    }

    @Test
    fun `scan fails when the root is not a readable directory`(@TempDir a: File) = runTest {
        val storage = DirectFileLocalStorage(File(a, "missing"))
        val e = runCatching { storage.scan(Snapshot.EMPTY) }.exceptionOrNull()
        assertTrue(e is IOException, "expected IOException, got: $e")
    }

    @Test
    fun `scan hides only exact temp names, never look-alike user files`(@TempDir a: File) = runTest {
        write(a, "doc.txt", "x")
        write(a, ".doc.txt.tmp-123456", "half-written") // exact writeAtomic shape
        write(a, ".env.tmp-backup", "x") // suffix is not digits
        write(a, "x.tmp-5", "x") // no leading dot
        write(a, ".a.tmp-12x", "x") // digits followed by junk

        val snapshot = DirectFileLocalStorage(a).scan(Snapshot.EMPTY)
        assertEquals(setOf("doc.txt", ".env.tmp-backup", "x.tmp-5", ".a.tmp-12x"), snapshot.paths)
    }

    @Test
    fun `scan reaps stale orphaned temps but keeps fresh ones`(@TempDir a: File) = runTest {
        write(a, ".old.kdbx.tmp-1", "orphan")
        write(a, ".new.kdbx.tmp-2", "in flight")
        val old = File(a, ".old.kdbx.tmp-1")
        assertTrue(old.setLastModified(System.currentTimeMillis() - 2 * 60 * 60_000L))

        DirectFileLocalStorage(a).scan(Snapshot.EMPTY)
        assertFalse(old.exists(), "stale temp should have been removed")
        assertTrue(File(a, ".new.kdbx.tmp-2").exists(), "fresh temp must not be removed")
    }

    @Test
    fun `paths escaping the root are rejected and nothing is written outside it`(
        @TempDir parent: File,
    ) = runTest {
        val rootDir = File(parent, "root").apply { mkdirs() }
        val storage = DirectFileLocalStorage(rootDir)

        assertTrue(runCatching { storage.writeAtomic("../escape.txt", Buffer().writeUtf8("x")) }.isFailure)
        assertTrue(runCatching { storage.read("../escape.txt") }.isFailure)
        assertTrue(runCatching { storage.delete("../escape.txt") }.isFailure)

        assertFalse(File(parent, "escape.txt").exists(), "file escaped the sync root")
        assertTrue(parent.listFiles()!!.map { it.name } == listOf("root"), "unexpected files outside root")
    }
}

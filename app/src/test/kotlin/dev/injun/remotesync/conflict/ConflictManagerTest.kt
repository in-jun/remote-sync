package dev.injun.remotesync.conflict

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Exercises [ConflictManager] on a real temp filesystem. Resolution is the only code
 * that deletes or overwrites user files on purpose, so every case asserts the exact
 * byte content that survives, not just which paths exist.
 */
class ConflictManagerTest {

    private val manager = ConflictManager()

    private fun write(root: File, path: String, content: String): File =
        File(root, path).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    private fun read(root: File, path: String): String? =
        File(root, path).let { if (it.isFile) it.readText() else null }

    private suspend fun scanOne(root: File): ConflictItem =
        manager.scan(1L, "pair", root.path).single()

    private suspend fun assertStale(root: File, item: ConflictItem, resolution: ConflictResolution) {
        val e = runCatching { manager.resolve(root.path, item, resolution) }.exceptionOrNull()
        assertTrue(e is StaleConflictException, "expected StaleConflictException, got: $e")
    }

    // ---- scan ----

    @Test
    fun `scan finds conflict copies and captures the canonical state`(@TempDir root: File) = runTest {
        val canonical = write(root, "vault/db.kdbx", "local")
        write(root, "vault/db.kdbx.conflict-9f86d081", "remote")

        val item = scanOne(root)
        assertEquals("vault/db.kdbx", item.originalPath)
        assertEquals("vault/db.kdbx.conflict-9f86d081", item.conflictCopyPath)
        assertTrue(item.canonicalExists)
        assertEquals(canonical.length(), item.localSize)
        assertEquals(canonical.lastModified(), item.localMtime)
        assertEquals("local", item.localPreview)
        assertEquals("remote", item.remotePreview)
        assertEquals("db (conflict).kdbx", item.keepBothName)
    }

    @Test
    fun `scan ignores regular files and malformed conflict tags`(@TempDir root: File) = runTest {
        write(root, "plain.txt", "x")
        write(root, "a.txt.conflict-12345", "x") // tag too short
        write(root, "b.txt.conflict-123456789", "x") // tag too long
        write(root, "c.txt.conflict-9F86D081", "x") // uppercase hex
        write(root, "d.txt.conflict-notahex1", "x") // non-hex chars

        assertTrue(manager.scan(1L, "pair", root.path).isEmpty())
    }

    @Test
    fun `scan reports a missing canonical file`(@TempDir root: File) = runTest {
        write(root, "gone.txt.conflict-00000000", "remote")

        val item = scanOne(root)
        assertFalse(item.canonicalExists)
        assertEquals(0, item.localSize)
        assertEquals(null, item.localPreview)
    }

    // ---- resolve ----

    @Test
    fun `keep local deletes the copy and leaves the canonical bytes`(@TempDir root: File) = runTest {
        write(root, "db.kdbx", "local")
        write(root, "db.kdbx.conflict-9f86d081", "remote")
        val item = scanOne(root)

        manager.resolve(root.path, item, ConflictResolution.KEEP_LOCAL)

        assertEquals("local", read(root, "db.kdbx"))
        assertEquals(null, read(root, "db.kdbx.conflict-9f86d081"))
    }

    @Test
    fun `keep remote replaces the canonical bytes and removes the copy`(@TempDir root: File) = runTest {
        write(root, "db.kdbx", "local")
        write(root, "db.kdbx.conflict-9f86d081", "remote")
        val item = scanOne(root)

        manager.resolve(root.path, item, ConflictResolution.KEEP_REMOTE)

        assertEquals("remote", read(root, "db.kdbx"))
        assertEquals(null, read(root, "db.kdbx.conflict-9f86d081"))
        val leftovers = root.walkTopDown().filter { it.isFile && it.name.contains(".tmp-") }.toList()
        assertTrue(leftovers.isEmpty(), "temp files left behind: $leftovers")
    }

    @Test
    fun `keep both renames the copy and touches nothing else`(@TempDir root: File) = runTest {
        write(root, "vault/db.kdbx", "local")
        write(root, "vault/db.kdbx.conflict-9f86d081", "remote")
        val item = scanOne(root)

        manager.resolve(root.path, item, ConflictResolution.KEEP_BOTH)

        assertEquals("local", read(root, "vault/db.kdbx"))
        assertEquals("remote", read(root, "vault/db (conflict).kdbx"))
        assertEquals(null, read(root, "vault/db.kdbx.conflict-9f86d081"))
    }

    @Test
    fun `keep both picks a numbered name when the first is taken`(@TempDir root: File) = runTest {
        write(root, "db.kdbx", "local")
        write(root, "db (conflict).kdbx", "earlier")
        write(root, "db.kdbx.conflict-9f86d081", "remote")
        val item = scanOne(root) // the earlier copy is a plain file, not a conflict

        manager.resolve(root.path, item, ConflictResolution.KEEP_BOTH)

        assertEquals("earlier", read(root, "db (conflict).kdbx"))
        assertEquals("remote", read(root, "db (conflict 2).kdbx"))
    }

    @Test
    fun `resolve is a no-op when the copy is already gone`(@TempDir root: File) = runTest {
        write(root, "db.kdbx", "local")
        write(root, "db.kdbx.conflict-9f86d081", "remote")
        val item = scanOne(root)
        assertTrue(File(root, "db.kdbx.conflict-9f86d081").delete())

        manager.resolve(root.path, item, ConflictResolution.KEEP_REMOTE)
        assertEquals("local", read(root, "db.kdbx"))
    }

    // ---- staleness re-check ----

    @Test
    fun `keep remote refuses when the canonical file changed after the scan`(@TempDir root: File) = runTest {
        write(root, "db.kdbx", "local")
        write(root, "db.kdbx.conflict-9f86d081", "old remote")
        val item = scanOne(root)

        // A background sync pulls a newer remote version into the canonical file.
        write(root, "db.kdbx", "newer remote content")

        assertStale(root, item, ConflictResolution.KEEP_REMOTE)
        assertEquals("newer remote content", read(root, "db.kdbx"))
        assertEquals("old remote", read(root, "db.kdbx.conflict-9f86d081"))
    }

    @Test
    fun `keep local refuses when the canonical file changed after the scan`(@TempDir root: File) = runTest {
        write(root, "db.kdbx", "local")
        write(root, "db.kdbx.conflict-9f86d081", "remote")
        val item = scanOne(root)

        val canonical = File(root, "db.kdbx")
        assertTrue(canonical.setLastModified(canonical.lastModified() + 5_000)) // same size, new mtime

        assertStale(root, item, ConflictResolution.KEEP_LOCAL)
        assertEquals("remote", read(root, "db.kdbx.conflict-9f86d081"))
    }

    @Test
    fun `resolve refuses when the canonical file appeared after the scan`(@TempDir root: File) = runTest {
        write(root, "db.kdbx.conflict-9f86d081", "remote")
        val item = scanOne(root)
        assertFalse(item.canonicalExists)

        write(root, "db.kdbx", "appeared later")

        assertStale(root, item, ConflictResolution.KEEP_REMOTE)
        assertEquals("appeared later", read(root, "db.kdbx"))
    }

    @Test
    fun `keep both still succeeds on a stale item because nothing is discarded`(@TempDir root: File) = runTest {
        write(root, "db.kdbx", "local")
        write(root, "db.kdbx.conflict-9f86d081", "remote")
        val item = scanOne(root)

        write(root, "db.kdbx", "newer remote content")

        manager.resolve(root.path, item, ConflictResolution.KEEP_BOTH)
        assertEquals("newer remote content", read(root, "db.kdbx"))
        assertEquals("remote", read(root, "db (conflict).kdbx"))
    }
}

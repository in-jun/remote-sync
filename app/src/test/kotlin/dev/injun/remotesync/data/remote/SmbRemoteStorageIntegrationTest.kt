package dev.injun.remotesync.data.remote

import dev.injun.remotesync.core.exec.SyncExecutor
import dev.injun.remotesync.core.exec.SyncResult
import dev.injun.remotesync.core.port.AncestorRecord
import dev.injun.remotesync.core.port.AncestorStore
import dev.injun.remotesync.data.local.DirectFileLocalStorage
import dev.injun.remotesync.sync.SmbConfig
import java.io.File
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * End-to-end validation of [SmbRemoteStorage] against a REAL SMB server. Opt-in:
 * skipped unless `-Dsmb.host=...` (and friends) are supplied, so the normal test run
 * stays hermetic. Locally we point it at a throwaway Samba container.
 *
 * This is the one piece of the app that couldn't be written against an in-memory
 * fake, so it gets a real server: it exercises connect, recursive listing, atomic
 * write (temp + server-side rename), read, move, delete, and a full two-way sync
 * (including a conflict) between a local folder and the share.
 */
class SmbRemoteStorageIntegrationTest {

    private lateinit var config: SmbConfig

    private class MemAncestor : AncestorStore {
        private val map = HashMap<String, AncestorRecord>()
        override suspend fun load() = map.toMap()
        override suspend fun put(path: String, record: AncestorRecord?) {
            if (record == null) map.remove(path) else map[path] = record
        }
    }

    @BeforeEach
    fun setup() {
        val host = System.getProperty("smb.host")
        assumeTrue(host != null, "SMB integration test skipped (no -Dsmb.host)")
        // Unique sub-directory per run so repeated runs don't interfere.
        config = SmbConfig(
            host = host,
            port = System.getProperty("smb.port", "445").toInt(),
            shareName = System.getProperty("smb.share", "sync"),
            username = System.getProperty("smb.user", "smbuser"),
            password = System.getProperty("smb.pass", "smbpass"),
            rootPath = "it-${System.nanoTime()}",
        )
    }

    private fun source(text: String) = Buffer().writeUtf8(text)

    private suspend fun readText(storage: SmbRemoteStorage, path: String): String =
        storage.read(path).buffer().use { it.readUtf8() }

    @Test
    fun `write, list, read, move, delete round-trip`() = runTest {
        SmbRemoteStorage(config).use { smb ->
            // write (creates nested dirs) → scan lists it → read returns same bytes
            smb.writeAtomic("vault/db.kdbx", source("secret-v1"))
            val snap = smb.scan()
            assertTrue(snap.contains("vault/db.kdbx"), "scan should list the written file")
            assertEquals("secret-v1", readText(smb, "vault/db.kdbx"))

            // atomic overwrite
            smb.writeAtomic("vault/db.kdbx", source("secret-v2"))
            assertEquals("secret-v2", readText(smb, "vault/db.kdbx"))

            // move
            smb.move("vault/db.kdbx", "vault/renamed.kdbx")
            assertEquals("secret-v2", readText(smb, "vault/renamed.kdbx"))
            assertNull(smb.stat("vault/db.kdbx"))

            // delete
            smb.delete("vault/renamed.kdbx")
            assertNull(smb.stat("vault/renamed.kdbx"))
        }
    }

    @Test
    fun `full two-way sync between local folder and SMB share`(@TempDir localDir: File) = runTest {
        val local = DirectFileLocalStorage(localDir)
        val ancestors = MemAncestor()

        // Seed a local file, sync up to the share.
        File(localDir, "notes/todo.txt").apply { parentFile?.mkdirs() }.writeText("buy milk")
        SmbRemoteStorage(config).use { remote ->
            SyncExecutor(local, remote, ancestors).sync()
            assertEquals("buy milk", readText(remote, "notes/todo.txt"))
        }

        // Diverge: local edits the file, remote gets a different edit; expect a conflict
        // that preserves both versions on both sides.
        File(localDir, "notes/todo.txt").writeText("buy milk and eggs")
        SmbRemoteStorage(config).use { remote ->
            remote.writeAtomic("notes/todo.txt", source("call the bank"))
            val result = SyncExecutor(local, remote, ancestors).sync() as SyncResult.Success
            assertTrue(result.hadConflicts, "divergent edits must produce a conflict")
            val copy = requireNotNull(result.conflicts.single().conflictCopyPath)

            assertEquals("buy milk and eggs", File(localDir, "notes/todo.txt").readText())
            assertEquals("buy milk and eggs", readText(remote, "notes/todo.txt"))
            assertEquals("call the bank", File(localDir, copy).readText())
            assertEquals("call the bank", readText(remote, copy))
        }
    }
}

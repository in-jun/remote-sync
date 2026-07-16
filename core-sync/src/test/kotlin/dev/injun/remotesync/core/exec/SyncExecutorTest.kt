package dev.injun.remotesync.core.exec

import dev.injun.remotesync.core.fake.InMemoryAncestorStore
import dev.injun.remotesync.core.fake.InMemoryStorage
import dev.injun.remotesync.core.model.ConflictKind
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncExecutorTest {

    private fun fixture(): Triple<InMemoryStorage, InMemoryStorage, InMemoryAncestorStore> =
        Triple(InMemoryStorage(), InMemoryStorage(), InMemoryAncestorStore())

    @Test
    fun `new local file is pushed to remote`() = runTest {
        val (local, remote, anc) = fixture()
        local.seed("a.txt", "hello")
        val exec = SyncExecutor(local, remote, anc)

        val result = exec.sync() as SyncResult.Success

        assertEquals("hello", remote.contentOf("a.txt"))
        assertTrue(!result.hadConflicts)
        // Fixed point: a second sync does nothing.
        assertEquals(0, (exec.sync() as SyncResult.Success).actionsApplied)
    }

    @Test
    fun `remote deletion propagates to local`() = runTest {
        val (local, remote, anc) = fixture()
        local.seed("a.txt", "hello")
        val exec = SyncExecutor(local, remote, anc)
        exec.sync() // establish ancestor, push to remote

        remote.delete("a.txt") // remote side removes it
        exec.sync()

        assertNull(local.contentOf("a.txt"))
        assertEquals(emptySet<String>(), local.paths())
    }

    @Test
    fun `modify-modify conflict preserves both versions on both sides`() = runTest {
        val (local, remote, anc) = fixture()
        local.seed("db.kdbx", "v0")
        val exec = SyncExecutor(local, remote, anc)
        exec.sync() // both = v0, ancestor = v0

        // Diverge: each side edits to different content.
        local.seed("db.kdbx", "local-edit")
        remote.seed("db.kdbx", "remote-edit")

        val result = exec.sync() as SyncResult.Success

        assertTrue(result.hadConflicts)
        val report = result.conflicts.single()
        assertEquals(ConflictKind.MODIFY_MODIFY, report.kind)
        val copy = requireNotNull(report.conflictCopyPath)

        // Local's version stays canonical; remote's is preserved beside it.
        assertEquals("local-edit", local.contentOf("db.kdbx"))
        assertEquals("local-edit", remote.contentOf("db.kdbx"))
        assertEquals("remote-edit", local.contentOf(copy))
        assertEquals("remote-edit", remote.contentOf(copy))

        // Nothing left to do afterwards.
        assertEquals(0, (exec.sync() as SyncResult.Success).actionsApplied)
    }

    @Test
    fun `modify-delete conflict keeps the modification on both sides`() = runTest {
        val (local, remote, anc) = fixture()
        local.seed("note.txt", "v0")
        val exec = SyncExecutor(local, remote, anc)
        exec.sync()

        local.seed("note.txt", "edited")   // local modifies
        remote.delete("note.txt")           // remote deletes

        val result = exec.sync() as SyncResult.Success
        val report = result.conflicts.single()
        assertEquals(ConflictKind.MODIFY_DELETE, report.kind)
        assertNull(report.conflictCopyPath)

        assertEquals("edited", local.contentOf("note.txt"))
        assertEquals("edited", remote.contentOf("note.txt"))
        assertEquals(0, (exec.sync() as SyncResult.Success).actionsApplied)
    }

    @Test
    fun `identical edits on both sides converge without a conflict`() = runTest {
        val (local, remote, anc) = fixture()
        local.seed("a.txt", "v0")
        val exec = SyncExecutor(local, remote, anc)
        exec.sync()

        local.seed("a.txt", "same-new")
        remote.seed("a.txt", "same-new")

        val result = exec.sync() as SyncResult.Success
        assertTrue(!result.hadConflicts)
        assertEquals("same-new", remote.contentOf("a.txt"))
        assertEquals(0, (exec.sync() as SyncResult.Success).actionsApplied)
    }
}

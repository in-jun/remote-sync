package dev.injun.remotesync.core.exec

import dev.injun.remotesync.core.fake.InMemoryAncestorStore
import dev.injun.remotesync.core.fake.InMemoryStorage
import dev.injun.remotesync.core.model.ConflictKind
import dev.injun.remotesync.core.safety.MaxDeleteGuard
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
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
    fun `remote coming back empty aborts before touching local files`() = runTest {
        val (local, remote, anc) = fixture()
        for (i in 1..6) local.seed("f$i.txt", "content-$i")
        val exec = SyncExecutor(local, remote, anc)
        exec.sync() // baseline: all files on both sides, ancestors recorded

        // Remote scan returns nothing (e.g. the share failed to mount), so the
        // plan would delete every local file. The guard must refuse the pass.
        for (p in remote.paths()) remote.deleteForTest(p)
        val ancestorsBefore = anc.load()

        val result = exec.sync()

        val aborted = assertInstanceOf(SyncResult.Aborted::class.java, result)
        assertEquals(MaxDeleteGuard.Side.LOCAL, aborted.verdict.side)
        assertEquals((1..6).map { "f$it.txt" }.toSet(), local.paths())
        for (i in 1..6) assertEquals("content-$i", local.contentOf("f$i.txt"))
        assertEquals(ancestorsBefore, anc.load())
    }

    @Test
    fun `remote deletions under the safety threshold still propagate`() = runTest {
        val (local, remote, anc) = fixture()
        for (i in 1..12) local.seed("f$i.txt", "content-$i")
        val exec = SyncExecutor(local, remote, anc)
        exec.sync()

        // 5 of 12 (~42%) is over the minimum trigger but under the 50% threshold.
        for (i in 1..5) remote.deleteForTest("f$i.txt")

        val result = exec.sync() as SyncResult.Success

        assertEquals(5, result.actionsApplied)
        assertEquals((6..12).map { "f$it.txt" }.toSet(), local.paths())
    }

    @Test
    fun `local delete is skipped when the file was edited after the scan`() = runTest {
        val (local, remote, anc) = fixture()
        local.seed("a.txt", "v0")
        val exec = SyncExecutor(local, remote, anc)
        exec.sync()
        remote.deleteForTest("a.txt") // remote deletion → plan: delete local

        // The file is re-edited between the scan and the apply loop.
        remote.afterScanForTest = { local.seed("a.txt", "edited-after-scan") }
        val ancestorsBefore = anc.load()

        val result = exec.sync() as SyncResult.Success

        assertEquals(listOf("a.txt"), result.skippedPaths)
        assertEquals(0, result.actionsApplied)
        assertEquals("edited-after-scan", local.contentOf("a.txt"))
        assertEquals(ancestorsBefore["a.txt"], anc.load()["a.txt"])

        // Next pass sees the edit and re-plans it as modify-vs-delete.
        remote.afterScanForTest = null
        val next = exec.sync() as SyncResult.Success
        assertEquals(ConflictKind.MODIFY_DELETE, next.conflicts.single().kind)
        assertEquals("edited-after-scan", local.contentOf("a.txt"))
        assertEquals("edited-after-scan", remote.contentOf("a.txt"))
    }

    @Test
    fun `modify-delete resolution is skipped when the remote file reappears after the scan`() = runTest {
        val (local, remote, anc) = fixture()
        local.seed("note.txt", "v0")
        val exec = SyncExecutor(local, remote, anc)
        exec.sync()

        local.seed("note.txt", "edited")
        remote.deleteForTest("note.txt")
        // The remote file is re-created between the scan and the apply loop;
        // writing over it would destroy content no snapshot ever saw.
        remote.afterScanForTest = { remote.seed("note.txt", "reappeared") }
        val ancestorsBefore = anc.load()

        val result = exec.sync() as SyncResult.Success

        assertEquals(listOf("note.txt"), result.skippedPaths)
        assertEquals(0, result.actionsApplied)
        assertEquals("reappeared", remote.contentOf("note.txt"))
        assertEquals(ancestorsBefore["note.txt"], anc.load()["note.txt"])

        // Next pass re-plans it as modify-modify and preserves both versions.
        remote.afterScanForTest = null
        val next = exec.sync() as SyncResult.Success
        val report = next.conflicts.single()
        assertEquals(ConflictKind.MODIFY_MODIFY, report.kind)
        assertEquals("edited", local.contentOf("note.txt"))
        val copy = requireNotNull(report.conflictCopyPath)
        assertEquals("reappeared", local.contentOf(copy))
    }

    @Test
    fun `path collision performs no IO, commits no ancestor, and is re-reported`() = runTest {
        val (local, remote, anc) = fixture()
        local.seed("Readme.md", "local-version")
        remote.seed("readme.md", "remote-version")
        val exec = SyncExecutor(local, remote, anc)

        val result = exec.sync() as SyncResult.Success

        assertTrue(result.hadConflicts)
        assertTrue(result.conflicts.isNotEmpty())
        assertTrue(result.conflicts.all { it.kind == ConflictKind.PATH_COLLISION })
        assertTrue(result.conflicts.all { it.conflictCopyPath == null })

        // No I/O in either direction: each side keeps exactly its own file, and no
        // conflict copy is materialized anywhere.
        assertEquals(setOf("Readme.md"), local.paths())
        assertEquals(setOf("readme.md"), remote.paths())
        assertEquals("local-version", local.contentOf("Readme.md"))
        assertEquals("remote-version", remote.contentOf("readme.md"))

        // No ancestor is committed, so the collision surfaces again next pass.
        assertTrue(anc.load().isEmpty())
        val again = exec.sync() as SyncResult.Success
        assertEquals(
            result.conflicts.map { it.path to it.kind }.toSet(),
            again.conflicts.map { it.path to it.kind }.toSet(),
        )
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

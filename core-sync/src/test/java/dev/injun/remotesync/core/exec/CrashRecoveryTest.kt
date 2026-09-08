package dev.injun.remotesync.core.exec

import dev.injun.remotesync.core.fake.FaultController
import dev.injun.remotesync.core.fake.InMemoryAncestorStore
import dev.injun.remotesync.core.fake.InMemoryStorage
import dev.injun.remotesync.core.fake.SimulatedCrash
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * The core no-data-loss proof (requirement #2). For each scenario we sweep the crash
 * point across *every* durable mutation of the sync: crash there, then resume, and
 * assert the replicas converge with nothing lost. Because every write is atomic,
 * every operation idempotent, and the ancestor committed only after durable I/O,
 * a resume from any interruption must succeed.
 */
class CrashRecoveryTest {

    private data class Fixture(
        val local: InMemoryStorage,
        val remote: InMemoryStorage,
        val ancestors: InMemoryAncestorStore,
    )

    /** All distinct content values currently stored on a side. */
    private fun contentsOf(s: InMemoryStorage): Set<String> =
        s.paths().mapNotNull { s.contentOf(it) }.toSet()

    /**
     * Run [setup] fresh for each crash budget (unarmed, so baseline state is built
     * cleanly), then arm the fault, crash the first sync at that point, resume to a
     * fixed point, and assert both sides are identical, every required content
     * survives on both, and no [forbiddenPaths] entry (a deleted file) reappears.
     *
     * A dry run first counts the durable mutations of one uninterrupted sync, so the
     * sweep covers exactly every crash point and can insist the injected crash really
     * aborted the sync for every in-range budget (a swallowed fault would silently
     * degrade the sweep into testing per-file failures instead of interruption).
     */
    private suspend fun sweepCrashPoints(
        requiredContents: Set<String>,
        forbiddenPaths: Set<String> = emptySet(),
        setup: suspend (FaultController) -> Fixture,
    ) {
        val probe = FaultController()
        val dry = setup(probe)
        probe.arm(Int.MAX_VALUE)
        SyncExecutor(dry.local, dry.remote, dry.ancestors).sync()
        val mutations = probe.stepsTaken
        assertTrue(mutations > 0, "scenario produced no durable mutations")

        for (budget in 0..mutations) {
            val fault = FaultController()
            val (local, remote, ancestors) = setup(fault)
            fault.arm(budget)
            val exec = SyncExecutor(local, remote, ancestors)

            // First attempt — must abort mid-sync at every in-range crash point.
            var crashed = false
            try {
                exec.sync()
            } catch (_: SimulatedCrash) {
                crashed = true
            }
            assertEquals(budget < mutations, crashed, "crash injection did not fire (budget=$budget of $mutations)")

            // Resume with faults off; run to a fixed point (must converge quickly).
            fault.disable()
            var passes = 0
            while (true) {
                val r = exec.sync()
                assertTrue(r is SyncResult.Success, "unexpected abort during resume (budget=$budget)")
                if ((r as SyncResult.Success).actionsApplied == 0) break
                if (++passes > 5) fail("did not converge after crash at budget=$budget")
            }

            val ctx = "budget=$budget crashed=$crashed"
            assertEquals(remote.hashesByPath(), local.hashesByPath(), "replicas diverged ($ctx)")
            assertTrue(contentsOf(local).containsAll(requiredContents), "local lost content ($ctx)")
            assertTrue(contentsOf(remote).containsAll(requiredContents), "remote lost content ($ctx)")
            for (path in forbiddenPaths) {
                assertTrue(path !in local.paths(), "deleted file resurrected locally: $path ($ctx)")
                assertTrue(path !in remote.paths(), "deleted file resurrected remotely: $path ($ctx)")
            }
        }
    }

    @Test
    fun `pushing many new files survives a crash at any step`() = runTest {
        val required = (1..8).map { "content$it" }.toSet()
        sweepCrashPoints(requiredContents = required) { fault ->
            val local = InMemoryStorage(fault)
            val remote = InMemoryStorage(fault)
            (1..8).forEach { local.seed("f$it.txt", "content$it") }
            Fixture(local, remote, InMemoryAncestorStore(fault))
        }
    }

    @Test
    fun `modify-modify conflict survives a crash at any step, both versions kept`() = runTest {
        val required = setOf("local-edit", "remote-edit")
        sweepCrashPoints(requiredContents = required) { fault ->
            val local = InMemoryStorage(fault)
            val remote = InMemoryStorage(fault)
            val anc = InMemoryAncestorStore(fault)
            // Clean baseline sync (fault still disarmed), then diverge both sides.
            local.seed("db.kdbx", "v0")
            SyncExecutor(local, remote, anc).sync()
            local.seed("db.kdbx", "local-edit")
            remote.seed("db.kdbx", "remote-edit")
            Fixture(local, remote, anc)
        }
    }

    @Test
    fun `mixed changes survive a crash at any step`() = runTest {
        val required = setOf("keep", "localNew", "remoteNew", "conflictL", "conflictR")
        sweepCrashPoints(requiredContents = required) { fault ->
            val local = InMemoryStorage(fault)
            val remote = InMemoryStorage(fault)
            val anc = InMemoryAncestorStore(fault)
            local.seed("keep.txt", "keep")
            remote.seed("keep.txt", "keep")
            local.seed("conf.txt", "v0")
            remote.seed("conf.txt", "v0")
            SyncExecutor(local, remote, anc).sync()
            // Diverge in several independent ways at once.
            local.seed("onlyL.txt", "localNew")
            remote.seed("onlyR.txt", "remoteNew")
            local.seed("conf.txt", "conflictL")
            remote.seed("conf.txt", "conflictR")
            Fixture(local, remote, anc)
        }
    }

    @Test
    fun `modify-vs-delete survives a crash at any step and the edit wins`() = runTest {
        // Pins the write-then-commit ordering for modify-vs-delete: if the commit ran
        // before the write, a crash in between would leave an ancestor claiming both
        // sides hold the edit while the target is still absent. The next pass would
        // then read that side as DELETED and propagate the deletion, destroying the
        // surviving edit. Covers both MODIFY_DELETE and its DELETE_MODIFY mirror.
        val required = setOf("kept-edit-l", "kept-edit-r")
        sweepCrashPoints(requiredContents = required) { fault ->
            val local = InMemoryStorage(fault)
            val remote = InMemoryStorage(fault)
            val anc = InMemoryAncestorStore(fault)
            local.seed("notes-l.txt", "v0")
            local.seed("notes-r.txt", "v0")
            SyncExecutor(local, remote, anc).sync()
            // Local edits a file the remote deletes; remote edits one the local deletes.
            local.seed("notes-l.txt", "kept-edit-l")
            remote.deleteForTest("notes-l.txt")
            remote.seed("notes-r.txt", "kept-edit-r")
            local.deleteForTest("notes-r.txt")
            Fixture(local, remote, anc)
        }
    }

    @Test
    fun `deletions survive a crash at any step and stay deleted`() = runTest {
        // Pins the delete-then-commit ordering: committing the ancestor first would,
        // on a crash in between, make the next pass see the survivor as CREATED and
        // push the deleted file back — resurrecting e.g. a revoked password database.
        sweepCrashPoints(
            requiredContents = setOf("keep"),
            forbiddenPaths = setOf("goneL1.txt", "goneL2.txt", "goneR1.txt", "goneR2.txt"),
        ) { fault ->
            val local = InMemoryStorage(fault)
            val remote = InMemoryStorage(fault)
            val anc = InMemoryAncestorStore(fault)
            local.seed("keep.txt", "keep")
            listOf("goneL1.txt", "goneL2.txt", "goneR1.txt", "goneR2.txt")
                .forEach { local.seed(it, "stale-$it") }
            SyncExecutor(local, remote, anc).sync()
            // Each side revokes files the other still holds.
            local.deleteForTest("goneL1.txt")
            local.deleteForTest("goneL2.txt")
            remote.deleteForTest("goneR1.txt")
            remote.deleteForTest("goneR2.txt")
            Fixture(local, remote, anc)
        }
    }
}

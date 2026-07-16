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
     * fixed point, and assert both sides are identical with every required content
     * surviving on both.
     */
    private suspend fun sweepCrashPoints(
        maxSteps: Int,
        requiredContents: Set<String>,
        setup: suspend (FaultController) -> Fixture,
    ) {
        for (budget in 0..maxSteps) {
            val fault = FaultController()
            val (local, remote, ancestors) = setup(fault)
            fault.arm(budget)
            val exec = SyncExecutor(local, remote, ancestors)

            // First attempt — may crash at the injected point.
            var crashed = false
            try {
                exec.sync()
            } catch (_: SimulatedCrash) {
                crashed = true
            }

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
        }
    }

    @Test
    fun `pushing many new files survives a crash at any step`() = runTest {
        val required = (1..8).map { "content$it" }.toSet()
        sweepCrashPoints(maxSteps = 40, requiredContents = required) { fault ->
            val local = InMemoryStorage(fault)
            val remote = InMemoryStorage(fault)
            (1..8).forEach { local.seed("f$it.txt", "content$it") }
            Fixture(local, remote, InMemoryAncestorStore(fault))
        }
    }

    @Test
    fun `modify-modify conflict survives a crash at any step, both versions kept`() = runTest {
        val required = setOf("local-edit", "remote-edit")
        sweepCrashPoints(maxSteps = 40, requiredContents = required) { fault ->
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
        sweepCrashPoints(maxSteps = 60, requiredContents = required) { fault ->
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
}

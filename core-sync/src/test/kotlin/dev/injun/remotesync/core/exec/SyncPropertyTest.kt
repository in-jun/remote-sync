package dev.injun.remotesync.core.exec

import dev.injun.remotesync.core.fake.InMemoryAncestorStore
import dev.injun.remotesync.core.fake.InMemoryStorage
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Randomized (property-based) hardening of the no-data-loss guarantee. Over many
 * rounds we apply random independent edits to both replicas — mimicking two apps
 * editing the shared folders between syncs — then sync and assert the invariants
 * that must always hold, whatever the sequence:
 *
 *  - **Convergence**: after syncing to a fixed point, both replicas are byte-identical.
 *  - **Stability**: an immediate re-sync produces no further actions.
 *  - **No silent loss**: every content that was created or modified on a side (with
 *    the other side untouched) survives on both replicas; and when both sides edited
 *    the same path differently, *both* versions survive.
 *
 * Runs are seeded, so any failure is deterministically reproducible.
 */
class SyncPropertyTest {

    private val paths = listOf("a.txt", "b.txt", "c.txt", "dir/d.txt", "dir/e.txt")

    private fun contentsOf(s: InMemoryStorage): Set<String> =
        s.paths().mapNotNull { s.contentOf(it) }.toSet()

    private suspend fun syncToFixedPoint(exec: SyncExecutor, ctx: String) {
        var passes = 0
        while (true) {
            val r = exec.sync()
            assertTrue(r is SyncResult.Success, "unexpected abort ($ctx)")
            if ((r as SyncResult.Success).actionsApplied == 0) break
            if (++passes > 6) fail("did not converge ($ctx)")
        }
    }

    @Test
    fun `random edit sequences never lose data and always converge`() = runTest {
        for (seed in 1..40) {
            val rng = Random(seed)
            val local = InMemoryStorage()
            val remote = InMemoryStorage()
            val ancestors = InMemoryAncestorStore()
            val exec = SyncExecutor(local, remote, ancestors)
            var version = 0

            for (round in 1..25) {
                // What each side looked like before this round's edits. Thanks to the
                // convergence assertion at the end of the previous round, both sides
                // equalled this agreed state, so it is a valid per-path baseline.
                val beforeLocal = paths.associateWith { local.contentOf(it) }

                // Apply random independent edits to each side.
                for (p in paths) {
                    if (rng.nextInt(3) == 0) mutate(local, p, rng) { "L${seed}_${round}_${version++}" }
                    if (rng.nextInt(3) == 0) mutate(remote, p, rng) { "R${seed}_${round}_${version++}" }
                }

                val ctx = "seed=$seed round=$round"

                // Compute what MUST survive under the no-loss policy, from pre-sync state.
                val mustSurvive = HashSet<String>()
                for (p in paths) {
                    val base = beforeLocal[p] // both sides equalled the agreed state before edits
                    val l = local.contentOf(p)
                    val r = remote.contentOf(p)
                    val localChanged = l != base
                    val remoteChanged = r != base
                    when {
                        localChanged && remoteChanged && l != r -> {
                            // Conflict: both surviving versions must be preserved.
                            l?.let(mustSurvive::add)
                            r?.let(mustSurvive::add)
                        }
                        localChanged && !remoteChanged -> l?.let(mustSurvive::add) // unilateral local edit
                        remoteChanged && !localChanged -> r?.let(mustSurvive::add) // unilateral remote edit
                        // both deleted / identical change / no change → nothing required
                    }
                }

                syncToFixedPoint(exec, ctx)

                // Convergence + stability.
                assertEquals(remote.hashesByPath(), local.hashesByPath(), "diverged ($ctx)")
                assertEquals(0, (exec.sync() as SyncResult.Success).actionsApplied, "not stable ($ctx)")

                // No silent loss.
                val localContents = contentsOf(local)
                val remoteContents = contentsOf(remote)
                for (needed in mustSurvive) {
                    assertTrue(needed in localContents, "local lost '$needed' ($ctx)")
                    assertTrue(needed in remoteContents, "remote lost '$needed' ($ctx)")
                }
            }
        }
    }

    /** Randomly create/modify (with fresh content) or delete a path. */
    private inline fun mutate(s: InMemoryStorage, path: String, rng: Random, newContent: () -> String) {
        if (rng.nextInt(4) == 0 && s.contentOf(path) != null) {
            s.deleteForTest(path)
        } else {
            s.seed(path, newContent())
        }
    }
}

package dev.injun.remotesync.core.safety

import dev.injun.remotesync.core.model.DeleteLocal
import dev.injun.remotesync.core.model.DeleteRemote
import dev.injun.remotesync.core.model.FileMeta
import dev.injun.remotesync.core.model.Snapshot
import dev.injun.remotesync.core.model.SyncPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MaxDeleteGuardTest {

    private fun meta(c: String) = FileMeta(c.length.toLong(), 0, "h:$c")

    private fun snapshotOf(n: Int): Snapshot =
        Snapshot((1..n).associate { "f$it" to meta("c$it") })

    private fun deleteLocalPlan(n: Int): SyncPlan =
        SyncPlan((1..n).map { DeleteLocal("f$it") })

    @Test
    fun `deletions under the minimum trigger are always allowed`() {
        // 4 of 4 = 100%, but below minDeletionsToTrigger(5) so not enforced.
        val guard = MaxDeleteGuard(threshold = 0.5, minDeletionsToTrigger = 5)
        val verdict = guard.check(deleteLocalPlan(4), local = snapshotOf(4), remote = Snapshot.EMPTY)
        assertEquals(SafetyVerdict.Ok, verdict)
    }

    @Test
    fun `mass local deletion over threshold aborts`() {
        // 8 of 10 = 80% > 50% and >= 5 deletions → abort.
        val guard = MaxDeleteGuard(threshold = 0.5, minDeletionsToTrigger = 5)
        val verdict = guard.check(deleteLocalPlan(8), local = snapshotOf(10), remote = Snapshot.EMPTY)
        val abort = assertInstanceOf(SafetyVerdict.Abort::class.java, verdict)
        assertEquals(MaxDeleteGuard.Side.LOCAL, abort.side)
        assertEquals(8, abort.deletions)
        assertEquals(10, abort.total)
        assertTrue(abort.message.contains("80%"))
    }

    @Test
    fun `deletions at or below threshold are allowed`() {
        // 5 of 10 = exactly 50%, not greater than threshold → ok.
        val guard = MaxDeleteGuard(threshold = 0.5, minDeletionsToTrigger = 5)
        val verdict = guard.check(deleteLocalPlan(5), local = snapshotOf(10), remote = Snapshot.EMPTY)
        assertEquals(SafetyVerdict.Ok, verdict)
    }

    @Test
    fun `exactly the trigger count over threshold aborts`() {
        // 5 of 6 = 83% > 50% and exactly minDeletionsToTrigger(5) → abort.
        // Pins the strict `<` boundary: with `<=` the guard would wrongly allow this.
        val guard = MaxDeleteGuard(threshold = 0.5, minDeletionsToTrigger = 5)
        val verdict = guard.check(deleteLocalPlan(5), local = snapshotOf(6), remote = Snapshot.EMPTY)
        val abort = assertInstanceOf(SafetyVerdict.Abort::class.java, verdict)
        assertEquals(MaxDeleteGuard.Side.LOCAL, abort.side)
        assertEquals(5, abort.deletions)
        assertEquals(6, abort.total)
    }

    @Test
    fun `one below the trigger count is allowed even over threshold`() {
        // 4 of 5 = 80% > 50% but below minDeletionsToTrigger(5) → ok.
        // Pins the other side of the boundary.
        val guard = MaxDeleteGuard(threshold = 0.5, minDeletionsToTrigger = 5)
        val verdict = guard.check(deleteLocalPlan(4), local = snapshotOf(5), remote = Snapshot.EMPTY)
        assertEquals(SafetyVerdict.Ok, verdict)
    }

    @Test
    fun `mass remote deletion is also guarded`() {
        val guard = MaxDeleteGuard(threshold = 0.5, minDeletionsToTrigger = 5)
        val plan = SyncPlan((1..9).map { DeleteRemote("f$it") })
        val verdict = guard.check(plan, local = Snapshot.EMPTY, remote = snapshotOf(10))
        val abort = assertInstanceOf(SafetyVerdict.Abort::class.java, verdict)
        assertEquals(MaxDeleteGuard.Side.REMOTE, abort.side)
    }
}

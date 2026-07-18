package dev.injun.remotesync.core.engine

import dev.injun.remotesync.core.model.Conflict
import dev.injun.remotesync.core.model.ConflictKind
import dev.injun.remotesync.core.model.Converged
import dev.injun.remotesync.core.model.DeleteLocal
import dev.injun.remotesync.core.model.DeleteRemote
import dev.injun.remotesync.core.model.FileMeta
import dev.injun.remotesync.core.model.Pull
import dev.injun.remotesync.core.model.Push
import dev.injun.remotesync.core.model.Snapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exhaustive coverage of the reconciliation decision table. Each test pins down one
 * cell of (localΔ × remoteΔ) and asserts both the action type and the ancestor it
 * would record — the ancestor being what guarantees the next sync stays correct.
 */
class SyncEngineTest {

    private val engine = SyncEngine()

    /** Content identity is the hash only, so a distinct string == distinct content. */
    private fun meta(content: String): FileMeta =
        FileMeta(size = content.length.toLong(), mtimeMillis = 0, contentHash = "h:$content")

    private val P = "vault/passwords.kdbx"

    private fun reconcile(
        local: Snapshot,
        remote: Snapshot,
        ancestor: Snapshot,
    ) = engine.reconcile(local, remote, ancestor)

    // ---- Neither side changed ----

    @Test
    fun `both unchanged produces no action`() {
        val m = meta("v1")
        val plan = reconcile(
            local = Snapshot.of(P to m),
            remote = Snapshot.of(P to m),
            ancestor = Snapshot.of(P to m),
        )
        assertTrue(plan.actions.isEmpty())
    }

    // ---- One-sided creation (first-run / new file) ----

    @Test
    fun `local created, remote absent - push`() {
        val m = meta("new")
        val plan = reconcile(
            local = Snapshot.of(P to m),
            remote = Snapshot.EMPTY,
            ancestor = Snapshot.EMPTY,
        )
        val a = plan.action(P)
        assertEquals(Push(P, m), a)
        assertEquals(m, a!!.ancestorAfter)
    }

    @Test
    fun `remote created, local absent - pull`() {
        val m = meta("new")
        val plan = reconcile(
            local = Snapshot.EMPTY,
            remote = Snapshot.of(P to m),
            ancestor = Snapshot.EMPTY,
        )
        assertEquals(Pull(P, m), plan.action(P))
        assertEquals(m, plan.action(P)!!.ancestorAfter)
    }

    // ---- One-sided modification ----

    @Test
    fun `local modified, remote unchanged - push`() {
        val old = meta("v1")
        val new = meta("v2")
        val plan = reconcile(
            local = Snapshot.of(P to new),
            remote = Snapshot.of(P to old),
            ancestor = Snapshot.of(P to old),
        )
        assertEquals(Push(P, new), plan.action(P))
    }

    @Test
    fun `remote modified, local unchanged - pull`() {
        val old = meta("v1")
        val new = meta("v2")
        val plan = reconcile(
            local = Snapshot.of(P to old),
            remote = Snapshot.of(P to new),
            ancestor = Snapshot.of(P to old),
        )
        assertEquals(Pull(P, new), plan.action(P))
    }

    // ---- One-sided deletion ----

    @Test
    fun `local deleted, remote unchanged - delete remote`() {
        val old = meta("v1")
        val plan = reconcile(
            local = Snapshot.EMPTY,
            remote = Snapshot.of(P to old),
            ancestor = Snapshot.of(P to old),
        )
        assertEquals(DeleteRemote(P), plan.action(P))
        assertNull(plan.action(P)!!.ancestorAfter)
    }

    @Test
    fun `remote deleted, local unchanged - delete local`() {
        val old = meta("v1")
        val plan = reconcile(
            local = Snapshot.of(P to old),
            remote = Snapshot.EMPTY,
            ancestor = Snapshot.of(P to old),
        )
        assertEquals(DeleteLocal(P), plan.action(P))
        assertNull(plan.action(P)!!.ancestorAfter)
    }

    // ---- Both changed the same way → converge, no I/O ----

    @Test
    fun `both deleted - converge to absent, no delete op`() {
        val old = meta("v1")
        val plan = reconcile(
            local = Snapshot.EMPTY,
            remote = Snapshot.EMPTY,
            ancestor = Snapshot.of(P to old),
        )
        assertEquals(Converged(P, ancestorAfter = null), plan.action(P))
    }

    @Test
    fun `both created identical content - converge, no transfer`() {
        val m = meta("same")
        val plan = reconcile(
            local = Snapshot.of(P to m),
            remote = Snapshot.of(P to m),
            ancestor = Snapshot.EMPTY,
        )
        assertEquals(Converged(P, ancestorAfter = m), plan.action(P))
    }

    @Test
    fun `both modified to identical content - converge`() {
        val old = meta("v1")
        val same = meta("v2")
        val plan = reconcile(
            local = Snapshot.of(P to same),
            remote = Snapshot.of(P to same),
            ancestor = Snapshot.of(P to old),
        )
        assertEquals(Converged(P, ancestorAfter = same), plan.action(P))
    }

    // ---- Genuine divergence → conflict, both preserved ----

    @Test
    fun `both created different content - CREATE_CREATE conflict`() {
        val l = meta("localVersion")
        val r = meta("remoteVersion")
        val plan = reconcile(
            local = Snapshot.of(P to l),
            remote = Snapshot.of(P to r),
            ancestor = Snapshot.EMPTY,
        )
        val c = plan.action(P) as Conflict
        assertEquals(ConflictKind.CREATE_CREATE, c.kind)
        assertEquals(l, c.local)
        assertEquals(r, c.remote)
    }

    @Test
    fun `both modified differently - MODIFY_MODIFY conflict, ancestor untouched`() {
        val old = meta("v1")
        val l = meta("localEdit")
        val r = meta("remoteEdit")
        val plan = reconcile(
            local = Snapshot.of(P to l),
            remote = Snapshot.of(P to r),
            ancestor = Snapshot.of(P to old),
        )
        val c = plan.action(P) as Conflict
        assertEquals(ConflictKind.MODIFY_MODIFY, c.kind)
        assertEquals(l, c.local)
        assertEquals(r, c.remote)
        // Ancestor is deferred (left as it was) until the user/executor resolves.
        assertEquals(old, c.ancestorAfter)
    }

    @Test
    fun `local modified, remote deleted - MODIFY_DELETE conflict keeps modification`() {
        val old = meta("v1")
        val l = meta("localEdit")
        val plan = reconcile(
            local = Snapshot.of(P to l),
            remote = Snapshot.EMPTY,
            ancestor = Snapshot.of(P to old),
        )
        val c = plan.action(P) as Conflict
        assertEquals(ConflictKind.MODIFY_DELETE, c.kind)
        assertEquals(l, c.local)
        assertNull(c.remote)
    }

    @Test
    fun `local deleted, remote modified - DELETE_MODIFY conflict keeps modification`() {
        val old = meta("v1")
        val r = meta("remoteEdit")
        val plan = reconcile(
            local = Snapshot.EMPTY,
            remote = Snapshot.of(P to r),
            ancestor = Snapshot.of(P to old),
        )
        val c = plan.action(P) as Conflict
        assertEquals(ConflictKind.DELETE_MODIFY, c.kind)
        assertNull(c.local)
        assertEquals(r, c.remote)
    }

    // ---- First-run baseline must never delete pre-existing files ----

    @Test
    fun `first run with populated identical replicas converges without deletions`() {
        val files = (1..20).associate { "f$it.txt" to meta("content$it") }
        val snap = Snapshot(files)
        val plan = reconcile(local = snap, remote = snap, ancestor = Snapshot.EMPTY)

        assertEquals(0, plan.localDeletionCount)
        assertEquals(0, plan.remoteDeletionCount)
        assertTrue(plan.actions.all { it is Converged }, "every path should converge")
        assertEquals(20, plan.actions.size)
    }

    @Test
    fun `first run with disjoint files pushes and pulls, never deletes`() {
        val plan = reconcile(
            local = Snapshot.of("only-local.txt" to meta("L")),
            remote = Snapshot.of("only-remote.txt" to meta("R")),
            ancestor = Snapshot.EMPTY,
        )
        assertEquals(Push("only-local.txt", meta("L")), plan.action("only-local.txt"))
        assertEquals(Pull("only-remote.txt", meta("R")), plan.action("only-remote.txt"))
        assertEquals(0, plan.localDeletionCount + plan.remoteDeletionCount)
    }

    // ---- Path collisions (case-insensitive / Unicode-normalizing replicas) ----

    @Test
    fun `case-fold colliding creations become PATH_COLLISION conflicts, not transfers`() {
        val l = meta("localOnly")
        val r = meta("remoteOnly")
        val plan = reconcile(
            local = Snapshot.of("Notes.txt" to l),
            remote = Snapshot.of("notes.txt" to r),
            ancestor = Snapshot.EMPTY,
        )
        val cu = plan.action("Notes.txt") as Conflict
        assertEquals(ConflictKind.PATH_COLLISION, cu.kind)
        assertEquals(l, cu.local)
        assertNull(cu.remote)
        val cl = plan.action("notes.txt") as Conflict
        assertEquals(ConflictKind.PATH_COLLISION, cl.kind)
        assertNull(cl.local)
        assertEquals(r, cl.remote)
        // Ancestor stays untouched for both, so the collision is re-seen until renamed.
        assertNull(cu.ancestorAfter)
        assertNull(cl.ancestorAfter)
    }

    @Test
    fun `NFC and NFD spellings of the same name collide`() {
        val nfc = "caf\u00e9.txt" // precomposed e-acute
        val nfd = "cafe\u0301.txt" // e + combining acute
        val plan = reconcile(
            local = Snapshot.of(nfc to meta("L")),
            remote = Snapshot.of(nfd to meta("R")),
            ancestor = Snapshot.EMPTY,
        )
        assertEquals(ConflictKind.PATH_COLLISION, (plan.action(nfc) as Conflict).kind)
        assertEquals(ConflictKind.PATH_COLLISION, (plan.action(nfd) as Conflict).kind)
    }

    @Test
    fun `converged member of a collision group passes through, sibling stays blocked`() {
        val old = meta("v1")
        val plan = reconcile(
            local = Snapshot.of("a.txt" to meta("localNew")),
            remote = Snapshot.EMPTY,
            ancestor = Snapshot.of("A.txt" to old),
        )
        // "A.txt" was deleted on both sides: no I/O, safe to converge even inside
        // the collision group.
        assertEquals(Converged("A.txt", ancestorAfter = null), plan.action("A.txt"))
        assertEquals(ConflictKind.PATH_COLLISION, (plan.action("a.txt") as Conflict).kind)
    }

    @Test
    fun `collision spanning ancestor and one replica blocks the delete it would propagate`() {
        val m = meta("v1")
        // The names already merged into one file on the case-insensitive local
        // replica; propagating local's "deletion" of A.txt would destroy the
        // survivor on the remote.
        val plan = reconcile(
            local = Snapshot.of("a.txt" to m),
            remote = Snapshot.of("A.txt" to m, "a.txt" to m),
            ancestor = Snapshot.of("A.txt" to m, "a.txt" to m),
        )
        assertEquals(ConflictKind.PATH_COLLISION, (plan.action("A.txt") as Conflict).kind)
        assertNull(plan.action("a.txt"))
        assertEquals(0, plan.remoteDeletionCount)
    }

    @Test
    fun `independent paths are decided independently in one pass`() {
        val old = meta("v1")
        val plan = reconcile(
            local = Snapshot.of(
                "push.txt" to meta("localNew"),
                "keep.txt" to old,
                "pull.txt" to old,
            ),
            remote = Snapshot.of(
                "push.txt" to old,
                "keep.txt" to old,
                "pull.txt" to meta("remoteNew"),
            ),
            ancestor = Snapshot.of(
                "push.txt" to old,
                "keep.txt" to old,
                "pull.txt" to old,
            ),
        )
        assertTrue(plan.action("push.txt") is Push)
        assertNull(plan.action("keep.txt"))
        assertTrue(plan.action("pull.txt") is Pull)
    }
}

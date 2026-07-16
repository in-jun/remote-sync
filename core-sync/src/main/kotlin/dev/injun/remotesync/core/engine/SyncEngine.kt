package dev.injun.remotesync.core.engine

import dev.injun.remotesync.core.model.ChangeType
import dev.injun.remotesync.core.model.Conflict
import dev.injun.remotesync.core.model.ConflictKind
import dev.injun.remotesync.core.model.Converged
import dev.injun.remotesync.core.model.DeleteLocal
import dev.injun.remotesync.core.model.DeleteRemote
import dev.injun.remotesync.core.model.FileMeta
import dev.injun.remotesync.core.model.Pull
import dev.injun.remotesync.core.model.Push
import dev.injun.remotesync.core.model.Snapshot
import dev.injun.remotesync.core.model.SyncAction
import dev.injun.remotesync.core.model.SyncPlan
import java.text.Normalizer
import java.util.Locale

/**
 * Pure, deterministic three-way reconciler (Unison / rclone-bisync model): classify
 * each side's change against the ancestor, then apply a fixed decision table. Any
 * divergence a one-sided propagation would clobber becomes a [Conflict] instead, so
 * data is never lost. No I/O, no Android/SMB dependencies — hence fully unit-testable.
 */
class SyncEngine {

    /**
     * On the first sync [ancestor] is [Snapshot.EMPTY]; existing files are then
     * treated as creations, never deletions, so a first run can't wipe a replica.
     */
    fun reconcile(local: Snapshot, remote: Snapshot, ancestor: Snapshot): SyncPlan {
        val actions = ArrayList<SyncAction>()
        val allPaths = LinkedHashSet<String>().apply {
            addAll(ancestor.paths)
            addAll(local.paths)
            addAll(remote.paths)
        }
        val colliding = collidingPaths(allPaths)
        for (path in allPaths) {
            val action = decide(path, ancestor[path], local[path], remote[path]) ?: continue
            // Propagating into a collision group could silently overwrite a sibling on a
            // case-insensitive/normalizing replica, so only no-I/O actions pass through.
            actions += if (path in colliding && action !is Converged) {
                Conflict(
                    path,
                    ConflictKind.PATH_COLLISION,
                    local = local[path],
                    remote = remote[path],
                    ancestorBefore = ancestor[path],
                )
            } else {
                action
            }
        }
        return SyncPlan(actions)
    }

    /**
     * Paths that collapse into one file on a case-insensitive or Unicode-normalizing
     * filesystem (Windows/macOS SMB shares, FAT sdcards): distinct here, but equal after
     * NFC normalization plus case folding. Detected across all three snapshots so that a
     * collision that already merged on one replica still blocks the delete/overwrite it
     * would otherwise propagate back.
     */
    private fun collidingPaths(paths: Set<String>): Set<String> {
        val byFoldedPath = paths.groupBy {
            Normalizer.normalize(it, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        }
        return byFoldedPath.values.filter { it.size > 1 }.flatten().toSet()
    }

    private fun decide(path: String, a: FileMeta?, l: FileMeta?, r: FileMeta?): SyncAction? {
        val localChange = classify(a, l)
        val remoteChange = classify(a, r)

        if (localChange == ChangeType.UNCHANGED && remoteChange == ChangeType.UNCHANGED) return null

        return when {
            // Only one side changed → propagate it.
            remoteChange == ChangeType.UNCHANGED -> when (localChange) {
                ChangeType.CREATED, ChangeType.MODIFIED -> Push(path, l!!)
                ChangeType.DELETED -> DeleteRemote(path)
                ChangeType.UNCHANGED -> null
            }

            localChange == ChangeType.UNCHANGED -> when (remoteChange) {
                ChangeType.CREATED, ChangeType.MODIFIED -> Pull(path, r!!)
                ChangeType.DELETED -> DeleteLocal(path)
                ChangeType.UNCHANGED -> null
            }

            // Both changed the same way → converge (no I/O).
            localChange == ChangeType.DELETED && remoteChange == ChangeType.DELETED ->
                Converged(path, ancestorAfter = null)

            // Delete vs modify → keep the surviving content, flag as conflict.
            localChange == ChangeType.DELETED ->
                Conflict(path, ConflictKind.DELETE_MODIFY, local = null, remote = r, ancestorBefore = a)

            remoteChange == ChangeType.DELETED ->
                Conflict(path, ConflictKind.MODIFY_DELETE, local = l, remote = null, ancestorBefore = a)

            l!!.sameContentAs(r!!) -> Converged(path, ancestorAfter = l)

            else -> {
                val kind = if (a == null) ConflictKind.CREATE_CREATE else ConflictKind.MODIFY_MODIFY
                Conflict(path, kind, local = l, remote = r, ancestorBefore = a)
            }
        }
    }

    private fun classify(ancestor: FileMeta?, current: FileMeta?): ChangeType = when {
        ancestor == null && current == null -> ChangeType.UNCHANGED
        ancestor == null && current != null -> ChangeType.CREATED
        ancestor != null && current == null -> ChangeType.DELETED
        else -> if (ancestor!!.sameContentAs(current!!)) ChangeType.UNCHANGED else ChangeType.MODIFIED
    }
}

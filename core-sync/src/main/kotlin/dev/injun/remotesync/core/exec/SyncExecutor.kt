package dev.injun.remotesync.core.exec

import dev.injun.remotesync.core.engine.SyncEngine
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
import dev.injun.remotesync.core.port.AncestorRecord
import dev.injun.remotesync.core.port.AncestorStore
import dev.injun.remotesync.core.port.Storage
import dev.injun.remotesync.core.safety.MaxDeleteGuard
import dev.injun.remotesync.core.safety.SafetyVerdict
import kotlin.coroutines.cancellation.CancellationException

/**
 * Applies a [SyncEngine] plan to two [Storage] replicas without data loss.
 *
 * Crash-safe without a write-ahead log, via three properties: writes are atomic
 * (temp+rename); the ancestor for a path is committed only AFTER its I/O is durable;
 * and every operation is idempotent (conflict copies are named by content hash). So
 * interrupting at any point and re-running finishes correctly — see the crash tests.
 *
 * The plan can be minutes older than the replicas (scans take time, other apps keep
 * writing), so every destructive operation first re-verifies its victim against the
 * scanned state; on mismatch the action is skipped and re-planned next pass.
 */
class SyncExecutor(
    private val local: Storage,
    private val remote: Storage,
    private val ancestors: AncestorStore,
    private val engine: SyncEngine = SyncEngine(),
    private val guard: MaxDeleteGuard = MaxDeleteGuard(),
) {

    suspend fun sync(): SyncResult {
        val records = ancestors.load()
        // Each side scans with ITS OWN recorded stat as the hint. The engine's ancestor
        // input only needs the shared content hash, so the local-side view doubles as it.
        val localSide = Snapshot(records.mapValues { it.value.local })
        val remoteSide = Snapshot(records.mapValues { it.value.remote })
        val localSnap = local.scan(localSide)
        val remoteSnap = remote.scan(remoteSide)

        val plan = engine.reconcile(localSnap, remoteSnap, ancestor = localSide)

        when (val verdict = guard.check(plan, localSnap, remoteSnap)) {
            is SafetyVerdict.Abort -> return SyncResult.Aborted(verdict)
            SafetyVerdict.Ok -> Unit
        }

        val reports = ArrayList<ConflictReport>()
        val failures = ArrayList<SyncFailure>()
        val skipped = ArrayList<String>()
        var applied = 0
        for (action in plan.actions) {
            // Per-path ancestor commits make actions independent, so a failure here is
            // a crash-at-this-point for this path alone: its ancestor is not committed
            // and it is re-planned next pass, while the remaining actions still run —
            // one permanently failing file must not starve every other file.
            try {
                when (val outcome = apply(action, localSnap, remoteSnap)) {
                    is Outcome.Applied -> {
                        outcome.conflict?.let(reports::add)
                        applied++
                    }
                    Outcome.Skipped -> skipped.add(action.path)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures.add(SyncFailure(action.path, e.message ?: e.javaClass.simpleName))
            }
        }
        refreshHints(records, localSnap, remoteSnap)
        return SyncResult.Success(applied, reports, failures, skipped)
    }

    private sealed interface Outcome {
        data class Applied(val conflict: ConflictReport? = null) : Outcome
        object Skipped : Outcome
    }

    // Re-verify the victim, perform I/O, then durably commit the ancestor.
    private suspend fun apply(action: SyncAction, localSnap: Snapshot, remoteSnap: Snapshot): Outcome {
        when (action) {
            is Push -> {
                if (!unchangedSinceScan(remote, action.path, remoteSnap[action.path])) return Outcome.Skipped
                remote.writeAtomic(action.path, local.read(action.path))
                commit(action.path, action.local, writtenMeta(remote, action.path, action.local))
            }
            is Pull -> {
                if (!unchangedSinceScan(local, action.path, localSnap[action.path])) return Outcome.Skipped
                local.writeAtomic(action.path, remote.read(action.path))
                commit(action.path, writtenMeta(local, action.path, action.remote), action.remote)
            }
            is DeleteLocal -> {
                if (!unchangedSinceScan(local, action.path, localSnap[action.path])) return Outcome.Skipped
                local.delete(action.path)
                commit(action.path, null, null)
            }
            is DeleteRemote -> {
                if (!unchangedSinceScan(remote, action.path, remoteSnap[action.path])) return Outcome.Skipped
                remote.delete(action.path)
                commit(action.path, null, null)
            }
            is Converged -> {
                if (action.ancestorAfter == null) {
                    commit(action.path, null, null)
                } else {
                    // Same content on both sides; record each side's own stat.
                    commit(
                        action.path,
                        requireNotNull(localSnap[action.path]),
                        requireNotNull(remoteSnap[action.path]),
                    )
                }
            }
            is Conflict -> return materialize(action)
        }
        return Outcome.Applied()
    }

    /**
     * Preserve both versions. Default policy: local keeps the canonical name; the
     * remote version is kept beside it as a hash-named conflict copy.
     */
    private suspend fun materialize(c: Conflict): Outcome {
        return when (c.kind) {
            ConflictKind.MODIFY_MODIFY, ConflictKind.CREATE_CREATE -> {
                val localMeta = requireNotNull(c.local)
                val remoteMeta = requireNotNull(c.remote)
                val copyPath = ConflictNamer.conflictPath(c.path, remoteMeta)

                if (!unchangedSinceScan(remote, c.path, remoteMeta)) return Outcome.Skipped
                remote.writeAtomic(copyPath, remote.read(c.path))
                local.writeAtomic(copyPath, remote.read(copyPath))
                // The copy above is the only place the remote version survives; if the
                // canonical was written again during those transfers, overwriting it now
                // would destroy content no conflict copy holds.
                if (!unchangedSinceScan(remote, c.path, remoteMeta)) return Outcome.Skipped
                remote.writeAtomic(c.path, local.read(c.path))

                commit(copyPath, writtenMeta(local, copyPath, remoteMeta), writtenMeta(remote, copyPath, remoteMeta))
                commit(c.path, localMeta, writtenMeta(remote, c.path, localMeta))
                Outcome.Applied(ConflictReport(c.path, c.kind, copyPath))
            }

            ConflictKind.MODIFY_DELETE -> {
                // Remote was absent at scan time; if it reappeared since, writing over
                // it would destroy content no snapshot ever saw.
                if (!unchangedSinceScan(remote, c.path, null)) return Outcome.Skipped
                val localMeta = requireNotNull(c.local)
                remote.writeAtomic(c.path, local.read(c.path))
                commit(c.path, localMeta, writtenMeta(remote, c.path, localMeta))
                Outcome.Applied(ConflictReport(c.path, c.kind, conflictCopyPath = null))
            }

            ConflictKind.DELETE_MODIFY -> {
                if (!unchangedSinceScan(local, c.path, null)) return Outcome.Skipped
                val remoteMeta = requireNotNull(c.remote)
                local.writeAtomic(c.path, remote.read(c.path))
                commit(c.path, writtenMeta(local, c.path, remoteMeta), remoteMeta)
                Outcome.Applied(ConflictReport(c.path, c.kind, conflictCopyPath = null))
            }

            // No I/O and no ancestor commit: any write/delete could silently clobber the
            // colliding sibling on a case-insensitive/normalizing replica, and a file
            // can never atomically replace a directory (or vice versa). Left pending
            // (re-reported every pass) until the user renames or removes one side.
            ConflictKind.PATH_COLLISION, ConflictKind.FILE_DIR_COLLISION ->
                Outcome.Applied(ConflictReport(c.path, c.kind, conflictCopyPath = null))
        }
    }

    /**
     * True when [path] on [side] still matches what this pass's scan saw ([scanned],
     * null = absent). Size+mtime is the same freshness signal the scan hint uses; a
     * mismatch means the file changed after the scan, so acting on the stale plan
     * would destroy the newer content.
     */
    private suspend fun unchangedSinceScan(side: Storage, path: String, scanned: FileMeta?): Boolean {
        val now = side.probe(path)
        return if (scanned == null) {
            now == null
        } else {
            now != null && now.size == scanned.size && now.mtimeMillis == scanned.mtimeMillis
        }
    }

    /**
     * Meta to record for a side that [source]'s content was just written to: the
     * destination's own size/mtime with the source's hash. Falls back to [source] —
     * costing one re-hash next pass — if the probe doesn't clearly show our write.
     */
    private suspend fun writtenMeta(side: Storage, path: String, source: FileMeta): FileMeta {
        val now = side.probe(path)
        return if (now != null && now.size == source.size) {
            FileMeta(now.size, now.mtimeMillis, source.contentHash)
        } else {
            source
        }
    }

    private suspend fun commit(path: String, local: FileMeta?, remote: FileMeta?) {
        val record = if (local != null && remote != null) AncestorRecord(local, remote) else null
        ancestors.put(path, record)
    }

    /**
     * Metadata-only repair for paths the plan left alone: when both sides still hold
     * the recorded content but a side's observed size/mtime drifted from the record
     * (mtime-only touch, data migrated from a single-sided ancestor), re-record it so
     * the scan hint matches again instead of re-hashing that file every pass. Never
     * counts as an applied action; failures only cost one more re-hash.
     */
    private suspend fun refreshHints(
        records: Map<String, AncestorRecord>,
        localSnap: Snapshot,
        remoteSnap: Snapshot,
    ) {
        for ((path, record) in records) {
            val l = localSnap[path] ?: continue
            val r = remoteSnap[path] ?: continue
            val hash = record.local.contentHash
            if (l.contentHash != hash || r.contentHash != hash) continue
            if (l == record.local && r == record.remote) continue
            runCatching { ancestors.put(path, AncestorRecord(l, r)) }
        }
    }
}

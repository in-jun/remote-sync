package dev.injun.remotesync.sync

import dev.injun.remotesync.core.exec.SyncExecutor
import dev.injun.remotesync.core.exec.SyncResult
import dev.injun.remotesync.core.safety.MaxDeleteGuard
import dev.injun.remotesync.data.config.SyncStateStore
import dev.injun.remotesync.data.db.AncestorDao
import dev.injun.remotesync.data.db.RoomAncestorStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs one sync pass for a [SyncPair]: wires the core [SyncExecutor] to the pair's
 * storages (via [StorageFactory]) and the Room ancestor, and always closes the
 * remote connection.
 * Records the outcome (durably) so every trigger updates the visible last-sync state,
 * and raises a notification when the outcome needs user attention (safety abort,
 * repeated failures). A [Mutex] serializes passes so manual and scheduled runs never
 * overlap.
 */
@Singleton
class SyncManager @Inject constructor(
    private val ancestorDao: AncestorDao,
    private val syncState: SyncStateStore,
    private val alerts: SyncAlertNotifier,
    private val storages: StorageFactory,
) {
    private val lock = Mutex()

    suspend fun syncOnce(pair: SyncPair): SyncResult = lock.withLock {
        val local = storages.local(pair)
        val ancestors = RoomAncestorStore(ancestorDao, pair.id)
        val guard = MaxDeleteGuard(threshold = pair.maxDeleteThreshold)
        try {
            storages.remote(pair.remote).use { remote ->
                val result = SyncExecutor(local, remote, ancestors, guard = guard).sync()
                syncState.record(pair.id, outcomeText(result))
                when (result) {
                    // A pass with per-path failures is not a clean success: keep the
                    // consecutive-failure count going so a permanently failing file
                    // (bad name, locked, too large) still raises an alert eventually.
                    is SyncResult.Success ->
                        if (result.failures.isEmpty()) alerts.recordSuccess(pair) else alerts.recordFailure(pair)
                    is SyncResult.Aborted -> alerts.notifyAborted(pair, result)
                }
                result
            }
        } catch (e: Exception) {
            syncState.record(pair.id, "Failed: ${e.message ?: e.javaClass.simpleName}")
            alerts.recordFailure(pair)
            throw e
        }
    }

    /**
     * Clears the persisted ancestor snapshot for [pairId], then runs [andThen], all under
     * the sync lock. A stale ancestor on a deleted-then-recreated or retargeted pair would
     * make the engine read the new target's missing files as deletions, so the wipe and
     * the config change ([andThen]) must be atomic with respect to sync passes.
     */
    suspend fun forgetAncestors(pairId: Long, andThen: suspend () -> Unit = {}) {
        lock.withLock {
            ancestorDao.clear(pairId)
            andThen()
        }
    }

    /** Runs [block] under the sync lock, so mutations of a sync root never race a pass. */
    suspend fun <T> withSyncLock(block: suspend () -> T): T = lock.withLock { block() }

    private fun outcomeText(result: SyncResult): String = when (result) {
        is SyncResult.Success -> when {
            result.failures.isNotEmpty() ->
                "Synced ${result.actionsApplied}, ${result.failures.size} failed " +
                    "(first: ${result.failures.first().path})"
            result.hadConflicts -> "${result.conflicts.size} conflict(s)"
            else -> "Synced (${result.actionsApplied})"
        }
        is SyncResult.Aborted -> "Aborted (safety limit)"
    }
}

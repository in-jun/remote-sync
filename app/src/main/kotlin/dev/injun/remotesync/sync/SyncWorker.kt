package dev.injun.remotesync.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.injun.remotesync.core.exec.SyncResult
import dev.injun.remotesync.data.config.ConfigRepository
import kotlinx.coroutines.CancellationException

/**
 * Periodic background sync of all pairs. Transient failures return [Result.retry]
 * (WorkManager backoff); a safety-guard abort is not retried — it needs user attention.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncManager: SyncManager,
    private val config: ConfigRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        config.awaitLoaded()
        val pairs = config.pairs.value
        if (pairs.isEmpty()) return Result.success()
        var transientFailure = false
        for (pair in pairs) {
            try {
                when (val result = syncManager.syncOnce(pair)) {
                    // Per-path failures are transient too (locked file, connection
                    // dropped mid-pass): retry instead of waiting a whole period.
                    is SyncResult.Success ->
                        if (result.failures.isNotEmpty()) transientFailure = true
                    is SyncResult.Aborted -> Unit // SyncManager notified the user; don't retry
                }
            } catch (e: CancellationException) {
                throw e // WorkManager is stopping the worker — don't keep syncing pairs
            } catch (e: Exception) {
                transientFailure = true // network/server hiccup — retry the batch later
            }
        }
        return if (transientFailure) Result.retry() else Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "remote-sync-periodic"
    }
}

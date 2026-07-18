package dev.injun.remotesync.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
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
        // Plain workers are stopped after ~10 minutes, which would cancel a large
        // whole-file SMB transfer every pass and leave that file unsynced forever.
        // Promote to a foreground worker so long passes can finish; if the system
        // forbids the promotion right now (background FGS start restrictions),
        // proceed with the default execution budget instead.
        try {
            setForeground(getForegroundInfo())
        } catch (_: IllegalStateException) {
        }
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
                    null -> Unit // pair was deleted after this batch's snapshot; nothing to do
                }
            } catch (e: CancellationException) {
                throw e // WorkManager is stopping the worker — don't keep syncing pairs
            } catch (e: Exception) {
                transientFailure = true // network/server hiccup — retry the batch later
            }
        }
        return if (transientFailure) Result.retry() else Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        SyncForegroundService.createChannel(applicationContext)
        val notification =
            NotificationCompat.Builder(applicationContext, SyncForegroundService.CHANNEL_ID)
                .setContentTitle("Remote Sync")
                .setContentText("Syncing files")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val UNIQUE_NAME = "remote-sync-periodic"
        private const val NOTIFICATION_ID = 1002
    }
}

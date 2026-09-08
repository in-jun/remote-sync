package dev.injun.remotesync.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.injun.remotesync.core.exec.SyncResult
import dev.injun.remotesync.data.config.ConfigRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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
    private val alerts: SyncAlertNotifier,
    private val scheduler: SyncScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Plain workers are stopped after ~10 minutes, which would cancel a large
        // whole-file SMB transfer every pass and leave that file unsynced forever.
        // Promote to a foreground worker so long passes can finish; if the system
        // forbids the promotion right now (background FGS start restrictions),
        // proceed with the default execution budget instead.
        //
        // Skip the promotion in REALTIME mode: SyncForegroundService already holds an
        // ongoing status notification on this channel, so promoting here too would post
        // a second, redundant "Syncing files" notification for the backstop pass.
        if (!scheduler.isRealtimeServiceEnabled()) {
            try {
                setForeground(getForegroundInfo())
            } catch (e: IllegalStateException) {
                // Common on Android 12+ when the job fires with the app backgrounded and
                // no battery-optimization exemption: the pass still runs, but under the
                // default budget, so a long transfer may be stopped mid-pass — which is
                // exactly what recordIfStoppedMidPass below keeps from staying silent.
                Log.w(TAG, "Foreground promotion rejected; using the default execution budget", e)
            }
        }
        config.awaitLoaded()
        val pairs = config.pairs.value
        if (pairs.isEmpty()) return Result.success()
        var transientFailure = false
        var inFlight: SyncPair? = null
        try {
            for (pair in pairs) {
                inFlight = pair
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
                inFlight = null
            }
        } finally {
            recordIfStoppedMidPass(inFlight)
        }
        return if (transientFailure) Result.retry() else Result.success()
    }

    /**
     * A system stop mid-pass cancels the transfer, and cancellation is deliberately
     * not recorded anywhere in the stack (it would poison the last-sync state on a
     * routine mode switch). But when the stop lands in the middle of a pair's pass,
     * staying silent is wrong: a file too large for the execution budget restarts
     * from byte 0 every period, is stopped at the same point again, and no alert
     * ever fires. Count the stop on that pair's consecutive-failure counter so the
     * persistent case surfaces. Runs from a finally block while the worker
     * coroutine is being cancelled, hence [NonCancellable].
     */
    private suspend fun recordIfStoppedMidPass(pair: SyncPair?) {
        if (pair == null || !isStopped) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP
        ) {
            return // we cancelled the work ourselves (config cleared) — not a stuck pass
        }
        withContext(NonCancellable) {
            val reason =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) " (reason $stopReason)" else ""
            Log.w(TAG, "Stopped mid-pass while syncing '${pair.name}'$reason")
            alerts.recordStopped(pair)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        SyncForegroundService.createChannel(applicationContext)
        val notification =
            SyncForegroundService.buildStatusNotification(applicationContext, "Syncing files")
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
        private const val TAG = "SyncWorker"
        private const val NOTIFICATION_ID = 1002
    }
}

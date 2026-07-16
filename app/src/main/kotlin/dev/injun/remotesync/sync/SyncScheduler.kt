package dev.injun.remotesync.sync

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies [AppSettings] to the background machinery: always keeps a WorkManager
 * periodic job as the Doze-proof baseline, and in REALTIME mode also runs the
 * foreground service. Call [apply] whenever config or settings change.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager get() = WorkManager.getInstance(context)

    fun apply(settings: AppSettings, configured: Boolean) {
        if (!configured) {
            workManager.cancelUniqueWork(SyncWorker.UNIQUE_NAME)
            stopForegroundService()
            return
        }
        schedulePeriodic(settings)
        if (settings.mode == SyncMode.REALTIME) startForegroundService() else stopForegroundService()
    }

    private fun schedulePeriodic(settings: AppSettings) {
        val interval = settings.intervalMinutes.coerceAtLeast(15)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            SyncWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun startForegroundService() {
        val intent = Intent(context, SyncForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopForegroundService() {
        context.stopService(Intent(context, SyncForegroundService::class.java))
    }
}

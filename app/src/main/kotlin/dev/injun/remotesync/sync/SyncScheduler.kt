package dev.injun.remotesync.sync

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
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

    // Plain (unencrypted) prefs holding only "should the realtime service run". Lets
    // BootReceiver decide synchronously without opening the Keystore-backed config.
    private val statePrefs get() = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    fun apply(settings: AppSettings, configured: Boolean) {
        val realtime = configured && settings.mode == SyncMode.REALTIME
        statePrefs.edit().putBoolean(KEY_REALTIME, realtime).apply()
        if (!configured) {
            workManager.cancelUniqueWork(SyncWorker.UNIQUE_NAME)
            stopForegroundService()
            return
        }
        schedulePeriodic(settings)
        if (realtime) startForegroundService() else stopForegroundService()
    }

    /**
     * Brings the realtime service back after boot or an app update. The WorkManager
     * periodic job persists on its own. Must run synchronously in the receiver: the
     * BOOT_COMPLETED background-start exemption does not wait for the config load,
     * and on API 31+ starting a foreground service after it lapses throws.
     */
    fun restoreRealtimeService() {
        if (!statePrefs.getBoolean(KEY_REALTIME, false)) return
        try {
            startForegroundService()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart realtime sync service", e)
        }
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

    /** Whether REALTIME mode is on, so [SyncForegroundService] is (or should be) running. */
    fun isRealtimeServiceEnabled(): Boolean = statePrefs.getBoolean(KEY_REALTIME, false)

    private fun startForegroundService() {
        val intent = Intent(context, SyncForegroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            // API 31+ refuses a foreground-service start while the app is in the
            // background (ForegroundServiceStartNotAllowedException). apply() runs from
            // viewModelScope, which outlives the Activity, so an awaitLoaded() or
            // sync-lock wait can complete after the app is backgrounded and reach here.
            // Swallow it and rely on the WorkManager periodic backstop until the next
            // foreground launch or boot restarts the service.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                Log.w(TAG, "Foreground service start refused from background", e)
            } else {
                throw e
            }
        }
    }

    private fun stopForegroundService() {
        context.stopService(Intent(context, SyncForegroundService::class.java))
    }

    private companion object {
        const val TAG = "SyncScheduler"
        const val STATE_PREFS = "sync-scheduler-state"
        const val KEY_REALTIME = "realtime_enabled"
    }
}

package dev.injun.remotesync.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.injun.remotesync.data.config.ConfigRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Near-real-time sync (REALTIME mode). A foreground service that collects push
 * change signals (local inotify + remote SMB2 CHANGE_NOTIFY) plus a low-frequency
 * safety poll, debounces them, and syncs all pairs. The WorkManager periodic job
 * still runs as a backstop. A foreground service only keeps the process alive: in
 * deep Doze the system still suspends network and defers timers unless the user
 * grants the battery-optimizations exemption (prompted for in Settings); without
 * it the backstop job covers the gap in its maintenance windows.
 */
@AndroidEntryPoint
class SyncForegroundService : Service() {

    @Inject lateinit var syncManager: SyncManager
    @Inject lateinit var config: ConfigRepository
    @Inject lateinit var networkGate: NetworkGate
    @Inject lateinit var changeTriggers: ChangeTriggers
    @Inject lateinit var alertNotifier: SyncAlertNotifier

    // Backstop: an escaped exception should stop the service, not crash the process
    // into a START_STICKY restart loop. Alert the user first — otherwise realtime
    // sync silently degrades to the periodic job until the next app launch.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e ->
                Log.e(TAG, "Sync loop failed", e)
                runCatching { alertNotifier.notifyRealtimeStopped() }
                stopSelf()
            },
    )

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )

        alertNotifier.clearRealtimeStopped()
        scope.launch {
            config.awaitLoaded()
            if (config.pairs.value.isEmpty()) {
                stopSelf()
                return@launch
            }
            config.pairs
                .flatMapLatest { pairs ->
                    if (pairs.isEmpty()) emptyFlow()
                    else merge(changeTriggers.forPairs(pairs), safetyTicker())
                }
                .debounce(DEBOUNCE_MS)
                .collect { syncAll() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private suspend fun syncAll() {
        // The safety poll retries once Wi-Fi returns; skipped pushes are caught then.
        if (!networkGate.allowsSync(config.settings.value)) return
        for (pair in config.pairs.value) {
            try {
                syncManager.syncOnce(pair)
            } catch (e: CancellationException) {
                throw e // service is shutting down — stop the pass
            } catch (_: Exception) {
                // SyncManager recorded the failure; keep syncing the other pairs.
            }
        }
    }

    // An initial trigger on start plus a rare backstop poll (in case a push is missed).
    private fun safetyTicker() = flow {
        emit(Unit)
        while (true) {
            delay(TimeUnit.MINUTES.toMillis(SAFETY_POLL_MINUTES))
            emit(Unit)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sync",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Background sync status" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Remote Sync")
            .setContentText("Real-time sync running")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private companion object {
        const val TAG = "SyncForegroundService"
        const val CHANNEL_ID = "sync_status"
        const val NOTIFICATION_ID = 1001
        const val DEBOUNCE_MS = 1500L
        const val SAFETY_POLL_MINUTES = 10L
    }
}

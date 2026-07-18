package dev.injun.remotesync.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.injun.remotesync.MainActivity
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

    // A foreground service keeps the process and network alive but does not by itself
    // stop CPU suspend once the screen turns off; a partial wake lock held around each
    // pass keeps an in-flight transfer running instead of freezing until the next wakeup.
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        createChannel(this)
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
                .debounce(ChangeTriggers.DEBOUNCE_MS)
                .collect { syncAll() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private suspend fun syncAll() {
        // The safety poll retries once Wi-Fi returns; skipped pushes are caught then.
        if (!networkGate.allowsSync(config.settings.value)) return
        // Timeout-bounded so a wedged pass can never pin the CPU indefinitely.
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        try {
            for (pair in config.pairs.value) {
                try {
                    syncManager.syncOnce(pair)
                } catch (e: CancellationException) {
                    throw e // service is shutting down — stop the pass
                } catch (_: Exception) {
                    // SyncManager recorded the failure; keep syncing the other pairs.
                }
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
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

    private fun buildNotification() = buildStatusNotification(this, "Real-time sync running")

    companion object {
        const val CHANNEL_ID = "sync_status"
        private const val TAG = "SyncForegroundService"
        private const val NOTIFICATION_ID = 1001
        private const val SAFETY_POLL_MINUTES = 10L
        private const val WAKE_LOCK_TAG = "RemoteSync:realtimeSync"
        private val WAKE_LOCK_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10)

        /** The ongoing low-priority status notification; also used by [SyncWorker]. */
        fun buildStatusNotification(context: Context, text: String): Notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Remote Sync")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(contentIntent(context))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

        // Tapping the persistent notification deep-links into the app to inspect status.
        private fun contentIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /** Idempotent; also used by [SyncWorker] when it promotes itself to foreground. */
        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Sync",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Background sync status" }
                context.getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(channel)
            }
        }
    }
}

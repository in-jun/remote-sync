package dev.injun.remotesync.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.injun.remotesync.MainActivity
import dev.injun.remotesync.core.exec.SyncResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Surfaces sync outcomes that need user attention as notifications: a safety-guard
 * abort (never auto-retried, so sync stalls until the user acts), repeated failures,
 * and new conflicts (both versions are kept, but they stay diverged until resolved
 * in the app). Consecutive-failure counts are persisted so infrequent periodic runs
 * still accumulate across process death; a success clears the count and any alert.
 */
@Singleton
class SyncAlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences("sync-alerts", Context.MODE_PRIVATE)

    fun notifyAborted(pair: SyncPair, result: SyncResult.Aborted) {
        postAlert(
            alertId(pair.id),
            "Sync stopped for safety: ${pair.name}",
            "${result.verdict.message} Open the app to review this pair.",
        )
    }

    /** Alerts that the real-time sync service hit an unexpected error and stopped. */
    fun notifyRealtimeStopped() {
        postAlert(
            SERVICE_ALERT_ID,
            "Real-time sync stopped",
            "Real-time sync hit an unexpected error and stopped. Periodic sync still " +
                "runs as a backstop; open the app to restart real-time sync.",
        )
    }

    /** Clears the stopped-service alert once real-time sync is running again. */
    fun clearRealtimeStopped() {
        NotificationManagerCompat.from(context).cancel(SERVICE_ALERT_ID)
    }

    /**
     * Alerts that a pass materialized new conflict copies. Uses its own alert slot:
     * the next clean pass calls [recordSuccess], which must not clear a conflict
     * the user has yet to resolve.
     */
    fun notifyConflicts(pair: SyncPair, count: Int) {
        postAlert(
            conflictAlertId(pair.id),
            "Sync conflicts: ${pair.name}",
            "$count file(s) changed on both sides; both versions were kept. " +
                "Open the app to resolve.",
        )
    }

    fun recordFailure(pair: SyncPair) {
        val failures = prefs.getInt(failureKey(pair.id), 0) + 1
        prefs.edit().putInt(failureKey(pair.id), failures).apply()
        // Alert at the threshold, then re-alert every further N failures so a
        // dismissed notification does not hide a persistent problem forever.
        if (failures % FAILURE_ALERT_THRESHOLD == 0) {
            postAlert(
                alertId(pair.id),
                "Sync keeps failing: ${pair.name}",
                "$failures attempts in a row have failed; files are not being synced. " +
                    "Open the app to check the server settings.",
            )
        }
    }

    fun recordSuccess(pair: SyncPair) {
        prefs.edit().remove(failureKey(pair.id)).apply()
        NotificationManagerCompat.from(context).cancel(alertId(pair.id))
    }

    /** Drops persisted failure state and any visible alerts for a deleted pair. */
    fun forget(pairId: Long) {
        prefs.edit().remove(failureKey(pairId)).apply()
        NotificationManagerCompat.from(context).cancel(alertId(pairId))
        NotificationManagerCompat.from(context).cancel(conflictAlertId(pairId))
    }

    private fun postAlert(id: Int, title: String, text: String) {
        if (!canNotify()) return
        createChannel()
        val contentIntent = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true) // an abort recurs every pass; don't buzz each time
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sync problems",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Sync stopped or keeps failing and needs attention" }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun failureKey(pairId: Long) = "f_$pairId"

    // One alert slot per pair; abort and failure alerts replace each other.
    private fun alertId(pairId: Long): Int = (ALERT_ID_BASE + pairId).toInt()

    // A separate slot per pair for conflicts, so success/failure alerts never mask them.
    private fun conflictAlertId(pairId: Long): Int = (CONFLICT_ALERT_ID_BASE + pairId).toInt()

    private companion object {
        const val CHANNEL_ID = "sync_alerts"
        const val ALERT_ID_BASE = 2000L
        const val CONFLICT_ALERT_ID_BASE = 1_000_000_000L
        const val SERVICE_ALERT_ID = 1999
        const val FAILURE_ALERT_THRESHOLD = 3
    }
}

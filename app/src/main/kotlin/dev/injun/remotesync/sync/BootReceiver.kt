package dev.injun.remotesync.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Restores background sync after a reboot or an app update. The WorkManager periodic
 * job persists on its own; this brings the REALTIME foreground service back without
 * waiting for the user to open the app. Runs synchronously off a plain prefs flag
 * (see [SyncScheduler.restoreRealtimeService]) so the service start stays inside the
 * boot-time background-start exemption instead of racing the encrypted config load.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: SyncScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        scheduler.restoreRealtimeService()
    }
}

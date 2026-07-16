package dev.injun.remotesync.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.injun.remotesync.data.config.ConfigRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restores background sync after a reboot. The WorkManager periodic job persists on
 * its own; this brings the REALTIME foreground service back without waiting for the
 * user to open the app.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var config: ConfigRepository
    @Inject lateinit var scheduler: SyncScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Config loads on IO (Keystore + disk); go async so the main thread isn't blocked.
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                config.awaitLoaded()
                scheduler.apply(config.settings.value, config.isConfigured)
            }
            result.finish()
        }
    }
}

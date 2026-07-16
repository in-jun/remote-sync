package dev.injun.remotesync.sync

import android.content.Context
import android.net.ConnectivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enforces the "Wi-Fi only" setting for syncs that run outside WorkManager (the
 * realtime foreground service and in-app foreground syncs). The periodic job needs
 * no check here: its work request already carries a NetworkType.UNMETERED constraint.
 */
@Singleton
class NetworkGate @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** False when [AppSettings.wifiOnly] is set and the active network is metered. */
    fun allowsSync(settings: AppSettings): Boolean {
        if (!settings.wifiOnly) return true
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return !cm.isActiveNetworkMetered
    }
}

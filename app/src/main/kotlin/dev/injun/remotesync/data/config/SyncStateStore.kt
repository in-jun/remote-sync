package dev.injun.remotesync.data.config

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Last completed sync for a pair; persisted so it survives app restarts and reflects background syncs. */
data class LastSync(val atMillis: Long, val outcome: String)

/**
 * Records the last-sync time and outcome per pair, durably. Every sync path (manual,
 * WorkManager, foreground service) writes here, so the UI always shows the true last
 * sync regardless of which triggered it or whether the app was alive.
 */
@Singleton
class SyncStateStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("sync-state", Context.MODE_PRIVATE)

    private val _lastSync = MutableStateFlow(loadAll())
    val lastSync: StateFlow<Map<Long, LastSync>> = _lastSync.asStateFlow()

    fun record(pairId: Long, outcome: String) {
        val now = System.currentTimeMillis()
        prefs.edit().putLong("t_$pairId", now).putString("o_$pairId", outcome).apply()
        _lastSync.update { it + (pairId to LastSync(now, outcome)) }
    }

    fun forget(pairId: Long) {
        prefs.edit().remove("t_$pairId").remove("o_$pairId").apply()
        _lastSync.update { it - pairId }
    }

    private fun loadAll(): Map<Long, LastSync> =
        prefs.all.keys
            .mapNotNull { if (it.startsWith("t_")) it.removePrefix("t_").toLongOrNull() else null }
            .associateWith { id ->
                LastSync(prefs.getLong("t_$id", 0), prefs.getString("o_$id", "").orEmpty())
            }
}

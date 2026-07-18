package dev.injun.remotesync.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.injun.remotesync.conflict.ConflictItem
import dev.injun.remotesync.conflict.ConflictManager
import dev.injun.remotesync.conflict.ConflictResolution
import dev.injun.remotesync.data.config.ConfigRepository
import dev.injun.remotesync.data.config.SyncStateStore
import dev.injun.remotesync.sync.AppSettings
import dev.injun.remotesync.sync.ChangeTriggers
import dev.injun.remotesync.sync.NetworkGate
import dev.injun.remotesync.sync.SyncAlertNotifier
import dev.injun.remotesync.sync.SyncManager
import dev.injun.remotesync.sync.SyncMode
import dev.injun.remotesync.sync.SyncPair
import dev.injun.remotesync.sync.SyncScheduler
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val config: ConfigRepository,
    private val syncManager: SyncManager,
    private val scheduler: SyncScheduler,
    private val conflictManager: ConflictManager,
    private val syncState: SyncStateStore,
    private val syncAlerts: SyncAlertNotifier,
    private val networkGate: NetworkGate,
    private val changeTriggers: ChangeTriggers,
) : ViewModel() {

    val pairs = config.pairs
    val settings = config.settings

    /** False until the encrypted store has been read; [pairs] is empty before then. */
    val configLoaded = config.isLoaded

    /** Durable last-sync per pair (updated by manual and background syncs alike). */
    val lastSync = syncState.lastSync

    private val _syncing = MutableStateFlow<Set<Long>>(emptySet())
    val syncing: StateFlow<Set<Long>> = _syncing.asStateFlow()

    private val _conflicts = MutableStateFlow<List<ConflictItem>>(emptyList())
    val conflicts: StateFlow<List<ConflictItem>> = _conflicts.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** Last operation error to surface to the user; cleared via [clearError] once shown. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private var foregroundWatch: Job? = null

    init {
        viewModelScope.launch {
            config.awaitLoaded()
            if (config.loadError.value) {
                _error.value = "Some saved configuration could not be read; " +
                    "check folder pairs before syncing"
            }
            scheduler.apply(config.settings.value, config.isConfigured)
        }
        refreshConflicts()
    }

    /**
     * Called when the app comes to the foreground: sync once so the user sees fresh
     * state, and — in PERIODIC mode — watch for changes live while the app is open
     * (no foreground service, so no battery cost once backgrounded).
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun onForegrounded() {
        syncAll()
        foregroundWatch?.cancel()
        foregroundWatch = viewModelScope.launch {
            config.awaitLoaded()
            // Derive the watched pairs from the settings flow rather than reading the
            // mode once: switching to REALTIME tears the in-app watch down (the
            // foreground service takes over), and switching back to PERIODIC restarts
            // it immediately instead of waiting for the next ON_RESUME.
            combine(config.settings, config.pairs) { settings, pairs ->
                if (settings.mode == SyncMode.PERIODIC) pairs else emptyList()
            }
                .distinctUntilChanged()
                .flatMapLatest { changeTriggers.forPairs(it) }
                .debounce(ChangeTriggers.DEBOUNCE_MS)
                // Suspend inline (like SyncForegroundService) so debounce backpressures:
                // a change landing mid-pass queues one follow-up sync instead of being
                // dropped by the in-flight skip in syncAllNow.
                .collect { syncAllNow() }
        }
    }

    fun onBackgrounded() {
        foregroundWatch?.cancel()
        foregroundWatch = null
    }

    fun upsertPair(pair: SyncPair) {
        viewModelScope.launch {
            config.awaitLoaded()
            val previous = config.pair(pair.id)
            val retargeted = previous != null && (
                previous.localRoot != pair.localRoot ||
                    previous.remote.withoutSecrets() != pair.remote.withoutSecrets()
                )
            runCatching {
                if (retargeted) {
                    // A retargeted pair must not keep the old target's ancestor: the
                    // engine would read files missing on the new target as deletions of
                    // live local files. Wipe and persist under the sync lock so no pass
                    // can pair the new target with the old ancestor. Credential-only
                    // changes keep the ancestor (same tree).
                    syncManager.forgetAncestors(pair.id) { config.upsertPair(pair) }
                } else {
                    config.upsertPair(pair)
                }
            }.onFailure { _error.value = errorText("Could not save folder pair", it) }
            scheduler.apply(config.settings.value, config.isConfigured)
            refreshConflicts()
        }
    }

    fun deletePair(id: Long) {
        viewModelScope.launch {
            // Tear down under the sync lock (like upsertPair's retarget path): an
            // in-flight pass for this pair finishes first, so it cannot resurrect
            // last-sync state or alerts after they are forgotten. The ancestor wipe
            // also means a future pair can never inherit this pair's rows.
            runCatching {
                syncManager.forgetAncestors(id) {
                    config.deletePair(id)
                    syncState.forget(id)
                    syncAlerts.forget(id)
                }
            }.onFailure { _error.value = errorText("Could not clear sync state", it) }
            _syncing.value = _syncing.value - id
            scheduler.apply(config.settings.value, config.isConfigured)
            refreshConflicts()
        }
    }

    fun saveSettings(settings: AppSettings) {
        viewModelScope.launch {
            config.saveSettings(settings)
            scheduler.apply(settings, config.isConfigured)
        }
    }

    fun syncPair(id: Long) {
        if (!networkGate.allowsSync(config.settings.value)) {
            // Automatic triggers skip silently, but an explicit tap needs feedback.
            _error.value = SYNC_BLOCKED_MESSAGE
            return
        }
        val target = config.pair(id) ?: return
        if (id in _syncing.value) return
        _syncing.value = _syncing.value + id
        viewModelScope.launch {
            try {
                trySync(target)
            } finally {
                _syncing.value = _syncing.value - id
            }
            refreshConflicts()
        }
    }

    /**
     * Syncs every pair. [manual] marks an explicit user action (the toolbar button):
     * a blocked manual sync surfaces the reason, while automatic triggers
     * (foregrounding, change watch) stay silent — the network gate blocking them is
     * routine, not news.
     */
    fun syncAll(manual: Boolean = false) {
        viewModelScope.launch { syncAllNow(manual) }
    }

    private suspend fun syncAllNow(manual: Boolean = false) {
        config.awaitLoaded()
        if (!networkGate.allowsSync(config.settings.value)) {
            if (manual) _error.value = SYNC_BLOCKED_MESSAGE
            return
        }
        for (pair in config.pairs.value) {
            if (pair.id in _syncing.value) continue
            _syncing.value = _syncing.value + pair.id
            try {
                trySync(pair)
            } finally {
                _syncing.value = _syncing.value - pair.id
            }
        }
        refreshConflicts()
    }

    fun refreshConflicts() {
        viewModelScope.launch {
            config.awaitLoaded()
            runCatching {
                config.pairs.value.flatMap { conflictManager.scan(it) }
            }
                .onSuccess { _conflicts.value = it }
                .onFailure { _error.value = errorText("Could not scan for conflicts", it) }
        }
    }

    fun resolveConflict(item: ConflictItem, resolution: ConflictResolution) {
        val pair = config.pair(item.pairId) ?: return
        viewModelScope.launch {
            // Resolution rewrites files inside the sync root, so take the sync lock:
            // racing a pass would commit ancestors that no longer match the local file.
            val resolved = runCatching {
                syncManager.withSyncLock {
                    conflictManager.resolve(pair, item, resolution)
                }
            }.onFailure { _error.value = errorText("Could not resolve conflict", it) }
            refreshConflicts()
            if (resolved.isSuccess) {
                trySync(pair)
                refreshConflicts()
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Runs one pass, swallowing sync errors (SyncManager already records and surfaces
     * them) but letting cancellation propagate so a dying scope stops the loop.
     */
    private suspend fun trySync(pair: SyncPair) {
        try {
            syncManager.syncOnce(pair)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun errorText(what: String, e: Throwable): String =
        "$what: ${e.message ?: e.javaClass.simpleName}"

    private companion object {
        // Matches the Settings label so the user knows which switch to flip.
        const val SYNC_BLOCKED_MESSAGE =
            "Sync skipped: \"Sync on Wi-Fi only\" is on and this network is metered"
    }
}

package dev.injun.remotesync.sync

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge

/**
 * Merged push-change stream across pairs: local inotify + remote CHANGE_NOTIFY (or
 * nothing, per backend). Protocol-agnostic — storages come from [StorageFactory],
 * and the caller collects this and syncs.
 */
@Singleton
class ChangeTriggers @Inject constructor(
    private val storages: StorageFactory,
) {
    fun forPairs(pairs: List<SyncPair>): Flow<Unit> {
        if (pairs.isEmpty()) return emptyFlow()
        val flows = pairs.flatMap { pair ->
            listOf(
                storages.local(pair).changes(),
                storages.remote(pair.remote).changes(),
            )
        }
        return merge(*flows.toTypedArray())
    }

    companion object {
        /** Coalescing window applied by collectors of [forPairs] before syncing. */
        const val DEBOUNCE_MS = 1500L
    }
}

package dev.injun.remotesync.sync

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
                remoteChanges(pair.remote),
            )
        }
        return flows.merge()
    }

    /**
     * Owns the [RemoteStorage]'s lifecycle: the instance is created when collection
     * starts and closed when it ends, honoring the [AutoCloseable] contract. Today's
     * SMB [changes] happens to manage its own separate watch connection, but a backend
     * whose watch reuses the main connection would otherwise leak one per resubscription.
     */
    private fun remoteChanges(config: RemoteConfig): Flow<Unit> = flow {
        val remote = storages.remote(config)
        try {
            emitAll(remote.changes())
        } finally {
            remote.close()
        }
    }

    companion object {
        /** Coalescing window applied by collectors of [forPairs] before syncing. */
        const val DEBOUNCE_MS = 1500L
    }
}

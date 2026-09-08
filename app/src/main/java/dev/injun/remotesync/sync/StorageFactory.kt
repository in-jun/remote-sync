package dev.injun.remotesync.sync

import dev.injun.remotesync.core.port.Storage

/** A [Storage] that may hold a live connection; callers close it after a sync pass. */
interface RemoteStorage : Storage, AutoCloseable

/**
 * Maps a pair's configuration to concrete [Storage] backends — the single place
 * protocol dispatch happens, so [SyncManager] and [ChangeTriggers] stay
 * protocol-agnostic.
 */
interface StorageFactory {
    fun local(pair: SyncPair): Storage
    fun remote(config: RemoteConfig): RemoteStorage
}

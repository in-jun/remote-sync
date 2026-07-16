package dev.injun.remotesync.data

import dev.injun.remotesync.core.port.Storage
import dev.injun.remotesync.data.local.DirectFileLocalStorage
import dev.injun.remotesync.data.remote.SmbRemoteStorage
import dev.injun.remotesync.sync.RemoteConfig
import dev.injun.remotesync.sync.RemoteStorage
import dev.injun.remotesync.sync.SmbConfig
import dev.injun.remotesync.sync.StorageFactory
import dev.injun.remotesync.sync.SyncPair
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Wires [StorageFactory] to the real backends; the `when` is exhaustive per protocol. */
@Singleton
class DefaultStorageFactory @Inject constructor() : StorageFactory {

    override fun local(pair: SyncPair): Storage = DirectFileLocalStorage(File(pair.localRoot))

    override fun remote(config: RemoteConfig): RemoteStorage = when (config) {
        is SmbConfig -> SmbRemoteStorage(config)
    }
}

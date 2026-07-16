package dev.injun.remotesync.sync

// A configured local↔remote sync. Keyed by [id]; several pairs can coexist.
data class SyncPair(
    val id: Long,
    val name: String,
    val localRoot: String,
    val remote: RemoteConfig,
    val maxDeleteThreshold: Double = 0.5,
) {
    val protocol: Protocol get() = remote.protocol
}

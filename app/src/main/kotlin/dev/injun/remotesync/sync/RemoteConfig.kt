package dev.injun.remotesync.sync

// Protocol-tagged remote endpoint settings carried by a [SyncPair]. Adding a protocol
// means a new variant here plus a [StorageFactory] branch — the sealed hierarchy makes
// the compiler point at every dispatch site.
sealed interface RemoteConfig {
    val protocol: Protocol

    /** Human-readable endpoint (e.g. "host/share/sub") for list rows. */
    val displayPath: String

    /** This config with credential fields blanked; equal values mean the same remote tree. */
    fun withoutSecrets(): RemoteConfig
}

/** SMB connection details. [rootPath] is the sub-directory within the share ("" = share root). */
data class SmbConfig(
    val host: String,
    val port: Int = 445,
    val shareName: String,
    val domain: String = "",
    val username: String,
    val password: String,
    val rootPath: String = "",
) : RemoteConfig {
    override val protocol: Protocol get() = Protocol.SMB
    override val displayPath: String
        get() = "$host/$shareName" + if (rootPath.isBlank()) "" else "/$rootPath"
    override fun withoutSecrets(): RemoteConfig = copy(password = "")
}

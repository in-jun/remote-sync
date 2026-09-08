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
    val port: Int = DEFAULT_PORT,
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

    // Redact the password so an accidental log/exception dump of a config or its
    // enclosing SyncPair never leaks the plaintext secret.
    override fun toString(): String =
        "SmbConfig(host=$host, port=$port, shareName=$shareName, domain=$domain, " +
            "username=$username, password=${if (password.isEmpty()) "" else "***"}, rootPath=$rootPath)"

    companion object {
        /** Standard SMB (microsoft-ds) port; default when the user leaves it blank. */
        const val DEFAULT_PORT = 445
    }
}

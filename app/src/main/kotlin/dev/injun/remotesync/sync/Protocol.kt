package dev.injun.remotesync.sync

// Remote protocol for a folder pair. v1 ships SMB; more are added as an enum entry,
// a [RemoteConfig] variant, and a Storage implementation wired in [StorageFactory],
// with no UI restructuring.
enum class Protocol(val display: String) {
    SMB("SMB"),
}

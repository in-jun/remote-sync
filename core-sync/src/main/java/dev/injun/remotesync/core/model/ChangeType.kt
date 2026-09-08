package dev.injun.remotesync.core.model

/** How one replica changed relative to the ancestor snapshot. */
enum class ChangeType {
    UNCHANGED,
    CREATED,
    MODIFIED,
    DELETED,
}

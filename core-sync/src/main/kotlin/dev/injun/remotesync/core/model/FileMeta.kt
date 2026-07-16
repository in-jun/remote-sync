package dev.injun.remotesync.core.model

/**
 * File metadata within a snapshot. Identity is [contentHash], not mtime/size, so the
 * engine is immune to mtime differences across filesystems and SMB servers.
 */
data class FileMeta(
    val size: Long,
    val mtimeMillis: Long,
    val contentHash: String,
) {
    fun sameContentAs(other: FileMeta): Boolean = contentHash == other.contentHash
}

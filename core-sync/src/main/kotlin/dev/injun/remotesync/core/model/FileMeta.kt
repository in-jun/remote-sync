package dev.injun.remotesync.core.model

/**
 * File metadata within a snapshot. Identity is [contentHash], not mtime/size, so the
 * engine is immune to mtime differences across filesystems and SMB servers.
 */
data class FileMeta(
    val size: Long,
    val mtimeMillis: Long,
    val contentHash: String,
    /**
     * True when this stat was observed while its mtime still sat inside the coarse-mtime
     * granularity window, so a later same-size edit could still land in the same mtime
     * bucket unseen. Such a record is never reused on a stat match by the snapshot builder;
     * it is re-hashed once, after which the bucket has settled and the flag clears. This
     * mirrors git's "racily clean" index entries. Defaults false: a stat that was never
     * flagged is treated as trustworthy, so existing callers keep their current behaviour.
     */
    val racyMtime: Boolean = false,
) {
    fun sameContentAs(other: FileMeta): Boolean = contentHash == other.contentHash
}

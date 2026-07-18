package dev.injun.remotesync.core.port

import dev.injun.remotesync.core.model.FileMeta
import dev.injun.remotesync.core.model.Snapshot

/** A cheap directory-listing entry: metadata only, no content hash yet. */
data class RawEntry(val path: String, val size: Long, val mtimeMillis: Long)

/**
 * Builds a hashed [Snapshot] from a cheap listing, reusing a previous hash when a
 * file's size and mtime are unchanged so only changed files are re-hashed. Lives in
 * the pure module (hashing is injected) so every [Storage] shares one correct impl.
 */
object SnapshotBuilder {

    /**
     * Conservative mtime granularity guarding the hash-reuse shortcut. FAT-formatted
     * SD cards resolve mtimes to 2 s and some SMB servers truncate to whole seconds,
     * so two same-size edits inside this window can share one mtime bucket while their
     * content differs. A hint hash is trusted only once the entry's mtime is this far
     * in the past of the scan clock; nearer than that a same-size edit could still land
     * unseen in the same bucket, so the file is re-hashed instead.
     */
    const val DEFAULT_MTIME_GRANULARITY_MILLIS = 2_000L

    /**
     * @param nowMillis the scan clock in the same time base as the entries' mtimes
     *   (device time for a local scan, server time for a remote one), or null when it
     *   cannot be established — then nothing is reused and every file is re-hashed.
     */
    suspend fun build(
        entries: List<RawEntry>,
        hint: Snapshot,
        nowMillis: Long?,
        granularityMillis: Long = DEFAULT_MTIME_GRANULARITY_MILLIS,
        hash: suspend (path: String) -> String,
    ): Snapshot {
        val map = LinkedHashMap<String, FileMeta>(entries.size)
        for (e in entries) {
            val prev = hint[e.path]
            // Reuse a hint hash only when size+mtime match AND the mtime has settled
            // past the granularity window. Within that window (or against an unknown
            // clock) a same-size edit could share this mtime bucket and go unseen, so
            // re-hash to avoid silently missing it. A future-dated mtime is negative
            // here and re-hashes too, which is the safe direction.
            val settled = nowMillis != null && nowMillis - e.mtimeMillis > granularityMillis
            val contentHash =
                if (settled && prev != null && prev.size == e.size && prev.mtimeMillis == e.mtimeMillis) {
                    prev.contentHash
                } else {
                    hash(e.path)
                }
            map[e.path] = FileMeta(e.size, e.mtimeMillis, contentHash)
        }
        return Snapshot(map)
    }
}

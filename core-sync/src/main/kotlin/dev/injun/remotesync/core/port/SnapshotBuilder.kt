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

    suspend fun build(
        entries: List<RawEntry>,
        hint: Snapshot,
        hash: suspend (path: String) -> String,
    ): Snapshot {
        val map = LinkedHashMap<String, FileMeta>(entries.size)
        for (e in entries) {
            val prev = hint[e.path]
            val contentHash =
                if (prev != null && prev.size == e.size && prev.mtimeMillis == e.mtimeMillis) {
                    prev.contentHash
                } else {
                    hash(e.path)
                }
            map[e.path] = FileMeta(e.size, e.mtimeMillis, contentHash)
        }
        return Snapshot(map)
    }
}

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
     * content differs. An entry whose mtime is this near the scan clock is "racy": a
     * later same-size edit could still land in its bucket unseen.
     */
    const val DEFAULT_MTIME_GRANULARITY_MILLIS = 2_000L

    /**
     * @param nowMillis the scan clock in the same time base as the entries' mtimes
     *   (device time for a local scan, server time for a remote one), or null when it
     *   cannot be established — then nothing is reused and every file is re-hashed.
     *
     * A hint hash is reused only when the stat matches, the entry's mtime has already
     * settled past the granularity window of THIS scan, and the hint itself was recorded
     * after ITS mtime had settled ([FileMeta.racyMtime] is false). Anchoring the second
     * check to the hint's own capture — not the current wall clock — is what closes the
     * #51 miss: a same-size edit committed into the bucket AFTER a racy hint was recorded
     * looks settled to every later scan, so without the flag its stale hash would be
     * reused forever. The scan clock only proves the bucket is closed for edits made
     * before this scan, never for ones made in the window during which the hint was born.
     * Each output entry is itself flagged racy when its mtime is within the window now, so
     * the next pass re-hashes it once and only then treats it as reusable (git racy-clean).
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
            // Racy now: the mtime sits within the granularity window of the scan clock (or
            // the clock is unknown, or the mtime is future-dated so the age is negative).
            // A same-size edit could still land in this bucket after the scan, so the stat
            // cannot be trusted this pass and the record must be re-checked on the next.
            val racyNow = nowMillis == null || nowMillis - e.mtimeMillis <= granularityMillis
            val reuse = !racyNow &&
                prev != null &&
                !prev.racyMtime &&
                prev.size == e.size &&
                prev.mtimeMillis == e.mtimeMillis
            val contentHash = if (reuse) prev!!.contentHash else hash(e.path)
            map[e.path] = FileMeta(e.size, e.mtimeMillis, contentHash, racyMtime = racyNow)
        }
        return Snapshot(map)
    }
}

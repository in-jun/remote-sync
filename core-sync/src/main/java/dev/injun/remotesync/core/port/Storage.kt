package dev.injun.remotesync.core.port

import dev.injun.remotesync.core.model.FileMeta
import dev.injun.remotesync.core.model.Snapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import okio.Source

/**
 * A replica the engine reconciles (local folder or remote endpoint). Local and remote
 * share this interface, which keeps the protocol layer swappable.
 *
 * [writeAtomic] and [move] MUST be atomic w.r.t. concurrent readers and crashes
 * (write-to-temp + rename) — the executor relies on this for its no-data-loss
 * guarantee. Paths are relative to the sync root, '/'-separated, no leading slash.
 */
interface Storage {

    /**
     * Build a hashed snapshot. Implementations may reuse a hash from [hint] when a
     * file's size+mtime are unchanged, avoiding re-hashing unmodified files.
     *
     * Every entry's [FileMeta.contentHash] MUST be produced by [ContentHash.sha256Hex]
     * — the engine compares hashes from different backends directly, so hashing any
     * other way silently breaks cross-side content comparison.
     */
    suspend fun scan(hint: Snapshot = Snapshot.EMPTY): Snapshot

    suspend fun read(path: String): Source

    /**
     * Atomically write [content] to [path]; takes ownership of and closes [content].
     * Returns the size/mtime of the file this write produced, observed on the written
     * file itself (the temp before its rename, or the still-open handle) — NOT by
     * re-looking-up [path] afterwards — so the caller can attribute the stat to this
     * write even if another writer replaces the path immediately after. Null when the
     * backend cannot observe it; callers then fall back to re-hashing later.
     */
    suspend fun writeAtomic(path: String, content: Source): RawEntry?

    /** Delete [path] if present (idempotent). */
    suspend fun delete(path: String)

    /** Atomically rename [from] to [to], replacing [to] if present. */
    suspend fun move(from: String, to: String)

    /**
     * Cheap size/mtime lookup with NO content hash; null if [path] is not a regular
     * file. The executor uses this to re-verify a target immediately before a
     * destructive operation.
     */
    suspend fun probe(path: String): RawEntry?

    /**
     * Push-based change signals, emitted when this backend detects a change (inotify,
     * SMB2 CHANGE_NOTIFY, …). Default: none — the backend has no push and the caller's
     * baseline poll covers it. Push-vs-poll and runtime capability (e.g. SMB2 vs SMB1)
     * stay inside each implementation, so the orchestrator treats every backend alike.
     */
    fun changes(): Flow<Unit> = emptyFlow()
}

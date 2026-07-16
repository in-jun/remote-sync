package dev.injun.remotesync.core.port

import dev.injun.remotesync.core.model.FileMeta

/**
 * One agreed path in the ancestor: the same content on both replicas, with EACH
 * side's own observed size/mtime. Each side assigns its own mtime on write (an SMB
 * server uses server time), so recording only the transfer's source stat would make
 * the other side's scan hint never match and force it to re-hash — over the network
 * for a remote — on every pass. Both hashes are identical by construction.
 */
data class AncestorRecord(val local: FileMeta, val remote: FileMeta) {
    init {
        require(local.contentHash == remote.contentHash) {
            "ancestor sides must agree on content"
        }
    }
}

/**
 * Durable store for the ancestor (last agreed) snapshot. Committing [put] per-path
 * only after the corresponding file I/O is durable is what makes it double as the
 * crash-recovery journal.
 */
interface AncestorStore {
    suspend fun load(): Map<String, AncestorRecord>

    /** Record the agreed state for [path]; null removes it. Must be durable on return. */
    suspend fun put(path: String, record: AncestorRecord?)
}

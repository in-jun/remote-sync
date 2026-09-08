package dev.injun.remotesync.core.exec

import dev.injun.remotesync.core.model.FileMeta

/**
 * Names the sibling file a conflicting version is preserved under, e.g.
 * `vault/db.kdbx` → `vault/db.kdbx.conflict-9f86d081`.
 *
 * The name is deterministic in the content hash (not a timestamp): re-running after
 * a crash maps the same content to the same path, so conflict handling is idempotent
 * and needs no journal.
 */
object ConflictNamer {

    private const val MARKER = ".conflict-"
    private const val HASH_LEN = 8

    fun conflictPath(originalPath: String, preserved: FileMeta): String =
        "$originalPath$MARKER${shortHash(preserved.contentHash)}"

    fun isConflictPath(path: String): Boolean = conflictTag(path) != null

    fun originalPathOf(path: String): String? =
        if (conflictTag(path) == null) null else path.substring(0, path.lastIndexOf(MARKER))

    private fun conflictTag(path: String): String? {
        val idx = path.lastIndexOf(MARKER)
        if (idx < 0) return null
        val tag = path.substring(idx + MARKER.length)
        return if (tag.length == HASH_LEN && tag.all { it in "0123456789abcdef" }) tag else null
    }

    private fun shortHash(contentHash: String): String {
        val hex = contentHash.filter { it in "0123456789abcdefABCDEF" }.lowercase()
        return (if (hex.length >= HASH_LEN) hex else contentHash.hashCode().toUInt().toString(16).padStart(HASH_LEN, '0'))
            .take(HASH_LEN)
    }
}

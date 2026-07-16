package dev.injun.remotesync.core.model

/**
 * An immutable view of one replica's files, keyed by relative '/'-separated path.
 * Used for all three reconciliation inputs: local, remote, and the ancestor.
 */
data class Snapshot(val files: Map<String, FileMeta>) {

    val paths: Set<String> get() = files.keys

    operator fun get(path: String): FileMeta? = files[path]

    fun contains(path: String): Boolean = files.containsKey(path)

    fun with(path: String, meta: FileMeta): Snapshot = Snapshot(files + (path to meta))

    fun without(path: String): Snapshot = if (path in files) Snapshot(files - path) else this

    companion object {
        val EMPTY = Snapshot(emptyMap())
        fun of(vararg entries: Pair<String, FileMeta>): Snapshot = Snapshot(entries.toMap())
    }
}

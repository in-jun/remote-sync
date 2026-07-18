package dev.injun.remotesync.core.port

import okio.HashingSource
import okio.Source
import okio.blackholeSink
import okio.buffer

/**
 * The single content-hash algorithm every [Storage] must use: SHA-256 over the file's
 * raw bytes, rendered as lowercase hex. The engine compares hashes produced by
 * different backends directly (local against remote), so the algorithm and its
 * encoding are a cross-implementation contract, not a private detail. A backend that
 * hashed any other way would compile and pass its own tests while silently breaking
 * every cross-side comparison — converged files would look like conflicts, or
 * different files could collide. Sharing one implementation here is what keeps a new
 * backend from diverging.
 */
object ContentHash {

    /** Stream [source] through SHA-256 and return the lowercase-hex digest; closes [source]. */
    fun sha256Hex(source: Source): String {
        val hashing = HashingSource.sha256(source)
        hashing.buffer().use { it.readAll(blackholeSink()) }
        return hashing.hash.hex()
    }
}

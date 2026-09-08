package dev.injun.remotesync.core.port

/**
 * The cross-replica temp-file contract. Every [Storage.writeAtomic] streams into a
 * sibling named by [nameFor] and renames it over the target, and every side's scan
 * must recognize the same shape — a temp written by one replica may be listed by the
 * other's scan, and treating it as a real file would sync a half-written blob.
 * Defined once so backends cannot drift apart on what counts as an in-flight temp.
 */
object TempFiles {

    /** Generate a temp sibling name for [finalName]: `.<name>.tmp-<nano>`. */
    fun nameFor(finalName: String): String = ".$finalName.tmp-${System.nanoTime()}"

    /**
     * Match ONLY the exact shape produced by [nameFor]. Anything looser would
     * silently exclude — and eventually delete — real user files whose names merely
     * resemble a temp (e.g. ".env.tmp-backup").
     */
    fun isTempName(name: String): Boolean = TEMP_NAME_REGEX.matches(name)

    /** How old an orphaned writeAtomic temp must be before a scan removes it. */
    const val STALE_AGE_MS = 60 * 60_000L

    private val TEMP_NAME_REGEX = Regex("""^\..+\.tmp-\d+$""")
}

package dev.injun.remotesync.core.exec

import dev.injun.remotesync.core.model.FileMeta
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the conflict-name grammar: the `conflictPath` <-> `originalPathOf` round trip
 * and the tag validation that decides whether a scanned file is treated as a conflict
 * copy at all.
 */
class ConflictNamerTest {

    private fun meta(hash: String) = FileMeta(size = 1, mtimeMillis = 0, contentHash = hash)

    @Test
    fun `conflictPath appends the first eight hex chars of the content hash`() {
        assertEquals(
            "vault/db.kdbx.conflict-9f86d081",
            ConflictNamer.conflictPath("vault/db.kdbx", meta("9f86d081884c7d659a2feaa0c55ad015")),
        )
    }

    @Test
    fun `conflictPath round-trips through originalPathOf`() {
        for (original in listOf("db.kdbx", "vault/db.kdbx", "a b/c.d.e", "no-extension")) {
            val conflict = ConflictNamer.conflictPath(original, meta("9f86d081884c7d65"))
            assertTrue(ConflictNamer.isConflictPath(conflict), conflict)
            assertEquals(original, ConflictNamer.originalPathOf(conflict))
        }
    }

    @Test
    fun `non-hex content hashes still produce a valid round-trippable tag`() {
        val conflict = ConflictNamer.conflictPath("db.kdbx", meta("!!not-hex-at-all!!"))
        assertTrue(ConflictNamer.isConflictPath(conflict), conflict)
        assertEquals("db.kdbx", ConflictNamer.originalPathOf(conflict))
    }

    @Test
    fun `mixed-case hashes are normalized to a lowercase tag`() {
        val conflict = ConflictNamer.conflictPath("db.kdbx", meta("9F86D081884C7D65"))
        assertEquals("db.kdbx.conflict-9f86d081", conflict)
        assertTrue(ConflictNamer.isConflictPath(conflict))
    }

    @Test
    fun `malformed tags are not conflict paths`() {
        val malformed = listOf(
            "db.kdbx", // no marker
            "db.kdbx.conflict-", // empty tag
            "db.kdbx.conflict-9f86d08", // too short
            "db.kdbx.conflict-9f86d0811", // too long
            "db.kdbx.conflict-9F86D081", // uppercase hex
            "db.kdbx.conflict-9f86d08x", // non-hex char
            "db.kdbx.conflict-9f86d081/nested", // tag not at the end
        )
        for (path in malformed) {
            assertFalse(ConflictNamer.isConflictPath(path), path)
            assertNull(ConflictNamer.originalPathOf(path), path)
        }
    }

    @Test
    fun `a conflict copy of a conflict copy resolves one level`() {
        val once = ConflictNamer.conflictPath("db.kdbx", meta("9f86d081"))
        val twice = ConflictNamer.conflictPath(once, meta("cafebabe"))
        assertEquals(once, ConflictNamer.originalPathOf(twice))
    }
}

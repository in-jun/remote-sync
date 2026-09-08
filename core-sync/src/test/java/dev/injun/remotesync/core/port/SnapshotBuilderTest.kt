package dev.injun.remotesync.core.port

import dev.injun.remotesync.core.model.FileMeta
import dev.injun.remotesync.core.model.Snapshot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SnapshotBuilderTest {

    private val now = 1_000_000L
    private val gran = SnapshotBuilder.DEFAULT_MTIME_GRANULARITY_MILLIS

    // Older than the granularity window → the mtime bucket has settled, hint trustworthy.
    private val settledMtime = now - gran - 1
    // Inside the window → a same-size edit could still land in this bucket, so untrusted.
    private val racyMtime = now - 1

    private fun hint(vararg metas: Pair<String, FileMeta>) = Snapshot(metas.toMap())

    /** Records which paths it was asked to hash, so each case can pin reuse vs. re-hash. */
    private class LiveHash {
        val calls = mutableListOf<String>()
        val fn: suspend (String) -> String = { path -> calls += path; "live:$path" }
    }

    @Test
    fun `reuses the hint hash when size and mtime match and the mtime has settled`() = runTest {
        val h = LiveHash()
        val out = SnapshotBuilder.build(
            entries = listOf(RawEntry("a", 10, settledMtime)),
            hint = hint("a" to FileMeta(10, settledMtime, "stored")),
            nowMillis = now,
            hash = h.fn,
        )
        assertEquals("stored", out["a"]!!.contentHash)
        assertEquals(emptyList<String>(), h.calls)
    }

    @Test
    fun `re-hashes when the size differs`() = runTest {
        val h = LiveHash()
        val out = SnapshotBuilder.build(
            entries = listOf(RawEntry("a", 11, settledMtime)),
            hint = hint("a" to FileMeta(10, settledMtime, "stored")),
            nowMillis = now,
            hash = h.fn,
        )
        assertEquals("live:a", out["a"]!!.contentHash)
        assertEquals(listOf("a"), h.calls)
    }

    @Test
    fun `re-hashes when the mtime differs`() = runTest {
        val h = LiveHash()
        val out = SnapshotBuilder.build(
            entries = listOf(RawEntry("a", 10, settledMtime + 1)),
            hint = hint("a" to FileMeta(10, settledMtime, "stored")),
            nowMillis = now,
            hash = h.fn,
        )
        assertEquals("live:a", out["a"]!!.contentHash)
        assertEquals(listOf("a"), h.calls)
    }

    @Test
    fun `re-hashes a same-size same-mtime entry whose mtime is within the granularity window`() = runTest {
        // Issue #51: on a coarse-mtime filesystem a second same-size edit can share the
        // first edit's mtime bucket. Until the bucket settles the hint hash is unsafe.
        val h = LiveHash()
        val out = SnapshotBuilder.build(
            entries = listOf(RawEntry("a", 10, racyMtime)),
            hint = hint("a" to FileMeta(10, racyMtime, "stored")),
            nowMillis = now,
            hash = h.fn,
        )
        assertEquals("live:a", out["a"]!!.contentHash)
        assertEquals(listOf("a"), h.calls)
    }

    @Test
    fun `re-hashes when the entry is absent from the hint`() = runTest {
        val h = LiveHash()
        val out = SnapshotBuilder.build(
            entries = listOf(RawEntry("a", 10, settledMtime)),
            hint = Snapshot.EMPTY,
            nowMillis = now,
            hash = h.fn,
        )
        assertEquals("live:a", out["a"]!!.contentHash)
        assertEquals(listOf("a"), h.calls)
    }

    @Test
    fun `re-hashes everything when the scan clock is unknown`() = runTest {
        // A remote scan passes null when the server clock cannot be established; a stat
        // match cannot be trusted without a clock to measure the settle window against.
        val h = LiveHash()
        val out = SnapshotBuilder.build(
            entries = listOf(RawEntry("a", 10, settledMtime)),
            hint = hint("a" to FileMeta(10, settledMtime, "stored")),
            nowMillis = null,
            hash = h.fn,
        )
        assertEquals("live:a", out["a"]!!.contentHash)
        assertEquals(listOf("a"), h.calls)
    }

    @Test
    fun `re-hashes a future-dated mtime rather than trusting it`() = runTest {
        val h = LiveHash()
        val out = SnapshotBuilder.build(
            entries = listOf(RawEntry("a", 10, now + gran)),
            hint = hint("a" to FileMeta(10, now + gran, "stored")),
            nowMillis = now,
            hash = h.fn,
        )
        assertEquals("live:a", out["a"]!!.contentHash)
        assertEquals(listOf("a"), h.calls)
    }

    @Test
    fun `re-hashes a settled stat match when the hint was recorded while racy`() = runTest {
        // Issue #51, the case a now-anchored guard misses: the hint (10, settledMtime,
        // "stored") was written while its bucket was still open, and a same-size edit
        // then landed in that same bucket. On this scan the mtime looks settled against
        // the clock and the stat matches, yet the recorded hash is stale — the racy flag
        // on the hint is the only thing that survives to force the re-hash.
        val h = LiveHash()
        val out = SnapshotBuilder.build(
            entries = listOf(RawEntry("a", 10, settledMtime)),
            hint = hint("a" to FileMeta(10, settledMtime, "stored", racyMtime = true)),
            nowMillis = now,
            hash = h.fn,
        )
        assertEquals("live:a", out["a"]!!.contentHash)
        assertEquals(listOf("a"), h.calls)
    }

    @Test
    fun `flags a scanned entry racy while its mtime is unsettled and clears it once settled`() = runTest {
        val racy = SnapshotBuilder.build(
            entries = listOf(RawEntry("a", 10, racyMtime)),
            hint = Snapshot.EMPTY,
            nowMillis = now,
            hash = LiveHash().fn,
        )
        // A within-window mtime is carried as racy so the next pass re-checks the bucket.
        assertEquals(true, racy["a"]!!.racyMtime)

        val settled = SnapshotBuilder.build(
            entries = listOf(RawEntry("a", 10, settledMtime)),
            hint = Snapshot.EMPTY,
            nowMillis = now,
            hash = LiveHash().fn,
        )
        assertEquals(false, settled["a"]!!.racyMtime)
    }

    @Test
    fun `reuses only the settled entries in a mixed listing`() = runTest {
        val h = LiveHash()
        val out = SnapshotBuilder.build(
            entries = listOf(
                RawEntry("settled", 10, settledMtime),
                RawEntry("racy", 10, racyMtime),
            ),
            hint = hint(
                "settled" to FileMeta(10, settledMtime, "stored-settled"),
                "racy" to FileMeta(10, racyMtime, "stored-racy"),
            ),
            nowMillis = now,
            hash = h.fn,
        )
        assertEquals("stored-settled", out["settled"]!!.contentHash)
        assertEquals("live:racy", out["racy"]!!.contentHash)
        assertEquals(listOf("racy"), h.calls)
    }
}

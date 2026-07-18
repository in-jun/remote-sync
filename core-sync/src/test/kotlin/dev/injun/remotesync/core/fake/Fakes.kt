package dev.injun.remotesync.core.fake

import dev.injun.remotesync.core.model.FileMeta
import dev.injun.remotesync.core.model.Snapshot
import dev.injun.remotesync.core.port.AncestorRecord
import dev.injun.remotesync.core.port.AncestorStore
import dev.injun.remotesync.core.port.RawEntry
import dev.injun.remotesync.core.port.Storage
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import okio.Buffer
import okio.Source
import okio.buffer

/** Stable content hash; the same digest scheme the real storages will use. */
fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Thrown by [FaultController] to simulate a crash / power loss mid-sync. Extends
 * [CancellationException] so it aborts the whole sync through the executor's real
 * interruption path (the per-action recovery catch would swallow a plain exception,
 * turning the "crash" into an ordinary per-file failure).
 */
class SimulatedCrash : CancellationException("simulated crash")

/**
 * Trips a [SimulatedCrash] after a fixed number of mutating steps. Wiring it into
 * both fake storages and the fake ancestor store lets a test sweep the crash point
 * across every durable mutation in a sync and assert recovery from each.
 *
 * Starts disarmed so a scenario can build baseline state (including a clean pre-sync)
 * without consuming the budget; call [arm] just before the sync under test.
 */
class FaultController {
    private var budget: Int = Int.MAX_VALUE

    /** Durable mutations attempted since the last [arm]. */
    var stepsTaken: Int = 0
        private set

    /** Begin counting; crash on the ([steps]+1)-th durable mutation. */
    fun arm(steps: Int) {
        budget = steps
        stepsTaken = 0
    }

    /** Turn faults off so the same wired storages can be resumed to completion. */
    fun disable() {
        budget = Int.MAX_VALUE
    }

    /** Call at each durable mutation point; throws once the budget is exhausted. */
    fun step() {
        stepsTaken++
        if (budget-- <= 0) throw SimulatedCrash()
    }
}

/**
 * In-memory [Storage]. Writes are atomic (content is fully read, then the map entry
 * is swapped); a [FaultController] trip happens *before* the swap, modeling a crash
 * where the pre-existing file survives intact.
 */
class InMemoryStorage(private val fault: FaultController? = null) : Storage {

    private data class Node(val bytes: ByteArray, val mtime: Long)

    private val files = LinkedHashMap<String, Node>()
    private var clock = 1L

    // ---- test helpers (not part of the Storage contract) ----
    /** Runs after a scan's snapshot is taken — mutations here land between scan and apply. */
    var afterScanForTest: (() -> Unit)? = null
    fun seed(path: String, content: String) { files[path] = Node(content.toByteArray(), clock++) }
    fun deleteForTest(path: String) { files.remove(path) }
    fun contentOf(path: String): String? = files[path]?.let { String(it.bytes) }
    fun paths(): Set<String> = files.keys.toSet()
    fun hashesByPath(): Map<String, String> = files.mapValues { sha256(it.value.bytes) }

    // ---- Storage ----
    override suspend fun scan(hint: Snapshot): Snapshot {
        val snap = Snapshot(files.mapValues { (_, n) -> FileMeta(n.bytes.size.toLong(), n.mtime, sha256(n.bytes)) })
        afterScanForTest?.invoke()
        return snap
    }

    override suspend fun read(path: String): Source {
        val n = files[path] ?: throw NoSuchElementException("read missing: $path")
        return Buffer().write(n.bytes)
    }

    override suspend fun writeAtomic(path: String, content: Source) {
        val bytes = content.buffer().use { it.readByteArray() }
        fault?.step() // crash before the atomic swap → old file remains
        files[path] = Node(bytes, clock++)
    }

    override suspend fun delete(path: String) {
        if (path !in files) return
        fault?.step()
        files.remove(path)
    }

    override suspend fun move(from: String, to: String) {
        val n = files[from] ?: return
        fault?.step()
        files[to] = n.copy(mtime = clock++)
        files.remove(from)
    }

    override suspend fun stat(path: String): FileMeta? =
        files[path]?.let { FileMeta(it.bytes.size.toLong(), it.mtime, sha256(it.bytes)) }

    override suspend fun probe(path: String): RawEntry? =
        files[path]?.let { RawEntry(path, it.bytes.size.toLong(), it.mtime) }
}

/** In-memory [AncestorStore]; a [FaultController] trip happens before each commit. */
class InMemoryAncestorStore(private val fault: FaultController? = null) : AncestorStore {
    private val map = LinkedHashMap<String, AncestorRecord>()

    override suspend fun load(): Map<String, AncestorRecord> = map.toMap()

    override suspend fun put(path: String, record: AncestorRecord?) {
        fault?.step() // crash before the ancestor record is durable
        if (record == null) map.remove(path) else map[path] = record
    }
}

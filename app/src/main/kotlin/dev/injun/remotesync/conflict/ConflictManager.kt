package dev.injun.remotesync.conflict

import dev.injun.remotesync.core.exec.ConflictNamer
import dev.injun.remotesync.core.model.Snapshot
import dev.injun.remotesync.core.port.Storage
import dev.injun.remotesync.data.db.AncestorDao
import dev.injun.remotesync.data.db.RoomAncestorStore
import dev.injun.remotesync.sync.StorageFactory
import dev.injun.remotesync.sync.SyncPair
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer

/** A conflict still awaiting resolution: the canonical file plus its preserved sibling. */
data class ConflictItem(
    val pairId: Long,
    val pairName: String,
    val originalPath: String,
    val conflictCopyPath: String,
    val localSize: Long,
    val localMtime: Long,
    val remoteSize: Long,
    val remoteMtime: Long,
    val canonicalExists: Boolean,
    /** Short text preview of each version, or null for binary/missing content. */
    val localPreview: String?,
    val remotePreview: String?,
    /** The kept-both filename that "keep both" would produce, for the UI to show. */
    val keepBothName: String,
)

enum class ConflictResolution {
    /** Keep the canonical (local) version; discard the preserved remote copy. */
    KEEP_LOCAL,

    /** Replace the canonical file with the preserved remote version. */
    KEEP_REMOTE,

    /** Keep both, renaming the copy to a stable non-conflict name. */
    KEEP_BOTH,
}

/** The files changed after the conflict list was built; the decision is based on stale data. */
class StaleConflictException :
    IOException("the file changed since this conflict was reviewed; review it again")

/**
 * Finds and resolves conflict copies (`*.conflict-<hash>`) in the local folder.
 * Resolution edits local files; the next sync propagates the result to the remote.
 * All file access goes through the pair's [Storage], so it inherits the backend's
 * atomicity and durability guarantees and keeps working if the local backend changes.
 */
@Singleton
class ConflictManager @Inject constructor(
    private val storages: StorageFactory,
    private val ancestorDao: AncestorDao,
) {

    suspend fun scan(pair: SyncPair): List<ConflictItem> = withContext(Dispatchers.IO) {
        val storage = storages.local(pair)
        // Same hint the executor uses: the ancestor's recorded local stats, so only
        // files changed since the last pass are re-hashed by the scan.
        val records = RoomAncestorStore(ancestorDao, pair.id).load()
        val snapshot = storage.scan(Snapshot(records.mapValues { it.value.local }))
        snapshot.paths.mapNotNull { path ->
            val original = ConflictNamer.originalPathOf(path) ?: return@mapNotNull null
            val copy = snapshot[path] ?: return@mapNotNull null
            val canonical = snapshot[original]
            ConflictItem(
                pairId = pair.id,
                pairName = pair.name,
                originalPath = original,
                conflictCopyPath = path,
                localSize = canonical?.size ?: 0,
                localMtime = canonical?.mtimeMillis ?: 0,
                remoteSize = copy.size,
                remoteMtime = copy.mtimeMillis,
                canonicalExists = canonical != null,
                localPreview = if (canonical != null) previewOf(storage, original) else null,
                remotePreview = previewOf(storage, path),
                keepBothName = keepBothTarget(storage, original).substringAfterLast('/'),
            )
        }
    }

    suspend fun resolve(
        pair: SyncPair,
        item: ConflictItem,
        resolution: ConflictResolution,
    ): Unit = withContext(Dispatchers.IO) {
        val storage = storages.local(pair)
        val copy = storage.probe(item.conflictCopyPath) ?: return@withContext // already resolved

        // The user decided from the state captured at scan time, but a background sync
        // can rewrite the canonical file or the conflict copy in between (both are
        // ordinary synced paths). Discarding or overwriting content the user never saw
        // would lose it on both replicas, so re-verify before the destructive
        // resolutions and make the user review the fresh state instead. KEEP_BOTH
        // preserves everything and needs no check.
        if (resolution != ConflictResolution.KEEP_BOTH) {
            val canonical = storage.probe(item.originalPath)
            val changed = (canonical != null) != item.canonicalExists ||
                (canonical != null && (canonical.size != item.localSize || canonical.mtimeMillis != item.localMtime)) ||
                copy.size != item.remoteSize || copy.mtimeMillis != item.remoteMtime
            if (changed) throw StaleConflictException()
        }

        // Every operation below throws on failure, so a resolution that could not be
        // applied surfaces to the caller instead of reading as success.
        when (resolution) {
            ConflictResolution.KEEP_LOCAL -> storage.delete(item.conflictCopyPath)
            ConflictResolution.KEEP_REMOTE -> {
                storage.writeAtomic(item.originalPath, storage.read(item.conflictCopyPath))
                storage.delete(item.conflictCopyPath)
            }
            ConflictResolution.KEEP_BOTH ->
                storage.move(item.conflictCopyPath, keepBothTarget(storage, item.originalPath))
        }
    }

    // A short text preview of [path], or null if it looks binary. Reads only the first chunk.
    private suspend fun previewOf(storage: Storage, path: String): String? {
        val bytes = try {
            storage.read(path).buffer().use { source ->
                source.request(PREVIEW_BYTES.toLong())
                source.buffer.readByteArray(minOf(source.buffer.size, PREVIEW_BYTES.toLong()))
            }
        } catch (e: IOException) {
            // A concurrent sync can rewrite or delete the file between the scan and
            // the read; treat it like missing content rather than failing the scan.
            return null
        }
        // Heuristic: NUL byte or many control chars → binary.
        val control = bytes.count { it >= 0 && it < 0x09 || it in 0x0E..0x1F }
        if (bytes.any { it.toInt() == 0 } || control > bytes.size / 10) return null
        return String(bytes, Charsets.UTF_8).take(PREVIEW_CHARS)
    }

    /** A readable, stable relative path for a kept-both copy, e.g. `db (conflict).kdbx`. */
    private suspend fun keepBothTarget(storage: Storage, originalPath: String): String {
        val dir = originalPath.substringBeforeLast('/', "")
        val name = originalPath.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val candidateName = if (n == 1) "$base (conflict)$ext" else "$base (conflict $n)$ext"
            val candidate = if (dir.isEmpty()) candidateName else "$dir/$candidateName"
            if (storage.probe(candidate) == null) return candidate
            n++
        }
    }

    private companion object {
        const val PREVIEW_BYTES = 2048
        const val PREVIEW_CHARS = 240
    }
}

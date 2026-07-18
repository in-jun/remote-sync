package dev.injun.remotesync.conflict

import dev.injun.remotesync.core.exec.ConflictNamer
import dev.injun.remotesync.core.port.TempFiles
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 */
@Singleton
class ConflictManager @Inject constructor() {

    suspend fun scan(pairId: Long, pairName: String, localRoot: String): List<ConflictItem> =
        withContext(Dispatchers.IO) {
        val root = File(localRoot)
        if (!root.isDirectory) return@withContext emptyList()
        root.walkTopDown()
            .filter { it.isFile }
            .mapNotNull { file ->
                val rel = file.relativeTo(root).path.replace(File.separatorChar, '/')
                val original = ConflictNamer.originalPathOf(rel) ?: return@mapNotNull null
                val canonical = File(root, original)
                ConflictItem(
                    pairId = pairId,
                    pairName = pairName,
                    originalPath = original,
                    conflictCopyPath = rel,
                    localSize = if (canonical.isFile) canonical.length() else 0,
                    localMtime = if (canonical.isFile) canonical.lastModified() else 0,
                    remoteSize = file.length(),
                    remoteMtime = file.lastModified(),
                    canonicalExists = canonical.isFile,
                    localPreview = if (canonical.isFile) previewOf(canonical) else null,
                    remotePreview = previewOf(file),
                    keepBothName = keepBothTarget(root, original).name,
                )
            }
            .toList()
    }

    suspend fun resolve(
        localRoot: String,
        item: ConflictItem,
        resolution: ConflictResolution,
    ): Unit = withContext(Dispatchers.IO) {
        val root = File(localRoot)
        val canonical = File(root, item.originalPath)
        val copy = File(root, item.conflictCopyPath)
        if (!copy.isFile) return@withContext // already resolved

        // The user decided from the state captured at scan time, but a background sync
        // can rewrite the canonical file in between (e.g. pull a newer remote version).
        // Discarding or overwriting content the user never saw would lose it on both
        // replicas, so re-verify before the destructive resolutions and make the user
        // review the fresh state instead. KEEP_BOTH preserves everything and needs no
        // check.
        if (resolution != ConflictResolution.KEEP_BOTH) {
            val exists = canonical.isFile
            val changed = exists != item.canonicalExists ||
                (exists && (canonical.length() != item.localSize || canonical.lastModified() != item.localMtime))
            if (changed) throw StaleConflictException()
        }

        when (resolution) {
            ConflictResolution.KEEP_LOCAL -> {
                copy.delete()
            }
            ConflictResolution.KEEP_REMOTE -> {
                canonical.parentFile?.mkdirs()
                val tmp = File(canonical.parentFile, TempFiles.nameFor(canonical.name))
                copy.copyTo(tmp, overwrite = true)
                Files.move(
                    tmp.toPath(),
                    canonical.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
                copy.delete()
            }
            ConflictResolution.KEEP_BOTH -> {
                copy.renameTo(keepBothTarget(root, item.originalPath))
            }
        }
    }

    // A short text preview of [file], or null if it looks binary. Reads only the first chunk.
    private fun previewOf(file: File): String? {
        if (!file.isFile) return null
        val bytes = try {
            file.inputStream().use { input ->
                val buf = ByteArray(PREVIEW_BYTES)
                val n = input.read(buf)
                if (n <= 0) return "" else buf.copyOf(n)
            }
        } catch (e: IOException) {
            // A concurrent sync can rewrite or delete the file between the walk and
            // the read; treat it like missing content rather than failing the scan.
            return null
        }
        // Heuristic: NUL byte or many control chars → binary.
        val control = bytes.count { it >= 0 && it < 0x09 || it in 0x0E..0x1F }
        if (bytes.any { it.toInt() == 0 } || control > bytes.size / 10) return null
        return String(bytes, Charsets.UTF_8).take(PREVIEW_CHARS)
    }

    /** A readable, stable name for a kept-both copy, e.g. `db (conflict).kdbx`. */
    private fun keepBothTarget(root: File, originalPath: String): File {
        val dir = File(root, originalPath).parentFile ?: root
        val name = File(originalPath).name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = File(dir, "$base (conflict)$ext")
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, "$base (conflict $n)$ext")
            n++
        }
        return candidate
    }

    private companion object {
        const val PREVIEW_BYTES = 2048
        const val PREVIEW_CHARS = 240
    }
}

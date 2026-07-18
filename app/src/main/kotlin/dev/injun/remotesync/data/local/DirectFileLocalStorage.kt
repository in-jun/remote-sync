package dev.injun.remotesync.data.local

import dev.injun.remotesync.core.model.Snapshot
import dev.injun.remotesync.core.port.ContentHash
import dev.injun.remotesync.core.port.RawEntry
import dev.injun.remotesync.core.port.SnapshotBuilder
import dev.injun.remotesync.core.port.Storage
import dev.injun.remotesync.core.port.TempFiles
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okio.Source
import okio.buffer
import okio.sink
import okio.source

/**
 * [Storage] over a real local folder using direct file paths (v1 default). Files live
 * at genuine paths in shared storage so other apps can open them, and writes are
 * atomic so a concurrent reader never sees a half-written file. Requires
 * All-Files-Access on Android 11+ (handled by the caller).
 */
class DirectFileLocalStorage(private val root: File) : Storage {

    override suspend fun scan(hint: Snapshot): Snapshot = withContext(Dispatchers.IO) {
        // A missing/unreadable root must fail the pass, not scan as empty: an empty
        // snapshot would make the engine see every synced file as locally deleted
        // and propagate those deletions to the remote.
        if (!root.isDirectory || !root.canRead()) {
            throw IOException("local sync root is not a readable directory: $root")
        }
        val entries = ArrayList<RawEntry>()
        root.walkTopDown()
            // Same hazard as the root check above, one level down: the walk silently
            // yields nothing for a subdirectory it cannot list, which would read as
            // that whole subtree being locally deleted.
            .onFail { dir, e -> throw IOException("cannot list directory: $dir", e) }
            .filter { it.isFile }
            .forEach { f ->
                if (TempFiles.isTempName(f.name)) {
                    // Orphaned writeAtomic temp (process killed mid-write) — never a
                    // sync entry. Remove it once no writer can still be using it; both
                    // timestamps come from the device clock, so the age is reliable.
                    if (System.currentTimeMillis() - f.lastModified() > TempFiles.STALE_AGE_MS) {
                        runCatching { f.delete() }
                    }
                    return@forEach
                }
                val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                entries.add(RawEntry(rel, f.length(), f.lastModified()))
            }
        SnapshotBuilder.build(entries, hint, System.currentTimeMillis()) { path -> hashFile(resolve(path)) }
    }

    override suspend fun read(path: String): Source = withContext(Dispatchers.IO) {
        resolve(path).source()
    }

    override suspend fun writeAtomic(path: String, content: Source): RawEntry? = withContext(Dispatchers.IO) {
        // The outer try owns [content]: resolve() can reject the path before the
        // inner finally exists, and the source must still be closed then.
        try {
            val target = resolve(path)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, TempFiles.nameFor(target.name))
            try {
                FileOutputStream(tmp).use { fos ->
                    val sink = fos.sink().buffer()
                    sink.writeAll(content)
                    sink.flush()
                    fos.fd.sync() // force to disk before the rename makes it visible
                }
                // Stat the temp we just wrote, before the rename publishes the name — a
                // rename preserves size and mtime, so this is the target's stat, and it
                // is attributable to THIS write even if another writer replaces the name
                // an instant later. Re-statting [path] afterwards could not promise that.
                val written = RawEntry(path, tmp.length(), tmp.lastModified())
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
                written
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        } finally {
            content.close()
        }
    }

    override suspend fun delete(path: String): Unit = withContext(Dispatchers.IO) {
        // Throws on failure so the executor keeps the ancestor and retries,
        // instead of committing a delete that never happened.
        Files.deleteIfExists(resolve(path).toPath())
        Unit
    }

    override suspend fun move(from: String, to: String): Unit = withContext(Dispatchers.IO) {
        val dst = resolve(to)
        dst.parentFile?.mkdirs()
        Files.move(
            resolve(from).toPath(),
            dst.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        Unit
    }

    override suspend fun probe(path: String): RawEntry? = withContext(Dispatchers.IO) {
        val f = resolve(path)
        if (!f.isFile) null else RawEntry(path, f.length(), f.lastModified())
    }

    // Local push via inotify (FileObserver). flowOn keeps the recursive tree walk
    // in start() off the collector's dispatcher (the UI watches from Main).
    override fun changes(): Flow<Unit> = callbackFlow {
        val watcher = DirectoryWatcher(root) { trySend(Unit) }
        watcher.start()
        awaitClose { watcher.stop() }
    }.flowOn(Dispatchers.IO)

    // Resolve a relative path to a File, guarding against path traversal.
    private fun resolve(path: String): File {
        val f = File(root, path).canonicalFile
        val base = root.canonicalFile
        require(f.path == base.path || f.path.startsWith(base.path + File.separator)) {
            "path escapes sync root: $path"
        }
        return f
    }

    private fun hashFile(f: File): String = ContentHash.sha256Hex(f.source())
}

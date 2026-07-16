package dev.injun.remotesync.data.local

import dev.injun.remotesync.core.model.FileMeta
import dev.injun.remotesync.core.model.Snapshot
import dev.injun.remotesync.core.port.RawEntry
import dev.injun.remotesync.core.port.SnapshotBuilder
import dev.injun.remotesync.core.port.Storage
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
        val entries = ArrayList<RawEntry>()
        root.walkTopDown()
            .filter { it.isFile && !isTempName(it.name) }
            .forEach { f ->
                val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                entries.add(RawEntry(rel, f.length(), f.lastModified()))
            }
        SnapshotBuilder.build(entries, hint) { path -> hashFile(resolve(path)) }
    }

    override suspend fun read(path: String): Source = withContext(Dispatchers.IO) {
        resolve(path).source()
    }

    override suspend fun writeAtomic(path: String, content: Source): Unit = withContext(Dispatchers.IO) {
        val target = resolve(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp-${System.nanoTime()}")
        try {
            FileOutputStream(tmp).use { fos ->
                val sink = fos.sink().buffer()
                sink.writeAll(content)
                sink.flush()
                fos.fd.sync() // force to disk before the rename makes it visible
            }
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            content.close()
            if (tmp.exists()) tmp.delete()
        }
    }

    override suspend fun delete(path: String): Unit = withContext(Dispatchers.IO) {
        resolve(path).delete()
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

    override suspend fun stat(path: String): FileMeta? = withContext(Dispatchers.IO) {
        val f = resolve(path)
        if (!f.isFile) null else FileMeta(f.length(), f.lastModified(), hashFile(f))
    }

    override suspend fun probe(path: String): RawEntry? = withContext(Dispatchers.IO) {
        val f = resolve(path)
        if (!f.isFile) null else RawEntry(path, f.length(), f.lastModified())
    }

    // Local push via inotify (FileObserver).
    override fun changes(): Flow<Unit> = callbackFlow {
        val watcher = DirectoryWatcher(root) { trySend(Unit) }
        watcher.start()
        awaitClose { watcher.stop() }
    }

    // Resolve a relative path to a File, guarding against path traversal.
    private fun resolve(path: String): File {
        val f = File(root, path).canonicalFile
        val base = root.canonicalFile
        require(f.path == base.path || f.path.startsWith(base.path + File.separator)) {
            "path escapes sync root: $path"
        }
        return f
    }

    private fun isTempName(name: String): Boolean = name.startsWith(".") && name.contains(".tmp-")

    private fun hashFile(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

package dev.injun.remotesync.data.local

import android.os.FileObserver
import java.io.File

/**
 * Recursively watches a folder tree and invokes [onChange] on any file change.
 * FileObserver gotchas handled here: watch directories and include MOVED_TO /
 * CLOSE_WRITE (atomic writes land as a rename, not MODIFY); keep strong references
 * (a GC'd observer stops delivering); manage recursion explicitly (native recursion
 * is unreliable). Callers should debounce [onChange].
 */
@Suppress("DEPRECATION")
class DirectoryWatcher(
    private val root: File,
    private val onChange: () -> Unit,
) {
    private val mask = FileObserver.CREATE or
        FileObserver.CLOSE_WRITE or
        FileObserver.DELETE or
        FileObserver.DELETE_SELF or
        FileObserver.MOVED_FROM or
        FileObserver.MOVED_TO or
        FileObserver.MOVE_SELF

    private val observers = mutableMapOf<String, FileObserver>()
    private var running = false

    @Synchronized
    fun start() {
        if (running) return
        running = true
        observeTree(root)
    }

    @Synchronized
    fun stop() {
        running = false
        observers.values.forEach { it.stopWatching() }
        observers.clear()
    }

    private fun observeTree(dir: File) {
        observe(dir)
        dir.walkTopDown().filter { it.isDirectory }.forEach { observe(it) }
    }

    @Synchronized
    private fun observe(dir: File) {
        val path = dir.path
        if (!running || !dir.isDirectory) return
        // Replace rather than skip an existing entry: a delete+recreate at the same
        // path gives the recreated directory a new inode whose inotify watch differs,
        // so the old observer is dead and keeping it would leave the new tree unwatched.
        observers.remove(path)?.stopWatching()
        val observer = object : FileObserver(path, mask) {
            override fun onEvent(event: Int, relPath: String?) {
                // This watched directory itself was deleted or moved away — *_SELF
                // events carry a null path, so identify it by the captured [dir] and
                // drop its (now dead) observer so the map can't leak and the path can
                // be re-watched if it comes back.
                if (event and (DELETE_SELF or MOVE_SELF) != 0) {
                    release(dir.path)
                    onChange()
                    return
                }
                if (relPath == null) return
                val changed = File(dir, relPath)
                when {
                    // A directory added under the tree (created or moved in, e.g. a bulk
                    // import) needs observers attached recursively to keep events flowing.
                    event and (CREATE or MOVED_TO) != 0 && changed.isDirectory ->
                        observeTree(changed)
                    // A directory removed from the tree: release its observer and any it
                    // owned beneath it (already-dead watches, but the map must forget them).
                    event and (DELETE or MOVED_FROM) != 0 -> releaseTree(changed.path)
                }
                onChange()
            }
        }
        observer.startWatching()
        observers[path] = observer
    }

    @Synchronized
    private fun release(path: String) {
        observers.remove(path)?.stopWatching()
    }

    @Synchronized
    private fun releaseTree(path: String) {
        val prefix = path + File.separator
        observers.keys
            .filter { it == path || it.startsWith(prefix) }
            .forEach { observers.remove(it)?.stopWatching() }
    }
}

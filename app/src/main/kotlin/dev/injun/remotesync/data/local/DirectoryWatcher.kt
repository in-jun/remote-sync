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
        if (!running || observers.containsKey(path) || !dir.isDirectory) return
        val observer = object : FileObserver(path, mask) {
            override fun onEvent(event: Int, relPath: String?) {
                if (relPath == null) return
                val changed = File(dir, relPath)
                // A new sub-directory needs its own observer for events to keep flowing.
                if (event and CREATE != 0 && changed.isDirectory) observe(changed)
                onChange()
            }
        }
        observer.startWatching()
        observers[path] = observer
    }
}

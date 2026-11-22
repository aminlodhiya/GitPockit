package dev.gitpockit.background

import android.os.Build
import android.os.FileObserver
import java.io.File

/**
 * Watches a folder tree for changes (FileObserver itself only watches one directory).
 * New sub-folders are picked up automatically; `.git` is never watched, so Git's own
 * writes don't trigger syncs.
 */
class RecursiveFileObserver(private val root: File, private val onChange: (File) -> Unit) {
    private val observers = HashMap<String, FileObserver>()

    @Synchronized
    fun start() {
        root.walkTopDown()
            .onEnter { it.name != ".git" }
            .filter { it.isDirectory }
            .forEach(::watch)
    }

    @Synchronized
    fun stop() {
        observers.values.forEach { it.stopWatching() }
        observers.clear()
    }

    @Synchronized
    private fun watch(dir: File) {
        if (dir.name == ".git" || observers.containsKey(dir.path)) return
        val observer = newObserver(dir) { event, name ->
            if (name == null) return@newObserver
            val file = File(dir, name)
            if (event and FileObserver.CREATE != 0 && file.isDirectory) {
                // Watch the new folder (and anything already copied into it).
                file.walkTopDown().onEnter { it.name != ".git" }.filter { it.isDirectory }.forEach(::watch)
            }
            if (event and (FileObserver.DELETE_SELF or FileObserver.MOVE_SELF) != 0) unwatch(dir)
            onChange(file)
        }
        observer.startWatching()
        observers[dir.path] = observer
    }

    @Synchronized
    private fun unwatch(dir: File) {
        observers.remove(dir.path)?.stopWatching()
    }

    private fun newObserver(dir: File, onEvent: (Int, String?) -> Unit): FileObserver =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, MASK) {
                override fun onEvent(event: Int, path: String?) = onEvent(event, path)
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.path, MASK) {
                override fun onEvent(event: Int, path: String?) = onEvent(event, path)
            }
        }

    private companion object {
        const val MASK = FileObserver.CLOSE_WRITE or FileObserver.CREATE or FileObserver.DELETE or
            FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
    }
}

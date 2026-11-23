package dev.gitpockit.core.git.jgit.lfs

import org.eclipse.jgit.attributes.Attribute
import org.eclipse.jgit.attributes.FilterCommand
import org.eclipse.jgit.attributes.FilterCommandRegistry
import org.eclipse.jgit.lfs.BuiltinLFS
import org.eclipse.jgit.lfs.CleanFilter
import org.eclipse.jgit.lfs.Lfs
import org.eclipse.jgit.lfs.LfsPointer
import org.eclipse.jgit.lib.ObjectLoader
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.util.LfsFactory
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Files

/**
 * GitPockit's Git LFS integration, wrapping JGit's built-in LFS filters:
 *
 * - Checkout: if a large file's content is already downloaded, it's written out; otherwise the
 *   small pointer file is written instead of failing (like `GIT_LFS_SKIP_SMUDGE`). Downloads are
 *   done by [LfsTransfer] with the repo's own credentials, then the files are checked out again.
 * - Staging: files that are still pointers pass through unchanged, so a not-yet-downloaded large
 *   file never shows up as modified. Real content is stored in .git/lfs and replaced by a pointer.
 * - Push: JGit's own pre-push upload is disabled; [LfsTransfer] uploads before pushing.
 */
internal object PockitLfs : LfsFactory() {
    private val builtin: LfsFactory

    init {
        BuiltinLFS.register() // registers JGit's own LFS filter commands
        builtin = getInstance()
        setInstance(this)
        // Checkout and staging run these named filter commands; replace JGit's with ours.
        FilterCommandRegistry.register("jgit://builtin/lfs/smudge") { repo, input, out -> LocalSmudge(repo, input, out) }
        FilterCommandRegistry.register("jgit://builtin/lfs/clean") { repo, input, out -> PointerAwareClean(repo, input, out) }
    }

    fun ensure() = Unit

    /** In-memory config that turns on JGit's built-in LFS filters for [repo]. */
    fun configure(repo: Repository) {
        repo.config.setBoolean("filter", "lfs", "useJGitBuiltin", true)
        repo.config.setBoolean("filter", "lfs", "required", false)
    }

    override fun isAvailable(): Boolean = true

    override fun isEnabled(db: Repository): Boolean = builtin.isEnabled(db)

    override fun applySmudgeFilter(db: Repository, loader: ObjectLoader, attribute: Attribute?): ObjectLoader {
        val pointer = parsePointer(loader) ?: return loader
        val media = Lfs(db).getMediaFile(pointer.oid)
        return if (Files.isRegularFile(media) && Files.size(media) == pointer.size) {
            builtin.applySmudgeFilter(db, loader, attribute)
        } else {
            loader // not downloaded: keep the pointer
        }
    }

    override fun applyCleanFilter(db: Repository, input: InputStream, length: Long, attribute: Attribute?): LfsInputStream {
        // A file that's still a pointer is stored as-is instead of becoming a pointer to a pointer.
        if (length in 1 until POINTER_MAX) {
            val bytes = input.readBytes()
            if (isPointer(bytes)) return LfsInputStream(ByteArrayInputStream(bytes), bytes.size.toLong())
            return builtin.applyCleanFilter(db, ByteArrayInputStream(bytes), bytes.size.toLong(), attribute)
        }
        return builtin.applyCleanFilter(db, input, length, attribute)
    }

    override fun getPrePushHook(repo: Repository, outputStream: PrintStream?) = null

    override fun getPrePushHook(repo: Repository, outputStream: PrintStream?, errorStream: PrintStream?) = null

    /** Checkout: real content if it's on this device, otherwise the pointer. Never touches the network. */
    private class LocalSmudge(private val repo: Repository, input: InputStream, out: OutputStream) : FilterCommand(input, out) {
        override fun run(): Int {
            val head = readHead(`in`)
            val pointer = if (head.complete) parsePointer(head.bytes) else null
            val media = pointer?.let { Lfs(repo).getMediaFile(it.oid) }
            if (pointer != null && media != null && Files.isRegularFile(media) && Files.size(media) == pointer.size) {
                Files.newInputStream(media).use { it.copyTo(out) }
            } else {
                out.write(head.bytes)
                if (!head.complete) `in`.copyTo(out)
            }
            `in`.close()
            out.close()
            return -1
        }
    }

    /** Staging: a pointer stays a pointer; real content is stored in .git/lfs and replaced by one. */
    private class PointerAwareClean(private val repo: Repository, input: InputStream, out: OutputStream) : FilterCommand(input, out) {
        override fun run(): Int {
            val buffered = BufferedInputStream(`in`, POINTER_MAX.toInt() * 2)
            buffered.mark(POINTER_MAX.toInt() + 1)
            val head = readHead(buffered)
            if (head.complete && isPointer(head.bytes)) {
                out.write(head.bytes)
                buffered.close()
                out.close()
                return -1
            }
            buffered.reset()
            val delegate = CleanFilter.FACTORY.create(repo, buffered, out)
            while (delegate.run() != -1) Unit
            return -1
        }
    }

    private class Head(val bytes: ByteArray, val complete: Boolean)

    /** Reads up to [POINTER_MAX] bytes; `complete` means the whole stream fit (it may be a pointer). */
    private fun readHead(input: InputStream): Head {
        val buf = ByteArray(POINTER_MAX.toInt())
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) return Head(buf.copyOf(n), complete = true)
            n += r
        }
        return Head(buf, complete = false)
    }

    /** Pointer files are tiny; anything larger is real content. */
    const val POINTER_MAX = 1024L

    fun isPointer(bytes: ByteArray): Boolean = bytes.size < POINTER_MAX && parsePointer(bytes) != null

    fun parsePointer(bytes: ByteArray): LfsPointer? =
        if (bytes.size >= POINTER_MAX) null else runCatching { LfsPointer.parseLfsPointer(ByteArrayInputStream(bytes)) }.getOrNull()

    private fun parsePointer(loader: ObjectLoader): LfsPointer? =
        if (loader.size >= POINTER_MAX) null else parsePointer(loader.cachedBytes)
}

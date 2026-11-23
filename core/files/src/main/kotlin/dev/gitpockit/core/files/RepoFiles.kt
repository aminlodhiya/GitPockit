package dev.gitpockit.core.files

import java.io.File
import java.io.IOException

/** What kind of viewer/editor a file should open in. */
enum class FileKind { TEXT, MARKDOWN, IMAGE, BINARY }

data class FileEntry(
    val name: String,
    /** Path relative to the repository root, with '/' separators. */
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val kind: FileKind,
)

class FileAccessException(message: String) : IOException(message)

/**
 * File operations confined to one repository's working tree.
 *
 * Every path is relative to [root]; anything that would escape it (`..`, absolute paths,
 * symlinks pointing outside) or touch Git's own `.git` folder is refused, so the explorer
 * and editor can never damage the repository or files elsewhere on the device.
 * `.git/info/exclude` is the one allowed exception (see [EXCLUDE_FILE]).
 */
class RepoFiles(root: File) {
    val root: File = root.canonicalFile

    /** Resolves [path] inside the repository, or throws [FileAccessException]. */
    fun resolve(path: String, allowGitInternals: Boolean = false): File {
        val clean = path.replace('\\', '/').trim('/')
        val file = if (clean.isEmpty()) root else File(root, clean).canonicalFile
        if (file != root && !file.path.startsWith(root.path + File.separator)) {
            throw FileAccessException("Outside the repository: $path")
        }
        val rel = relative(file)
        if (!allowGitInternals && (rel == ".git" || rel.startsWith(".git/"))) {
            throw FileAccessException("Git's internal folder can't be edited here")
        }
        return file
    }

    fun relative(file: File): String = file.canonicalFile.relativeTo(root).invariantSeparatorsPath

    /** Folder contents: folders first, then files, each alphabetically. `.git` is hidden. */
    fun list(path: String = ""): List<FileEntry> {
        val dir = resolve(path)
        if (!dir.isDirectory) throw FileAccessException("Not a folder: $path")
        return dir.listFiles().orEmpty()
            .filterNot { it.name == ".git" && dir == root }
            .map { f ->
                FileEntry(
                    name = f.name,
                    path = relative(f),
                    isDirectory = f.isDirectory,
                    size = if (f.isFile) f.length() else 0,
                    lastModified = f.lastModified(),
                    kind = if (f.isDirectory) FileKind.BINARY else kindOf(f.name),
                )
            }
            .sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun createFile(parent: String, name: String, content: String = ""): String {
        val file = resolve(join(parent, validName(name)))
        if (file.exists()) throw FileAccessException("\"$name\" already exists")
        file.parentFile?.mkdirs()
        file.writeText(content)
        return relative(file)
    }

    fun createFolder(parent: String, name: String): String {
        val dir = resolve(join(parent, validName(name)))
        if (dir.exists()) throw FileAccessException("\"$name\" already exists")
        if (!dir.mkdirs()) throw FileAccessException("Couldn't create \"$name\"")
        return relative(dir)
    }

    /** Renames within the same folder. Returns the new relative path. */
    fun rename(path: String, newName: String): String {
        val from = resolve(path)
        if (from == root) throw FileAccessException("Can't rename the repository folder here")
        val to = resolve(join(relative(from.parentFile), validName(newName)))
        if (to.exists() && !to.path.equals(from.path, ignoreCase = true)) throw FileAccessException("\"$newName\" already exists")
        if (!from.renameTo(to)) throw FileAccessException("Couldn't rename \"${from.name}\"")
        return relative(to)
    }

    /** Moves into another folder of the repository. Returns the new relative path. */
    fun move(path: String, targetFolder: String): String {
        val from = resolve(path)
        val dir = resolve(targetFolder)
        if (!dir.isDirectory) throw FileAccessException("Not a folder: $targetFolder")
        if (dir.path == from.path || dir.path.startsWith(from.path + File.separator)) throw FileAccessException("Can't move a folder into itself")
        val to = File(dir, from.name)
        if (to.exists()) throw FileAccessException("\"${from.name}\" already exists there")
        if (!from.renameTo(to)) throw FileAccessException("Couldn't move \"${from.name}\"")
        return relative(to)
    }

    fun delete(path: String) {
        val file = resolve(path)
        if (file == root) throw FileAccessException("Can't delete the repository folder here")
        if (!file.deleteRecursively()) throw FileAccessException("Couldn't delete \"${file.name}\"")
    }

    /** Reads a text file; refuses binary files and files over [maxBytes]. */
    fun readText(path: String, maxBytes: Long = MAX_EDITABLE_BYTES, allowGitInternals: Boolean = false): String {
        val file = resolve(path, allowGitInternals)
        if (!file.isFile) return ""
        if (file.length() > maxBytes) throw FileAccessException("File is too large to edit (${file.length() / 1024} KB)")
        val bytes = file.readBytes()
        if (looksBinary(bytes)) throw FileAccessException("This is a binary file")
        return bytes.decodeToString()
    }

    /** Writes text atomically (temp file + rename), so a crash never leaves a half-written file. */
    fun writeText(path: String, text: String, allowGitInternals: Boolean = false) {
        val file = resolve(path, allowGitInternals)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.gitpockit-tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    /**
     * Adds [pattern] to `.gitignore` (shared via the repository) or `.git/info/exclude`
     * (this device only). Does nothing if the exact line is already there.
     */
    fun addIgnoreRule(pattern: String, deviceOnly: Boolean) {
        val target = if (deviceOnly) EXCLUDE_FILE else ".gitignore"
        val current = runCatching { readText(target, allowGitInternals = deviceOnly) }.getOrDefault("")
        if (current.lineSequence().any { it.trim() == pattern.trim() }) return
        val prefix = if (current.isEmpty() || current.endsWith("\n")) "" else "\n"
        writeText(target, current + prefix + pattern.trim() + "\n", allowGitInternals = deviceOnly)
    }

    private fun join(parent: String, name: String) = if (parent.isBlank()) name else "${parent.trim('/')}/$name"

    private fun validName(name: String): String {
        val n = name.trim()
        if (n.isEmpty() || n == "." || n == ".." || n.contains('/') || n.contains('\\') || n.contains('\u0000')) {
            throw FileAccessException("Invalid name: \"$name\"")
        }
        if (n == ".git") throw FileAccessException("\".git\" is reserved by Git")
        return n
    }

    companion object {
        const val EXCLUDE_FILE = ".git/info/exclude"
        const val MAX_EDITABLE_BYTES = 5L * 1024 * 1024

        private val IMAGE = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico", "heic", "avif")
        private val MARKDOWN = setOf("md", "markdown", "mdx")
        private val BINARY = setOf(
            "pdf", "zip", "gz", "tgz", "7z", "rar", "jar", "apk", "aab", "exe", "dll", "so", "dylib", "class",
            "mp3", "mp4", "m4a", "wav", "ogg", "flac", "mov", "mkv", "avi", "webm", "ttf", "otf", "woff", "woff2",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "psd", "sqlite", "db", "bin", "iso",
        )

        fun kindOf(name: String): FileKind {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                in IMAGE -> FileKind.IMAGE
                in MARKDOWN -> FileKind.MARKDOWN
                in BINARY -> FileKind.BINARY
                else -> FileKind.TEXT
            }
        }

        /** NUL bytes in the first 8 KB mean binary, the same rule Git uses. */
        fun looksBinary(bytes: ByteArray): Boolean = bytes.asSequence().take(8000).any { it == 0.toByte() }
    }
}

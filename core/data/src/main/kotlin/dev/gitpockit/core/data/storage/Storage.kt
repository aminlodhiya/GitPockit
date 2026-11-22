package dev.gitpockit.core.data.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File

/** What a chosen folder contains, which decides whether we import, clone into, or init it. */
enum class FolderState { MISSING, EMPTY, REPOSITORY, HAS_FILES }

class StorageLocations(private val context: Context) {

    /** True when GitPockit may read/write any folder in shared storage. */
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    /** Where new clones go by default: a visible `GitPockit` folder, or app storage without permission. */
    fun defaultRoot(): File =
        if (hasAllFilesAccess()) {
            File(Environment.getExternalStorageDirectory(), "GitPockit")
        } else {
            File(context.getExternalFilesDir(null) ?: context.filesDir, "repos")
        }

    fun inspect(dir: File): FolderState = when {
        !dir.exists() -> FolderState.MISSING
        File(dir, ".git").exists() -> FolderState.REPOSITORY
        dir.list().isNullOrEmpty() -> FolderState.EMPTY
        else -> FolderState.HAS_FILES
    }
}

/**
 * Converts a Storage Access Framework tree document id (from ACTION_OPEN_DOCUMENT_TREE) into a
 * real file path, which JGit needs. Returns null for providers that aren't backed by local storage.
 *
 * Examples: `primary:Documents/Notes` → `/storage/emulated/0/Documents/Notes`,
 * `1A2B-3C4D:Notes` → `/storage/1A2B-3C4D/Notes`, `raw:/storage/emulated/0/Download/x` → as-is.
 */
object TreeDocumentPaths {
    private val VOLUME_ID = Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")

    fun toPath(documentId: String, primaryRoot: String = "/storage/emulated/0"): String? {
        if (documentId.startsWith("raw:")) return documentId.removePrefix("raw:").trimEnd('/').ifEmpty { null }
        val volume = documentId.substringBefore(':', missingDelimiterValue = "")
        if (volume.isEmpty()) return null
        val relative = documentId.substringAfter(':').trim('/')
        val root = when {
            volume.equals("primary", ignoreCase = true) -> primaryRoot
            volume.equals("home", ignoreCase = true) -> "$primaryRoot/Documents"
            VOLUME_ID.matches(volume) -> "/storage/$volume"
            else -> return null
        }
        return if (relative.isEmpty()) root else "$root/$relative"
    }
}

package dev.gitpockit.core.data.model

enum class SyncMode { FULL, PULL_ONLY }

enum class ConflictStrategy { ASK, KEEP_LOCAL, KEEP_REMOTE, KEEP_BOTH }

data class SyncSettings(
    val mode: SyncMode = SyncMode.FULL,
    val conflictStrategy: ConflictStrategy = ConflictStrategy.ASK,
    /** null = use the default template. */
    val commitTemplate: String? = null,
    /** 0 = no scheduled sync. */
    val intervalMinutes: Int = 0,
    val onFileChange: Boolean = false,
    val wifiOnly: Boolean = false,
    /** Daily sync times, in minutes after midnight (local time), sorted and unique. */
    val dailyTimes: List<Int> = emptyList(),
    /** Rebase local commits on top of the remote instead of creating merge commits. */
    val pullRebase: Boolean = false,
    /** Download Git LFS files automatically when pulling. */
    val lfsDownload: Boolean = true,
    /** Bring submodules to the commits the repository expects after pulling. */
    val updateSubmodules: Boolean = true,
)

enum class SyncTrigger { MANUAL, SCHEDULE, FILE_CHANGE, APP_OPEN, APP_CLOSE, TILE, WIDGET, INTENT, RETRY }

enum class SyncResult {
    /** Commits were pulled and/or pushed. */
    SYNCED,
    /** Nothing to commit, pull or push. */
    UP_TO_DATE,
    /** Committed locally; network unavailable, will retry automatically. */
    OFFLINE,
    /** A merge needs the user. */
    CONFLICTS,
    FAILED,
}

data class SyncLogEntry(
    val id: Long,
    val repoId: Long,
    val trigger: SyncTrigger,
    val startedAt: Long,
    val durationMs: Long,
    val result: SyncResult,
    val message: String?,
)

data class AppTrigger(val packageName: String, val repoId: Long, val onOpen: Boolean, val onClose: Boolean)

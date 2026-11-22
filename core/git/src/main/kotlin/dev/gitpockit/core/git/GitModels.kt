package dev.gitpockit.core.git

import java.io.File
import java.time.Instant

/** Credentials used for network operations (clone, fetch, pull, push). */
sealed interface GitCredentials {
    data object None : GitCredentials

    /** HTTPS basic auth. For GitHub/GitLab/Gitea use a personal access token as [password]. */
    data class UserPassword(val username: String, val password: String) : GitCredentials

    /** SSH private key in OpenSSH or PEM format. */
    data class SshKey(
        val privateKey: ByteArray,
        val passphrase: String? = null,
        val username: String = "git",
    ) : GitCredentials {
        override fun equals(other: Any?): Boolean =
            other is SshKey && privateKey.contentEquals(other.privateKey) &&
                passphrase == other.passphrase && username == other.username

        override fun hashCode(): Int = privateKey.contentHashCode()
    }
}

data class GitAuthor(val name: String, val email: String)

data class CloneOptions(
    val url: String,
    val directory: File,
    val credentials: GitCredentials = GitCredentials.None,
    val branch: String? = null,
    /** 0 = full history; >0 = shallow clone with that many commits. */
    val depth: Int = 0,
    val cloneSubmodules: Boolean = false,
)

data class RepoStatus(
    val branch: String?,
    val added: Set<String>,
    val changed: Set<String>,
    val removed: Set<String>,
    val modified: Set<String>,
    val missing: Set<String>,
    val untracked: Set<String>,
    val conflicting: Set<String>,
) {
    val isClean: Boolean
        get() = listOf(added, changed, removed, modified, missing, untracked, conflicting).all { it.isEmpty() }

    val hasStagedChanges: Boolean
        get() = added.isNotEmpty() || changed.isNotEmpty() || removed.isNotEmpty()
}

data class CommitInfo(
    val id: String,
    val shortId: String,
    val message: String,
    val authorName: String,
    val authorEmail: String,
    val time: Instant,
    val parentIds: List<String>,
) {
    val title: String get() = message.lineSequence().firstOrNull().orEmpty()
}

data class BranchInfo(
    val name: String,
    val fullRef: String,
    val isRemote: Boolean,
    val isCurrent: Boolean,
    val headId: String?,
)

data class RemoteInfo(val name: String, val fetchUrls: List<String>, val pushUrls: List<String>)

sealed interface PullResult {
    data object UpToDate : PullResult
    data object FastForward : PullResult
    data object Merged : PullResult
    data class Conflicts(val paths: Set<String>) : PullResult
}

data class PushResult(val rejectedRefs: Map<String, String>) {
    val isSuccess: Boolean get() = rejectedRefs.isEmpty()
}

/** Progress callback for long-running network operations. */
fun interface GitProgress {
    fun onProgress(task: String, completed: Int, total: Int)
}

/** Base exception for all git failures, with a user-readable message. */
open class GitException(message: String, cause: Throwable? = null) : Exception(message, cause)

class GitAuthException(message: String, cause: Throwable? = null) : GitException(message, cause)

class GitNetworkException(message: String, cause: Throwable? = null) : GitException(message, cause)

enum class ChangeType { ADDED, MODIFIED, DELETED, RENAMED, COPIED }

/** A file touched by a commit (compared with its first parent). */
data class FileChange(val path: String, val oldPath: String?, val type: ChangeType)

data class CommitDetail(val info: CommitInfo, val changes: List<FileChange>)

enum class DiffLineType { CONTEXT, ADDED, REMOVED }

data class DiffLine(
    val type: DiffLineType,
    val text: String,
    /** 1-based line number in the old file; null for added lines. */
    val oldLine: Int?,
    /** 1-based line number in the new file; null for removed lines. */
    val newLine: Int?,
)

data class DiffHunk(val header: String, val lines: List<DiffLine>)

data class FileDiff(
    val path: String,
    val oldPath: String?,
    val type: ChangeType,
    val isBinary: Boolean,
    val hunks: List<DiffHunk>,
) {
    val additions: Int get() = hunks.sumOf { h -> h.lines.count { it.type == DiffLineType.ADDED } }
    val deletions: Int get() = hunks.sumOf { h -> h.lines.count { it.type == DiffLineType.REMOVED } }
}

/** Which two versions of a working-tree file to compare. */
enum class WorkingDiffMode {
    /** Index vs working tree: what `git diff` shows (unstaged changes). */
    UNSTAGED,

    /** HEAD vs index: what `git diff --cached` shows (staged changes). */
    STAGED,
}

/** Deleting a branch whose commits aren't merged elsewhere; retry with force to delete anyway. */
class GitBranchNotMergedException(val branch: String, cause: Throwable? = null) :
    GitException("Branch $branch has unmerged commits", cause)

/** Local changes would be overwritten (checkout, pull). */
class GitLocalChangesException(val paths: List<String>, cause: Throwable? = null) :
    GitException(
        "Your local changes to ${paths.take(3).joinToString()}${if (paths.size > 3) " and ${paths.size - 3} more" else ""} " +
            "would be overwritten. Commit or discard them first.",
        cause,
    )

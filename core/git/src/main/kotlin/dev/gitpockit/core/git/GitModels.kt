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
    /** Download Git LFS files after cloning (otherwise they stay as small pointer files). */
    val downloadLfs: Boolean = true,
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

/** The three versions of a conflicted file. Null means the file doesn't exist on that side. */
class ConflictVersions(
    val path: String,
    val base: ByteArray?,
    /** Version from the local branch (before the pull). */
    val ours: ByteArray?,
    /** Version from the remote branch being merged in. */
    val theirs: ByteArray?,
)

/** A multi-step operation that is paused, usually waiting for conflicts to be resolved. */
enum class GitOperation { NONE, MERGE, REBASE, CHERRY_PICK, REVERT }

/** Result of merge, rebase, cherry-pick and revert. */
sealed interface OperationResult {
    data object UpToDate : OperationResult
    data object FastForward : OperationResult
    /** Completed and committed. */
    data object Done : OperationResult
    /** Paused with conflicts; resolve them, then continue or abort the operation. */
    data class Conflicts(val paths: Set<String>) : OperationResult
}

enum class ResetMode {
    /** Move the branch only; changes stay staged. */
    SOFT,
    /** Move the branch and unstage; changes stay in your files. */
    MIXED,
    /** Move the branch and discard all changes. */
    HARD,
}

data class StashInfo(val index: Int, val id: String, val message: String, val time: java.time.Instant)

data class TagInfo(
    val name: String,
    /** The commit the tag points to. */
    val commitId: String,
    val annotated: Boolean,
    val message: String?,
    val taggerName: String?,
    val time: java.time.Instant?,
)

data class ReflogEntryInfo(val newId: String, val oldId: String, val message: String, val who: String, val time: java.time.Instant)

/** Which side of a conflict is the user's own work. During a rebase Git swaps "ours" and "theirs". */
fun ConflictVersions.yours(operation: GitOperation): ByteArray? = if (operation == GitOperation.REBASE) theirs else ours

fun ConflictVersions.incoming(operation: GitOperation): ByteArray? = if (operation == GitOperation.REBASE) ours else theirs

enum class RefKind { HEAD, LOCAL_BRANCH, REMOTE_BRANCH, TAG }

/** A branch/tag name shown next to a commit in history. */
data class RefLabel(val name: String, val kind: RefKind)

/** A commit in a file's history, with the file's path at that commit (it may have been renamed since). */
data class FileHistoryEntry(val commit: CommitInfo, val path: String)

data class BlameLine(
    /** 1-based line number in the current file. */
    val line: Int,
    val text: String,
    val commitId: String?,
    val authorName: String?,
    val time: java.time.Instant?,
    val summary: String?,
)

/** What changed between two revisions, plus the commits reachable from [to] but not [from]. */
data class Comparison(val from: String, val to: String, val changes: List<FileChange>, val commits: List<CommitInfo>)

/** Lines picked in a diff: removed lines by old line number, added lines by new line number (1-based). */
data class LineSelection(val oldLines: Set<Int> = emptySet(), val newLines: Set<Int> = emptySet()) {
    val isEmpty: Boolean get() = oldLines.isEmpty() && newLines.isEmpty()
}

/** Key used to sign commits and annotated tags. Material is decrypted by the caller. */
sealed interface SigningKey {
    class Ssh(val privateKey: ByteArray, val passphrase: String?) : SigningKey

    class OpenPgp(val armoredSecret: String, val passphrase: String?) : SigningKey
}

/** Keys the user owns, to recognise their own signatures. */
data class TrustedKeys(val sshFingerprints: Set<String> = emptySet(), val pgpPublicKeys: List<String> = emptyList())

enum class SignatureState {
    UNSIGNED,
    /** Valid signature by one of the user's keys. */
    VERIFIED,
    /** Valid SSH signature by a key the user doesn't have, or an OpenPGP key we can't check. */
    UNKNOWN_KEY,
    /** The signature doesn't match the commit. */
    BAD,
}

data class SignatureInfo(val state: SignatureState, val format: String?, val fingerprint: String?, val message: String?)

enum class SubmoduleState {
    /** Listed in .gitmodules but not downloaded yet. */
    UNINITIALIZED,
    /** Registered but its folder is missing. */
    MISSING,
    /** Checked out at the commit the parent repository expects. */
    UP_TO_DATE,
    /** Checked out at a different commit than the parent expects (needs update, or commit the change). */
    DIFFERENT_COMMIT,
}

data class SubmoduleInfo(
    val path: String,
    val url: String?,
    val state: SubmoduleState,
    /** Commit recorded in the parent repository. */
    val expectedId: String?,
    /** Commit currently checked out in the submodule. */
    val checkedOutId: String?,
)

/** A Git LFS file in the repository. */
data class LfsFile(val path: String, val oid: String, val size: Long, val downloaded: Boolean)

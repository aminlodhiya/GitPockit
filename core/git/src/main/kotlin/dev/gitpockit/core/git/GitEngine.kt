package dev.gitpockit.core.git

import java.io.File

/**
 * Engine-agnostic Git API used by the whole app.
 *
 * The default implementation is [dev.gitpockit.core.git.jgit.JGitEngine]. Keeping this
 * interface small and stable lets us swap in another backend (e.g. libgit2 over JNI)
 * without touching features or the sync engine.
 *
 * All functions are blocking-free `suspend` functions that run on an IO dispatcher.
 */
interface GitEngine {
    suspend fun init(directory: File, initialBranch: String = "main"): GitRepository

    suspend fun clone(options: CloneOptions, progress: GitProgress? = null): GitRepository

    suspend fun open(directory: File): GitRepository

    fun isRepository(directory: File): Boolean
}

/** An opened repository. Close it when done to release file handles. */
interface GitRepository : AutoCloseable {
    val workTree: File

    suspend fun status(): RepoStatus

    suspend fun currentBranch(): String?

    /** Stage paths (relative to the work tree). Use "." to stage everything incl. deletions. */
    suspend fun stage(paths: Collection<String>)

    suspend fun unstage(paths: Collection<String>)

    /** Stage every change in the work tree, including deletions. */
    suspend fun stageAll()

    suspend fun commit(message: String, author: GitAuthor, amend: Boolean = false): CommitInfo

    suspend fun log(maxCount: Int = 100, path: String? = null, skip: Int = 0): List<CommitInfo>

    /** Commit with the files it changed relative to its first parent. */
    suspend fun commitDetail(commitId: String): CommitDetail

    /** Diff of one file in a commit against its first parent. */
    suspend fun commitFileDiff(commitId: String, path: String): FileDiff

    /** Diff of one file in the working tree or index. Untracked files show as fully added. */
    suspend fun workingFileDiff(path: String, mode: WorkingDiffMode): FileDiff

    /**
     * Throw away local changes to [paths]: tracked files are restored from HEAD
     * (also unstaging them), untracked files are deleted.
     */
    suspend fun discardChanges(paths: Collection<String>)

    suspend fun fetch(
        remote: String = "origin",
        credentials: GitCredentials = GitCredentials.None,
        progress: GitProgress? = null,
    )

    suspend fun pull(
        remote: String = "origin",
        credentials: GitCredentials = GitCredentials.None,
        rebase: Boolean = false,
        progress: GitProgress? = null,
    ): PullResult

    suspend fun push(
        remote: String = "origin",
        credentials: GitCredentials = GitCredentials.None,
        force: Boolean = false,
        pushTags: Boolean = false,
        progress: GitProgress? = null,
    ): PushResult

    suspend fun branches(includeRemote: Boolean = true): List<BranchInfo>

    suspend fun createBranch(name: String, startPoint: String? = null, checkout: Boolean = false)

    suspend fun checkout(branch: String)

    suspend fun deleteBranch(name: String, force: Boolean = false)

    suspend fun renameBranch(oldName: String, newName: String)

    suspend fun remotes(): List<RemoteInfo>

    suspend fun addRemote(name: String, url: String)

    suspend fun removeRemote(name: String)

    suspend fun setRemoteUrl(name: String, url: String)

    /** Makes [branch] track `<remote>/<branch>` so pull/push/ahead-behind work without arguments. */
    suspend fun setUpstream(branch: String, remote: String)

    /** Renames a remote, its remote-tracking branches and every branch's upstream pointing at it. */
    suspend fun renameRemote(oldName: String, newName: String)

    /** True while a merge is in progress (after a pull with conflicts, before the merge commit). */
    suspend fun isMerging(): Boolean

    suspend fun conflictVersions(path: String): ConflictVersions

    /**
     * Resolves a conflicted file by writing [content] to it (or deleting it when null) and staging it.
     * Commit once every conflict is resolved to finish the merge.
     */
    suspend fun resolveConflict(path: String, content: ByteArray?)

    /** Abandons an in-progress merge, restoring the last local commit (`git merge --abort`). */
    suspend fun abortMerge()

    /** Number of commits (ahead, behind) of the current branch relative to its upstream. */
    suspend fun aheadBehind(): Pair<Int, Int>?
}

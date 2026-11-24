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

    /**
     * Identity for commits Git creates by itself (merge, cherry-pick, revert, rebase).
     * Kept in memory only; the repo's .git/config is not changed.
     */
    fun setIdentity(author: GitAuthor)

    /**
     * Signs every commit and annotated tag made through this instance with [key]
     * (null turns signing off). In memory only; the repo's .git/config is not changed.
     */
    fun setSigning(key: SigningKey?)

    /** Signature status of a commit; [trusted] identifies the user's own keys. */
    suspend fun signature(commitId: String, trusted: TrustedKeys): SignatureInfo

    suspend fun status(): RepoStatus

    suspend fun currentBranch(): String?

    /** Stage paths (relative to the work tree). Use "." to stage everything incl. deletions. */
    suspend fun stage(paths: Collection<String>)

    suspend fun unstage(paths: Collection<String>)

    /**
     * Stage every change in the work tree, including deletions. Folders that are separate Git
     * repositories (see [nestedRepositories]) are skipped.
     */
    suspend fun stageAll()

    /**
     * Folders inside the work tree that are separate Git repositories (their own .git) but not
     * registered submodules. Their files can't be committed in this repository.
     */
    suspend fun nestedRepositories(): List<String>

    suspend fun commit(message: String, author: GitAuthor, amend: Boolean = false): CommitInfo

    suspend fun log(maxCount: Int = 100, path: String? = null, skip: Int = 0): List<CommitInfo>

    /**
     * History in graph order (topological, newest first) for drawing a commit graph.
     * With [allRefs], includes every branch and tag instead of only HEAD.
     */
    suspend fun graphLog(maxCount: Int = 100, skip: Int = 0, allRefs: Boolean = false): List<CommitInfo>

    /** Branch/tag labels by commit id. */
    suspend fun refLabels(): Map<String, List<RefLabel>>

    /** Commits whose message, author, email or hash contain [query] (case-insensitive). */
    suspend fun searchCommits(query: String, maxResults: Int = 200, scanLimit: Int = 20_000): List<CommitInfo>

    /** History of one file, following renames. */
    suspend fun fileHistory(path: String, maxCount: Int = 500): List<FileHistoryEntry>

    /** Who last changed each line of [path] (in HEAD), following renames. */
    suspend fun blame(path: String): List<BlameLine>

    /** Changes and commits going from [from] to [to] (branches, tags or commits). */
    suspend fun compare(from: String, to: String, maxCommits: Int = 500): Comparison

    /** Diff of one file between two revisions. */
    suspend fun compareFileDiff(from: String, to: String, path: String): FileDiff

    /** Stages only the [selection] of the file's unstaged changes. */
    suspend fun stageLines(path: String, selection: LineSelection)

    /** Unstages only the [selection] of the file's staged changes (the file itself is untouched). */
    suspend fun unstageLines(path: String, selection: LineSelection)

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

    /** The paused multi-step operation, if any. */
    suspend fun operation(): GitOperation

    /**
     * Continues the paused operation once conflicts are resolved: commits a merge, cherry-pick
     * or revert (with [message], or Git's prepared message when blank), or replays the
     * remaining commits of a rebase.
     */
    suspend fun continueOperation(message: String, author: GitAuthor): OperationResult

    /** Abandons the paused operation and restores the state from before it started. */
    suspend fun abortOperation()

    // ---- Merge, rebase, cherry-pick, revert, reset

    /** Merges [rev] (branch, tag or commit) into the current branch. */
    suspend fun merge(rev: String, message: String? = null): OperationResult

    /** Rebases the current branch onto [upstream]. */
    suspend fun rebase(upstream: String): OperationResult

    suspend fun cherryPick(commitId: String): OperationResult

    /** Creates a commit that undoes [commitId]. */
    suspend fun revert(commitId: String): OperationResult

    suspend fun reset(rev: String, mode: ResetMode)

    /** Checks out a commit or tag without a branch ("detached HEAD"). */
    suspend fun checkoutDetached(rev: String)

    // ---- Stash

    suspend fun stashes(): List<StashInfo>

    /** Returns false when there was nothing to stash. */
    suspend fun stashSave(message: String?, includeUntracked: Boolean, author: GitAuthor): Boolean

    /** Applies a stash; with [pop] it is dropped afterwards unless applying caused conflicts. */
    suspend fun stashApply(index: Int, pop: Boolean): OperationResult

    suspend fun stashDrop(index: Int)

    // ---- Tags

    suspend fun tags(): List<TagInfo>

    /** Lightweight tag when [message] is null, annotated otherwise. Tags HEAD when [rev] is null. */
    suspend fun createTag(name: String, message: String?, rev: String?, author: GitAuthor)

    suspend fun deleteTag(name: String)

    suspend fun pushTag(name: String, remote: String, credentials: GitCredentials, delete: Boolean = false): PushResult

    // ---- Submodules

    suspend fun submodules(): List<SubmoduleInfo>

    /**
     * Downloads (if needed) and checks out submodules at the commits the parent expects
     * (`git submodule update --init`). Empty [paths] means all. Returns the updated paths.
     */
    suspend fun updateSubmodules(paths: Collection<String> = emptyList(), credentials: GitCredentials = GitCredentials.None): List<String>

    /** Adds a submodule at [path] cloned from [url]; commit afterwards to record it. */
    suspend fun addSubmodule(path: String, url: String, credentials: GitCredentials = GitCredentials.None)

    /** Copies submodule URLs from .gitmodules into the config (`git submodule sync`). */
    suspend fun syncSubmodules()

    // ---- Git LFS

    /** Whether the repository tracks files with Git LFS (`filter=lfs` in .gitattributes). */
    suspend fun usesLfs(): Boolean

    /** LFS files in the current checkout and whether their content is on this device. */
    suspend fun lfsFiles(): List<LfsFile>

    /**
     * Downloads missing LFS content and replaces pointer files with it. Empty [paths] means all.
     * Returns the number of files downloaded.
     */
    suspend fun lfsDownload(remote: String = "origin", credentials: GitCredentials = GitCredentials.None, paths: Collection<String> = emptyList()): Int

    /** Tracks files matching [pattern] (e.g. `*.psd`) with LFS from now on, via .gitattributes. */
    suspend fun lfsTrack(pattern: String)

    /** Whether pull/clone download LFS content automatically (in memory, per opened repo). */
    fun setLfsAutoDownload(enabled: Boolean)

    // ---- Reflog

    suspend fun reflog(maxCount: Int = 200): List<ReflogEntryInfo>

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

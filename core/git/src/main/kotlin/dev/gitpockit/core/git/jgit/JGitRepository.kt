package dev.gitpockit.core.git.jgit

import dev.gitpockit.core.git.BranchInfo
import dev.gitpockit.core.git.ChangeType
import dev.gitpockit.core.git.CommitDetail
import dev.gitpockit.core.git.ConflictVersions
import dev.gitpockit.core.git.GitLocalChangesException
import dev.gitpockit.core.git.GitOperation
import dev.gitpockit.core.git.OperationResult
import dev.gitpockit.core.git.ReflogEntryInfo
import dev.gitpockit.core.git.ResetMode
import dev.gitpockit.core.git.StashInfo
import dev.gitpockit.core.git.TagInfo
import dev.gitpockit.core.git.FileChange
import dev.gitpockit.core.git.FileDiff
import dev.gitpockit.core.git.WorkingDiffMode
import dev.gitpockit.core.git.CommitInfo
import dev.gitpockit.core.git.GitAuthor
import dev.gitpockit.core.git.GitCredentials
import dev.gitpockit.core.git.GitException
import dev.gitpockit.core.git.GitProgress
import dev.gitpockit.core.git.GitRepository
import dev.gitpockit.core.git.PullResult
import dev.gitpockit.core.git.PushResult
import dev.gitpockit.core.git.RemoteInfo
import dev.gitpockit.core.git.RepoStatus
import kotlinx.coroutines.Job
import org.eclipse.jgit.api.CherryPickResult
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.errors.StashApplyFailureException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.RefNotAdvertisedException
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.RevTag
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteConfig
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.io.NullOutputStream
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.URIish
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import kotlin.coroutines.coroutineContext

internal class JGitRepository(
    private val git: Git,
    private val engine: JGitEngine,
) : GitRepository {

    private val repo get() = git.repository

    override val workTree: File get() = repo.workTree

    override suspend fun status(): RepoStatus = engine.io {
        val s = git.status().call()
        RepoStatus(
            branch = repo.branch,
            added = s.added,
            changed = s.changed,
            removed = s.removed,
            modified = s.modified,
            missing = s.missing,
            untracked = s.untracked,
            conflicting = s.conflicting,
        )
    }

    override suspend fun currentBranch(): String? = engine.io { repo.branch }

    override suspend fun stage(paths: Collection<String>) = engine.io {
        if (paths.isEmpty()) return@io
        val add = git.add()
        paths.forEach(add::addFilepattern)
        add.call()
        // `git add` does not record deletions; stage those separately.
        val missing = git.status().call().missing.filter { m -> paths.any { m == it || m.startsWith("$it/") || it == "." } }
        if (missing.isNotEmpty()) {
            val rm = git.rm().setCached(true)
            missing.forEach(rm::addFilepattern)
            rm.call()
        }
    }

    override suspend fun unstage(paths: Collection<String>) = engine.io {
        if (paths.isEmpty()) return@io
        val reset = git.reset()
        paths.forEach(reset::addPath)
        reset.call()
        Unit
    }

    override suspend fun stageAll() = engine.io {
        git.add().addFilepattern(".").call()
        git.add().addFilepattern(".").setUpdate(true).call() // records deletions
        Unit
    }

    override suspend fun commit(message: String, author: GitAuthor, amend: Boolean): CommitInfo = engine.io {
        val ident = PersonIdent(author.name, author.email)
        git.commit()
            .setMessage(message)
            .setAuthor(ident)
            .setCommitter(ident)
            .setAmend(amend)
            .setSign(false)
            .call()
            .toInfo()
    }

    override suspend fun log(maxCount: Int, path: String?, skip: Int): List<CommitInfo> = engine.io {
        if (repo.resolve(Constants.HEAD) == null) return@io emptyList()
        val cmd = git.log().setMaxCount(maxCount).setSkip(skip)
        path?.let { cmd.addPath(it) }
        cmd.call().map { it.toInfo() }
    }

    override suspend fun commitDetail(commitId: String): CommitDetail = engine.io {
        RevWalk(repo).use { walk ->
            val commit = walk.parseCommit(resolveCommit(commitId))
            val changes = commitEntries(walk, commit).map { e ->
                FileChange(path = e.displayPath, oldPath = e.oldPath.takeIf { e.changeType.isMove }, type = e.changeType.toChangeType())
            }
            CommitDetail(commit.toInfo(), changes)
        }
    }

    override suspend fun commitFileDiff(commitId: String, path: String): FileDiff = engine.io {
        RevWalk(repo).use { walk ->
            val commit = walk.parseCommit(resolveCommit(commitId))
            val entry = commitEntries(walk, commit).firstOrNull { it.newPath == path || it.oldPath == path }
                ?: throw GitException("$path was not changed in ${commitId.take(7)}")
            formatEntries(listOf(entry)).single()
        }
    }

    override suspend fun workingFileDiff(path: String, mode: WorkingDiffMode): FileDiff = engine.io {
        // Working-tree content isn't in the object database, so compare raw bytes ourselves.
        val index = repo.readDirCache()
        val indexBytes = index.getEntry(path)?.objectId?.let { repo.open(it).bytes }
        val (old, new) = when (mode) {
            WorkingDiffMode.UNSTAGED -> {
                val file = File(repo.workTree, path)
                // Untracked files compare against nothing; tracked ones against the index.
                indexBytes to file.takeIf { it.isFile }?.readBytes()
            }
            WorkingDiffMode.STAGED -> headBlob(path) to indexBytes
        }
        diffBytes(path, old, new)
    }

    override suspend fun discardChanges(paths: Collection<String>) = engine.io {
        if (paths.isEmpty()) return@io
        val headTree = repo.resolve("HEAD^{tree}")
        val (inHead, notInHead) = paths.partition { p ->
            headTree != null && TreeWalk.forPath(repo, p, headTree)?.use { true } == true
        }
        if (inHead.isNotEmpty()) {
            // Like `git checkout HEAD -- <paths>`: restores both the index and the working tree.
            val checkout = git.checkout().setStartPoint(Constants.HEAD)
            inHead.forEach(checkout::addPath)
            checkout.call()
        }
        if (notInHead.isNotEmpty()) {
            val index = repo.readDirCache()
            val staged = notInHead.filter { index.getEntry(it) != null }
            if (staged.isNotEmpty()) {
                val rm = git.rm().setCached(true)
                staged.forEach(rm::addFilepattern)
                rm.call()
            }
            notInHead.forEach { File(repo.workTree, it).deleteRecursively() }
        }
    }

    override suspend fun fetch(remote: String, credentials: GitCredentials, progress: GitProgress?) {
        val job = coroutineContext[Job]
        engine.io {
            val cmd = git.fetch().setRemote(remote).setProgressMonitor(progress.toMonitor(job))
            engine.applyCredentials(cmd, credentials)
            cmd.call()
            Unit
        }
    }

    override suspend fun pull(
        remote: String,
        credentials: GitCredentials,
        rebase: Boolean,
        progress: GitProgress?,
    ): PullResult {
        val job = coroutineContext[Job]
        return engine.io {
            val cmd = git.pull()
                .setRemote(remote)
                .setRebase(rebase)
                .setProgressMonitor(progress.toMonitor(job))
            engine.applyCredentials(cmd, credentials)
            val result = try {
                cmd.call()
            } catch (e: RefNotAdvertisedException) {
                // Upstream branch doesn't exist yet (e.g. empty remote before the first push).
                return@io PullResult.UpToDate
            }
            result.rebaseResult?.let { rr ->
                return@io when (rr.status) {
                    RebaseResult.Status.UP_TO_DATE -> PullResult.UpToDate
                    RebaseResult.Status.FAST_FORWARD -> PullResult.FastForward
                    RebaseResult.Status.OK -> PullResult.Merged
                    RebaseResult.Status.STOPPED -> PullResult.Conflicts(git.status().call().conflicting)
                    RebaseResult.Status.CONFLICTS, RebaseResult.Status.UNCOMMITTED_CHANGES ->
                        throw GitLocalChangesException(rr.conflicts.orEmpty() + rr.uncommittedChanges.orEmpty())
                    else -> throw GitException("Rebase failed: ${rr.status}")
                }
            }
            val mr = result.mergeResult
                ?: return@io if (result.isSuccessful) PullResult.UpToDate else throw GitException("Pull failed")
            when (mr.mergeStatus) {
                MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> PullResult.UpToDate
                MergeResult.MergeStatus.FAST_FORWARD, MergeResult.MergeStatus.FAST_FORWARD_SQUASHED -> PullResult.FastForward
                MergeResult.MergeStatus.MERGED, MergeResult.MergeStatus.MERGED_SQUASHED -> PullResult.Merged
                MergeResult.MergeStatus.CONFLICTING -> PullResult.Conflicts(mr.conflicts?.keys.orEmpty())
                else -> throw GitException(
                    "Pull failed: ${mr.mergeStatus}" +
                        (mr.failingPaths?.keys?.joinToString(prefix = " (", postfix = ")") ?: ""),
                )
            }
        }
    }

    override suspend fun push(
        remote: String,
        credentials: GitCredentials,
        force: Boolean,
        pushTags: Boolean,
        progress: GitProgress?,
    ): PushResult {
        val job = coroutineContext[Job]
        return engine.io {
            val cmd = git.push()
                .setRemote(remote)
                .setForce(force)
                .setProgressMonitor(progress.toMonitor(job))
            if (pushTags) cmd.setPushTags()
            engine.applyCredentials(cmd, credentials)
            val rejected = cmd.call()
                .flatMap { it.remoteUpdates }
                .filter { it.status != RemoteRefUpdate.Status.OK && it.status != RemoteRefUpdate.Status.UP_TO_DATE }
                .associate { it.remoteName to (it.message ?: it.status.name) }
            PushResult(rejected)
        }
    }

    override suspend fun branches(includeRemote: Boolean): List<BranchInfo> = engine.io {
        val current = repo.fullBranch
        val cmd = git.branchList()
        if (includeRemote) cmd.setListMode(ListBranchCommand.ListMode.ALL)
        cmd.call().map { ref ->
            val isRemote = ref.name.startsWith(Constants.R_REMOTES)
            BranchInfo(
                name = Repository.shortenRefName(ref.name),
                fullRef = ref.name,
                isRemote = isRemote,
                isCurrent = ref.name == current,
                headId = ref.objectId?.name,
            )
        }
    }

    override suspend fun createBranch(name: String, startPoint: String?, checkout: Boolean) = engine.io {
        if (checkout) {
            val cmd = git.checkout().setCreateBranch(true).setName(name)
            startPoint?.let { cmd.setStartPoint(it) }
            cmd.call()
        } else {
            val cmd = git.branchCreate().setName(name)
            startPoint?.let { cmd.setStartPoint(it) }
            cmd.call()
        }
        Unit
    }

    override suspend fun checkout(branch: String) = engine.io {
        val local = repo.findRef(Constants.R_HEADS + branch)
        if (local != null) {
            git.checkout().setName(branch).call()
        } else {
            // Checking out "origin/feature" or "feature" that only exists remotely: create a tracking branch.
            val remoteRef = repo.findRef(Constants.R_REMOTES + branch)
                ?: repo.refDatabase.getRefsByPrefix(Constants.R_REMOTES).firstOrNull { it.name.endsWith("/$branch") }
                ?: throw GitException("Branch not found: $branch")
            val localName = remoteRef.name.removePrefix(Constants.R_REMOTES).substringAfter('/')
            git.checkout()
                .setCreateBranch(true)
                .setName(localName)
                .setStartPoint(remoteRef.name)
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                .call()
        }
        Unit
    }

    override suspend fun deleteBranch(name: String, force: Boolean) = engine.io {
        git.branchDelete().setBranchNames(name).setForce(force).call()
        Unit
    }

    override suspend fun renameBranch(oldName: String, newName: String) = engine.io {
        git.branchRename().setOldName(oldName).setNewName(newName).call()
        Unit
    }

    override suspend fun remotes(): List<RemoteInfo> = engine.io {
        git.remoteList().call().map { rc ->
            RemoteInfo(
                name = rc.name,
                fetchUrls = rc.urIs.map { it.toString() },
                pushUrls = rc.pushURIs.map { it.toString() },
            )
        }
    }

    override suspend fun addRemote(name: String, url: String) = engine.io {
        git.remoteAdd().setName(name).setUri(URIish(url)).call()
        Unit
    }

    override suspend fun removeRemote(name: String) = engine.io {
        git.remoteRemove().setRemoteName(name).call()
        Unit
    }

    override suspend fun setRemoteUrl(name: String, url: String) = engine.io {
        git.remoteSetUrl().setRemoteName(name).setRemoteUri(URIish(url)).call()
        Unit
    }

    override suspend fun setUpstream(branch: String, remote: String) = engine.io {
        repo.config.apply {
            setString("branch", branch, "remote", remote)
            setString("branch", branch, "merge", Constants.R_HEADS + branch)
            save()
        }
        Unit
    }

    override suspend fun renameRemote(oldName: String, newName: String) = engine.io {
        val cfg = repo.config
        val remotes = cfg.getSubsections("remote")
        if (oldName !in remotes) throw GitException("Remote not found: $oldName")
        if (newName in remotes) throw GitException("Remote already exists: $newName")
        val oldPrefix = "${Constants.R_REMOTES}$oldName/"
        val newPrefix = "${Constants.R_REMOTES}$newName/"

        val old = RemoteConfig(cfg, oldName)
        val renamed = RemoteConfig(cfg, newName)
        old.urIs.forEach(renamed::addURI)
        old.pushURIs.forEach(renamed::addPushURI)
        old.fetchRefSpecs.forEach { spec ->
            val dst = spec.destination
            renamed.addFetchRefSpec(if (dst != null && dst.startsWith(oldPrefix)) spec.setDestination(newPrefix + dst.removePrefix(oldPrefix)) else spec)
        }
        old.pushRefSpecs.forEach(renamed::addPushRefSpec)
        renamed.update(cfg)
        cfg.unsetSection("remote", oldName)
        cfg.getSubsections("branch").forEach { b ->
            if (cfg.getString("branch", b, "remote") == oldName) cfg.setString("branch", b, "remote", newName)
        }
        cfg.save()

        val refs = repo.refDatabase.getRefsByPrefix(oldPrefix)
        // Rename real refs first, then re-point symbolic ones such as origin/HEAD.
        refs.filterNot { it.isSymbolic }.forEach { ref ->
            val result = repo.renameRef(ref.name, newPrefix + ref.name.removePrefix(oldPrefix)).rename()
            if (result !in setOf(RefUpdate.Result.RENAMED, RefUpdate.Result.NO_CHANGE)) {
                throw GitException("Could not rename ${ref.name}: $result")
            }
        }
        refs.filter { it.isSymbolic }.forEach { ref ->
            val target = ref.target.name
            val link = repo.updateRef(newPrefix + ref.name.removePrefix(oldPrefix))
            link.link(if (target.startsWith(oldPrefix)) newPrefix + target.removePrefix(oldPrefix) else target)
            repo.updateRef(ref.name, true).apply { isForceUpdate = true }.delete()
        }
    }

    override fun setIdentity(author: GitAuthor) {
        // In-memory only (no save()): JGit reads user.name/email when it creates commits itself.
        repo.config.setString("user", null, "name", author.name)
        repo.config.setString("user", null, "email", author.email)
    }

    override suspend fun isMerging(): Boolean = engine.io { repo.repositoryState == RepositoryState.MERGING }

    override suspend fun operation(): GitOperation = engine.io { currentOperation() }

    private fun currentOperation(): GitOperation = when (repo.repositoryState) {
        RepositoryState.MERGING, RepositoryState.MERGING_RESOLVED -> GitOperation.MERGE
        RepositoryState.CHERRY_PICKING, RepositoryState.CHERRY_PICKING_RESOLVED -> GitOperation.CHERRY_PICK
        RepositoryState.REVERTING, RepositoryState.REVERTING_RESOLVED -> GitOperation.REVERT
        RepositoryState.REBASING, RepositoryState.REBASING_MERGE, RepositoryState.REBASING_REBASING,
        RepositoryState.REBASING_INTERACTIVE, RepositoryState.APPLY -> GitOperation.REBASE
        else -> GitOperation.NONE
    }

    override suspend fun continueOperation(message: String, author: GitAuthor): OperationResult = engine.io {
        val conflicts = git.status().call().conflicting
        if (conflicts.isNotEmpty()) return@io OperationResult.Conflicts(conflicts)
        when (currentOperation()) {
            GitOperation.NONE -> OperationResult.UpToDate
            GitOperation.REBASE -> {
                var r = git.rebase().setOperation(RebaseCommand.Operation.CONTINUE).call()
                // The resolution made this commit empty (e.g. "keep remote"): drop it and go on.
                if (r.status == RebaseResult.Status.NOTHING_TO_COMMIT) r = git.rebase().setOperation(RebaseCommand.Operation.SKIP).call()
                rebaseResult(r)
            }
            else -> {
                val ident = PersonIdent(author.name, author.email)
                // Default to the message Git prepared (e.g. "Merge branch 'x'" or the picked commit's message).
                val text = message.ifBlank { repo.readMergeCommitMsg()?.trim().orEmpty() }.ifBlank { "Merge" }
                git.commit().setMessage(text).setAuthor(ident).setCommitter(ident).setSign(false).call()
                OperationResult.Done
            }
        }
    }

    override suspend fun abortOperation() = engine.io {
        when (currentOperation()) {
            GitOperation.REBASE -> git.rebase().setOperation(RebaseCommand.Operation.ABORT).call()
            // Merge, cherry-pick and revert: back to the last commit; also clears MERGE_HEAD etc.
            else -> git.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call()
        }
        Unit
    }

    override suspend fun merge(rev: String, message: String?): OperationResult = engine.io {
        val target = repo.findRef(rev) ?: repo.exactRef(rev)
        val cmd = git.merge().setCommit(true).setFastForward(MergeCommand.FastForwardMode.FF)
        if (target != null) cmd.include(target) else cmd.include(resolveCommit(rev))
        message?.let { cmd.setMessage(it) }
        val r = cmd.call()
        when (r.mergeStatus) {
            MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> OperationResult.UpToDate
            MergeResult.MergeStatus.FAST_FORWARD, MergeResult.MergeStatus.FAST_FORWARD_SQUASHED -> OperationResult.FastForward
            MergeResult.MergeStatus.MERGED, MergeResult.MergeStatus.MERGED_SQUASHED -> OperationResult.Done
            MergeResult.MergeStatus.CONFLICTING -> OperationResult.Conflicts(r.conflicts?.keys.orEmpty())
            MergeResult.MergeStatus.FAILED -> throw GitLocalChangesException(r.failingPaths?.keys?.toList().orEmpty())
            else -> throw GitException("Merge failed: ${r.mergeStatus}")
        }
    }

    override suspend fun rebase(upstream: String): OperationResult = engine.io {
        rebaseResult(git.rebase().setUpstream(resolveCommit(upstream)).call())
    }

    private fun rebaseResult(r: RebaseResult): OperationResult = when (r.status) {
        RebaseResult.Status.UP_TO_DATE -> OperationResult.UpToDate
        RebaseResult.Status.FAST_FORWARD -> OperationResult.FastForward
        RebaseResult.Status.OK, RebaseResult.Status.NOTHING_TO_COMMIT -> OperationResult.Done
        RebaseResult.Status.STOPPED -> OperationResult.Conflicts(git.status().call().conflicting)
        RebaseResult.Status.CONFLICTS, RebaseResult.Status.UNCOMMITTED_CHANGES ->
            throw GitLocalChangesException(r.conflicts.orEmpty() + r.uncommittedChanges.orEmpty())
        else -> throw GitException("Rebase failed: ${r.status}")
    }

    override suspend fun cherryPick(commitId: String): OperationResult = engine.io {
        val r = git.cherryPick().include(resolveCommit(commitId)).call()
        when (r.status) {
            CherryPickResult.CherryPickStatus.OK -> OperationResult.Done
            CherryPickResult.CherryPickStatus.CONFLICTING -> OperationResult.Conflicts(git.status().call().conflicting)
            CherryPickResult.CherryPickStatus.FAILED -> throw GitLocalChangesException(r.failingPaths?.keys?.toList().orEmpty())
        }
    }

    override suspend fun revert(commitId: String): OperationResult = engine.io {
        val cmd = git.revert().include(resolveCommit(commitId))
        val commit = cmd.call()
        when {
            commit != null -> OperationResult.Done
            cmd.failingResult != null -> throw GitLocalChangesException(cmd.failingResult.failingPaths?.keys?.toList().orEmpty())
            else -> OperationResult.Conflicts(git.status().call().conflicting.ifEmpty { cmd.unmergedPaths?.toSet().orEmpty() })
        }
    }

    override suspend fun reset(rev: String, mode: ResetMode) = engine.io {
        val type = when (mode) {
            ResetMode.SOFT -> ResetCommand.ResetType.SOFT
            ResetMode.MIXED -> ResetCommand.ResetType.MIXED
            ResetMode.HARD -> ResetCommand.ResetType.HARD
        }
        git.reset().setRef(resolveCommit(rev).name).setMode(type).call()
        Unit
    }

    override suspend fun checkoutDetached(rev: String) = engine.io {
        git.checkout().setName(resolveCommit(rev).name).call()
        Unit
    }

    override suspend fun stashes(): List<StashInfo> = engine.io {
        git.stashList().call().mapIndexed { i, c ->
            StashInfo(i, c.name, c.fullMessage.trim(), Instant.ofEpochSecond(c.commitTime.toLong()))
        }
    }

    override suspend fun stashSave(message: String?, includeUntracked: Boolean, author: GitAuthor): Boolean = engine.io {
        val cmd = git.stashCreate()
            .setIncludeUntracked(includeUntracked)
            .setPerson(PersonIdent(author.name, author.email))
        message?.takeIf { it.isNotBlank() }?.let { cmd.setWorkingDirectoryMessage(it) }
        cmd.call() != null
    }

    override suspend fun stashApply(index: Int, pop: Boolean): OperationResult = engine.io {
        val ref = "stash@{$index}"
        try {
            git.stashApply().setStashRef(ref).setRestoreUntracked(true).call()
        } catch (e: StashApplyFailureException) {
            val conflicts = git.status().call().conflicting
            if (conflicts.isNotEmpty()) return@io OperationResult.Conflicts(conflicts) // the stash is kept
            throw GitLocalChangesException(git.status().call().uncommittedChanges.toList(), e)
        }
        if (pop) git.stashDrop().setStashRef(index).call()
        OperationResult.Done
    }

    override suspend fun stashDrop(index: Int) = engine.io {
        git.stashDrop().setStashRef(index).call()
        Unit
    }

    override suspend fun tags(): List<TagInfo> = engine.io {
        RevWalk(repo).use { walk ->
            git.tagList().call().map { ref ->
                val obj = walk.parseAny(ref.objectId)
                val tag = obj as? RevTag
                val commit = walk.peel(obj)
                TagInfo(
                    name = Repository.shortenRefName(ref.name),
                    commitId = commit.name,
                    annotated = tag != null,
                    message = tag?.fullMessage?.trim(),
                    taggerName = tag?.taggerIdent?.name,
                    time = tag?.taggerIdent?.whenAsInstant ?: (commit as? RevCommit)?.let { Instant.ofEpochSecond(it.commitTime.toLong()) },
                )
            }.sortedByDescending { it.time }
        }
    }

    override suspend fun createTag(name: String, message: String?, rev: String?, author: GitAuthor) = engine.io {
        RevWalk(repo).use { walk ->
            val target = walk.parseAny(rev?.let(::resolveCommit) ?: resolveCommit(Constants.HEAD))
            val cmd = git.tag().setName(name).setObjectId(target).setSigned(false)
            if (message.isNullOrBlank()) {
                cmd.setAnnotated(false)
            } else {
                cmd.setAnnotated(true).setMessage(message).setTagger(PersonIdent(author.name, author.email))
            }
            cmd.call()
        }
        Unit
    }

    override suspend fun deleteTag(name: String) = engine.io {
        git.tagDelete().setTags(name).call()
        Unit
    }

    override suspend fun pushTag(name: String, remote: String, credentials: GitCredentials, delete: Boolean): PushResult = engine.io {
        val spec = if (delete) RefSpec(":${Constants.R_TAGS}$name") else RefSpec("${Constants.R_TAGS}$name:${Constants.R_TAGS}$name")
        val cmd = git.push().setRemote(remote).setRefSpecs(spec)
        engine.applyCredentials(cmd, credentials)
        PushResult(
            cmd.call().flatMap { it.remoteUpdates }
                .filter { it.status != RemoteRefUpdate.Status.OK && it.status != RemoteRefUpdate.Status.UP_TO_DATE }
                .associate { it.remoteName to (it.message ?: it.status.name) },
        )
    }

    override suspend fun reflog(maxCount: Int): List<ReflogEntryInfo> = engine.io {
        if (repo.resolve(Constants.HEAD) == null) return@io emptyList()
        git.reflog().setRef(Constants.HEAD).call().take(maxCount).map { e ->
            ReflogEntryInfo(e.newId.name, e.oldId.name, e.comment, e.who.name, e.who.whenAsInstant)
        }
    }

    override suspend fun conflictVersions(path: String): ConflictVersions = engine.io {
        val index = repo.readDirCache()
        var base: ByteArray? = null
        var ours: ByteArray? = null
        var theirs: ByteArray? = null
        var i = index.findEntry(path)
        while (i in 0 until index.entryCount && index.getEntry(i).pathString == path) {
            val entry = index.getEntry(i)
            val bytes = repo.open(entry.objectId).bytes
            when (entry.stage) {
                DirCacheEntry.STAGE_1 -> base = bytes
                DirCacheEntry.STAGE_2 -> ours = bytes
                DirCacheEntry.STAGE_3 -> theirs = bytes
            }
            i++
        }
        ConflictVersions(path, base, ours, theirs)
    }

    override suspend fun resolveConflict(path: String, content: ByteArray?) = engine.io {
        val file = File(repo.workTree, path)
        if (content != null) {
            file.parentFile?.mkdirs()
            file.writeBytes(content)
            git.add().addFilepattern(path).call()
        } else {
            file.delete()
            git.rm().addFilepattern(path).call()
        }
        Unit
    }

    override suspend fun abortMerge() = engine.io {
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call()
        Unit
    }

    override suspend fun aheadBehind(): Pair<Int, Int>? = engine.io {
        val branch = repo.branch ?: return@io null
        BranchTrackingStatus.of(repo, branch)?.let { it.aheadCount to it.behindCount }
    }

    override fun close() = git.close()

    private fun resolveCommit(id: String) =
        repo.resolve(id) ?: throw GitException("Unknown commit: $id")

    private fun headBlob(path: String): ByteArray? {
        val tree = repo.resolve("HEAD^{tree}") ?: return null
        return TreeWalk.forPath(repo, path, tree)?.use { walk -> repo.open(walk.getObjectId(0)).bytes }
    }

    private fun diffBytes(path: String, old: ByteArray?, new: ByteArray?): FileDiff {
        val type = when {
            old == null -> ChangeType.ADDED
            new == null -> ChangeType.DELETED
            else -> ChangeType.MODIFIED
        }
        val a = old ?: ByteArray(0)
        val b = new ?: ByteArray(0)
        if (RawText.isBinary(a) || RawText.isBinary(b)) {
            return FileDiff(path, null, type, isBinary = true, hunks = emptyList())
        }
        val textA = RawText(a)
        val textB = RawText(b)
        val edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
            .diff(RawTextComparator.DEFAULT, textA, textB)
        val out = ByteArrayOutputStream()
        DiffFormatter(out).use { it.format(edits, textA, textB) }
        return UnifiedDiffParser.parse(out.toString(Charsets.UTF_8), path, null, type)
    }

    /** Diff entries of [commit] against its first parent (or the empty tree for a root commit). */
    private fun commitEntries(walk: RevWalk, commit: RevCommit): List<DiffEntry> {
        val parent = commit.parents.firstOrNull()?.let { walk.parseCommit(it) }
        DiffFormatter(NullOutputStream.INSTANCE).use { f ->
            f.setRepository(repo)
            f.isDetectRenames = true
            return f.scan(parent?.tree, commit.tree)
        }
    }

    private fun formatEntries(entries: List<DiffEntry>): List<FileDiff> = entries.map { entry ->
        val out = ByteArrayOutputStream()
        DiffFormatter(out).use { f ->
            f.setRepository(repo)
            f.format(entry)
        }
        UnifiedDiffParser.parse(
            text = out.toString(Charsets.UTF_8),
            path = entry.displayPath,
            oldPath = entry.oldPath.takeIf { entry.changeType.isMove },
            type = entry.changeType.toChangeType(),
        )
    }

    private fun RevCommit.toInfo() = CommitInfo(
        id = name,
        shortId = name.take(7),
        message = fullMessage,
        authorName = authorIdent.name,
        authorEmail = authorIdent.emailAddress,
        time = Instant.ofEpochSecond(commitTime.toLong()),
        parentIds = parents.map { it.name },
    )
}

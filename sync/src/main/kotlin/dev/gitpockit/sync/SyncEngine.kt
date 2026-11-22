package dev.gitpockit.sync

import dev.gitpockit.core.data.model.ConflictStrategy
import dev.gitpockit.core.data.model.Repo
import dev.gitpockit.core.data.model.SyncMode
import dev.gitpockit.core.data.model.SyncResult
import dev.gitpockit.core.data.model.SyncTrigger
import dev.gitpockit.core.data.repository.RepoRepository
import dev.gitpockit.core.git.GitAuthException
import dev.gitpockit.core.git.GitException
import dev.gitpockit.core.git.GitNetworkException
import dev.gitpockit.core.git.GitRepository
import dev.gitpockit.core.git.PullResult
import dev.gitpockit.core.git.RepoStatus
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Is the device online? Abstracted so the engine can be tested without Android. */
interface NetworkStatus {
    fun isOnline(): Boolean

    /** True on Wi-Fi/Ethernet (not metered mobile data). */
    fun isUnmetered(): Boolean
}

enum class SyncDirection {
    /** Commit local changes, pull, then push. */
    BOTH,
    PULL,
    PUSH,
}

data class SyncOutcome(
    val result: SyncResult,
    val message: String? = null,
    val committed: Boolean = false,
    val pulled: Boolean = false,
    val pushed: Boolean = false,
    /** Conflicted paths when [result] is CONFLICTS. */
    val conflicts: Set<String> = emptySet(),
    /** Local commits (or a pending pull) still need the network. */
    val pendingNetwork: Boolean = false,
    /** Authentication failed; the user must fix the credential. */
    val authFailed: Boolean = false,
)

/**
 * The one sync pipeline used by every trigger (button, schedule, file change, app open/close,
 * tile, widget, automation intents):
 *
 * 1. Refuse to touch a repo that is mid-merge; that needs the user.
 * 2. Commit every local change with the repo's message template (FULL mode only).
 *    This always works offline, so nothing is ever lost.
 * 3. Offline (or on mobile data with "Wi-Fi only")? Stop: [SyncResult.OFFLINE] with
 *    `pendingNetwork`, and the caller queues a retry for when the network is back.
 * 4. Pull. On conflicts, apply the repo's [ConflictStrategy]: ASK leaves the merge for the
 *    conflict resolver; KEEP_LOCAL / KEEP_REMOTE / KEEP_BOTH resolve it automatically.
 * 5. Push if there is anything to push. If the remote moved in the meantime, pull and push once more.
 * 6. Record the outcome on the repo and in the sync log.
 */
class SyncEngine(
    private val ops: RepoOps,
    private val repos: RepoRepository,
    private val network: NetworkStatus,
    private val deviceName: suspend () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    suspend fun sync(repoId: Long, trigger: SyncTrigger, direction: SyncDirection = SyncDirection.BOTH): SyncOutcome {
        val started = clock()
        val outcome = try {
            ops.write(repoId) { repo, git -> run(repo, git, trigger, direction) }
        } catch (e: GitException) {
            SyncOutcome(SyncResult.FAILED, e.message)
        }
        repos.recordSync(
            id = repoId,
            error = outcome.message.takeIf { outcome.result == SyncResult.FAILED || outcome.result == SyncResult.CONFLICTS },
            pending = outcome.pendingNetwork,
        )
        repos.addLog(repoId, trigger, started, outcome.result, outcome.message)
        return outcome
    }

    private suspend fun run(repo: Repo, git: GitRepository, trigger: SyncTrigger, direction: SyncDirection): SyncOutcome {
        val status = git.status()
        if (git.isMerging() || status.conflicting.isNotEmpty()) {
            return SyncOutcome(SyncResult.CONFLICTS, "Resolve the conflicts to finish the merge", conflicts = status.conflicting)
        }

        val fullMode = repo.sync.mode == SyncMode.FULL
        val mayPush = direction == SyncDirection.PUSH || (direction == SyncDirection.BOTH && fullMode)
        val mayPull = direction != SyncDirection.PUSH

        // 2. Commit local work first; this never needs the network.
        var committed = false
        if (direction == SyncDirection.BOTH && fullMode && !status.isClean) {
            git.stageAll()
            git.commit(commitMessage(repo, status), ops.authorFor(repo))
            committed = true
        }

        // 3. Network gate.
        val automatic = trigger != SyncTrigger.MANUAL
        val blockedByMetered = automatic && repo.sync.wifiOnly && !network.isUnmetered()
        if (!network.isOnline() || blockedByMetered) {
            return SyncOutcome(
                SyncResult.OFFLINE,
                if (blockedByMetered) "Waiting for Wi-Fi" else "No network; will sync when back online",
                committed = committed,
                pendingNetwork = true,
            )
        }

        val credentials = ops.credentialsFor(repo)
        val remote = repo.remoteName
        return try {
            // 4. Pull (with conflict handling).
            var pulled = false
            if (mayPull) {
                when (val r = pull(repo, git, credentials)) {
                    is PullStep.Conflicts -> return SyncOutcome(
                        SyncResult.CONFLICTS,
                        "Conflicts in ${r.paths.size} file(s)",
                        committed = committed,
                        conflicts = r.paths,
                    )
                    is PullStep.Done -> pulled = r.changed
                }
            }

            // 5. Push if there is anything to push; retry once if the remote moved.
            var pushed = false
            if (mayPush && hasSomethingToPush(git)) {
                var result = git.push(remote = remote, credentials = credentials)
                if (!result.isSuccess && direction == SyncDirection.BOTH) {
                    when (val r = pull(repo, git, credentials)) {
                        is PullStep.Conflicts -> return SyncOutcome(
                            SyncResult.CONFLICTS,
                            "Conflicts in ${r.paths.size} file(s)",
                            committed = committed,
                            conflicts = r.paths,
                        )
                        is PullStep.Done -> pulled = pulled || r.changed
                    }
                    result = git.push(remote = remote, credentials = credentials)
                }
                if (!result.isSuccess) {
                    return SyncOutcome(
                        SyncResult.FAILED,
                        "Push rejected: ${result.rejectedRefs.values.joinToString()}",
                        committed = committed,
                        pulled = pulled,
                    )
                }
                pushed = true
            }

            val changed = committed || pulled || pushed
            SyncOutcome(
                if (changed) SyncResult.SYNCED else SyncResult.UP_TO_DATE,
                describe(committed, pulled, pushed),
                committed = committed,
                pulled = pulled,
                pushed = pushed,
            )
        } catch (e: GitAuthException) {
            SyncOutcome(SyncResult.FAILED, e.message, committed = committed, authFailed = true)
        } catch (e: GitNetworkException) {
            // Flaky or captive network: keep the local commit and retry later.
            SyncOutcome(SyncResult.OFFLINE, e.message, committed = committed, pendingNetwork = true)
        } catch (e: GitException) {
            // Anything else (remote gone, local changes in the way…): report it, but keep
            // the fact that local work was safely committed.
            SyncOutcome(SyncResult.FAILED, e.message, committed = committed)
        }
    }

    private sealed interface PullStep {
        data class Done(val changed: Boolean) : PullStep
        data class Conflicts(val paths: Set<String>) : PullStep
    }

    private suspend fun pull(repo: Repo, git: GitRepository, credentials: dev.gitpockit.core.git.GitCredentials): PullStep =
        when (val r = git.pull(remote = repo.remoteName, credentials = credentials)) {
            PullResult.UpToDate -> PullStep.Done(changed = false)
            PullResult.FastForward, PullResult.Merged -> PullStep.Done(changed = true)
            is PullResult.Conflicts -> {
                if (repo.sync.conflictStrategy == ConflictStrategy.ASK) {
                    PullStep.Conflicts(r.paths)
                } else {
                    autoResolve(repo, git, r.paths)
                    PullStep.Done(changed = true)
                }
            }
        }

    /** Applies KEEP_LOCAL / KEEP_REMOTE / KEEP_BOTH to every conflicted file and commits the merge. */
    private suspend fun autoResolve(repo: Repo, git: GitRepository, paths: Set<String>) {
        val strategy = repo.sync.conflictStrategy
        for (path in paths.sorted()) {
            val v = git.conflictVersions(path)
            when (strategy) {
                ConflictStrategy.KEEP_LOCAL -> git.resolveConflict(path, v.ours)
                ConflictStrategy.KEEP_REMOTE -> git.resolveConflict(path, v.theirs)
                ConflictStrategy.KEEP_BOTH -> {
                    // The remote version keeps the original name, so other devices see no surprise;
                    // the local version is saved next to it as a clearly named copy.
                    git.resolveConflict(path, v.theirs)
                    if (v.ours != null && !v.ours.contentEquals(v.theirs)) {
                        git.resolveConflict(conflictCopyName(path), v.ours)
                    }
                }
                ConflictStrategy.ASK -> error("unreachable")
            }
        }
        val label = when (strategy) {
            ConflictStrategy.KEEP_LOCAL -> "kept local version"
            ConflictStrategy.KEEP_REMOTE -> "kept remote version"
            else -> "kept both versions"
        }
        git.commit("Merge remote changes ($label of ${paths.size} file(s))", ops.authorFor(repo))
    }

    /** `notes/todo.md` → `notes/todo (conflict Pixel 8 2026-11-22 1530).md` */
    suspend fun conflictCopyName(path: String): String {
        val dir = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val file = path.substringAfterLast('/')
        val dot = file.lastIndexOf('.').takeIf { it > 0 } ?: file.length
        val stamp = now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm"))
        val device = deviceName().replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().ifEmpty { "device" }
        return "$dir${file.substring(0, dot)} (conflict $device $stamp)${file.substring(dot)}"
    }

    private suspend fun hasSomethingToPush(git: GitRepository): Boolean {
        val ab = git.aheadBehind()
        // No upstream yet (new repo/branch): push to create it, as long as there is a commit.
        return if (ab == null) git.log(maxCount = 1).isNotEmpty() else ab.first > 0
    }

    internal suspend fun commitMessage(repo: Repo, status: RepoStatus): String {
        val files = (status.added + status.changed + status.modified + status.removed + status.missing + status.untracked)
            .map { it.substringAfterLast('/') }
            .distinct()
            .sorted()
        val fileList = when {
            files.size <= 3 -> files.joinToString()
            else -> files.take(3).joinToString() + " and ${files.size - 3} more"
        }
        val time = now()
        return (repo.sync.commitTemplate?.takeIf { it.isNotBlank() } ?: DEFAULT_TEMPLATE)
            .replace("{device}", deviceName())
            .replace("{date}", time.format(DateTimeFormatter.ISO_LOCAL_DATE))
            .replace("{time}", time.format(DateTimeFormatter.ofPattern("HH:mm")))
            .replace("{count}", files.size.toString())
            .replace("{files}", fileList)
    }

    private fun describe(committed: Boolean, pulled: Boolean, pushed: Boolean): String = buildList {
        if (committed) add("committed")
        if (pulled) add("pulled")
        if (pushed) add("pushed")
    }.joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { "Already up to date" }

    companion object {
        const val DEFAULT_TEMPLATE = "Sync from {device}: {files}"
    }
}

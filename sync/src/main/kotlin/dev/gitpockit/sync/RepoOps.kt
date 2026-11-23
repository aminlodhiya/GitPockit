package dev.gitpockit.sync

import dev.gitpockit.core.data.model.Repo
import dev.gitpockit.core.data.repository.CredentialRepository
import dev.gitpockit.core.data.repository.RepoRepository
import dev.gitpockit.core.git.GitAuthor
import dev.gitpockit.core.git.GitCredentials
import dev.gitpockit.core.git.GitEngine
import dev.gitpockit.core.git.GitException
import dev.gitpockit.core.git.GitRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Supplies the global commit author (from app settings). */
fun interface AuthorProvider {
    suspend fun defaultAuthor(): GitAuthor
}

/**
 * Single entry point for running Git work on a registered repo.
 *
 * - Serializes operations per repo, so screens and background sync never fight over the index lock.
 * - Resolves the repo's saved credential and commit author.
 * - Broadcasts [changes] after mutating operations so every open screen can refresh, and exposes
 *   [busy] so the UI (and the file watcher) know when a repo is being worked on.
 */
class RepoOps(
    private val engine: GitEngine,
    private val repos: RepoRepository,
    private val credentials: CredentialRepository,
    private val authors: AuthorProvider,
) {
    private val locks = ConcurrentHashMap<Long, Mutex>()
    private val _changes = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    private val _busy = MutableStateFlow<Set<Long>>(emptySet())

    /** Emits a repo id whenever that repo's state changed (commit, checkout, pull…). */
    val changes: SharedFlow<Long> = _changes

    /** Repos with a mutating operation (commit, pull, sync…) in progress. */
    val busy: StateFlow<Set<Long>> = _busy

    /** Tells open screens that files changed outside a Git operation (e.g. saved in the editor). */
    fun notifyChanged(id: Long) {
        _changes.tryEmit(id)
    }

    suspend fun repo(id: Long): Repo = repos.get(id) ?: throw GitException("Repository no longer exists")

    suspend fun credentialsFor(repo: Repo): GitCredentials = credentials.resolve(repo.credentialId)

    suspend fun trustedKeys() = credentials.trustedKeys()

    suspend fun authorFor(repo: Repo): GitAuthor {
        val name = repo.authorName
        val email = repo.authorEmail
        return if (!name.isNullOrBlank() && !email.isNullOrBlank()) GitAuthor(name, email) else authors.defaultAuthor()
    }

    /** Runs a read-only [block]; no change event is emitted. */
    suspend fun <T> read(id: Long, block: suspend (Repo, GitRepository) -> T): T = run(id, mutate = false, block)

    /** Runs a mutating [block] and notifies observers afterwards (even if it failed halfway). */
    suspend fun <T> write(id: Long, block: suspend (Repo, GitRepository) -> T): T = run(id, mutate = true, block)

    private suspend fun <T> run(id: Long, mutate: Boolean, block: suspend (Repo, GitRepository) -> T): T {
        val lock = locks.getOrPut(id) { Mutex() }
        return lock.withLock {
            val repo = repo(id)
            if (!repo.directory.exists()) throw GitException("Folder not found: ${repo.directory.path}")
            if (mutate) _busy.update { it + id }
            try {
                engine.open(repo.directory).use { git ->
                    // Commits JGit makes by itself (merges, rebases…) get the right author.
                    git.setIdentity(authorFor(repo))
                    // Always set explicitly: a repo's own config may ask for a key that isn't on this device.
                    git.setSigning(credentials.signingKey(repo.signingCredentialId))
                    git.setLfsAutoDownload(repo.sync.lfsDownload)
                    block(repo, git)
                }
            } finally {
                if (mutate) {
                    _busy.update { it - id }
                    _changes.tryEmit(id)
                }
            }
        }
    }
}

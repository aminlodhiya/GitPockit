package dev.gitpockit.domain

import dev.gitpockit.core.data.model.Repo
import dev.gitpockit.core.data.repository.CredentialRepository
import dev.gitpockit.core.data.repository.RepoRepository
import dev.gitpockit.core.git.GitAuthor
import dev.gitpockit.core.git.GitCredentials
import dev.gitpockit.core.git.GitEngine
import dev.gitpockit.core.git.GitException
import dev.gitpockit.core.git.GitRepository
import dev.gitpockit.data.SettingsRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entry point for running Git work on a registered repo.
 *
 * - Serializes operations per repo, so two screens (or later, background sync) never fight over
 *   the index lock.
 * - Resolves the repo's saved credential and commit author.
 * - Broadcasts [changes] after mutating operations so every open screen can refresh.
 */
@Singleton
class RepoOps @Inject constructor(
    private val engine: GitEngine,
    private val repos: RepoRepository,
    private val credentials: CredentialRepository,
    private val settings: SettingsRepository,
) {
    private val locks = ConcurrentHashMap<Long, Mutex>()
    private val _changes = MutableSharedFlow<Long>(extraBufferCapacity = 16)

    /** Emits a repo id whenever that repo's state changed (commit, checkout, pull…). */
    val changes: SharedFlow<Long> = _changes

    suspend fun repo(id: Long): Repo = repos.get(id) ?: throw GitException("Repository no longer exists")

    suspend fun credentialsFor(repo: Repo): GitCredentials = credentials.resolve(repo.credentialId)

    suspend fun authorFor(repo: Repo): GitAuthor {
        val name = repo.authorName
        val email = repo.authorEmail
        return if (!name.isNullOrBlank() && !email.isNullOrBlank()) GitAuthor(name, email) else settings.author.first()
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
            try {
                engine.open(repo.directory).use { git -> block(repo, git) }
            } finally {
                if (mutate) _changes.tryEmit(id)
            }
        }
    }
}

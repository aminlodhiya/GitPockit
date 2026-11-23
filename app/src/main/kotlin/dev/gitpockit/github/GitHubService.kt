package dev.gitpockit.github

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gitpockit.BuildConfig
import dev.gitpockit.core.data.db.GitHubCacheDao
import dev.gitpockit.core.data.db.GitHubCacheEntity
import dev.gitpockit.core.data.db.PendingCommentDao
import dev.gitpockit.core.data.db.PendingCommentEntity
import dev.gitpockit.core.data.model.Credential
import dev.gitpockit.core.data.model.Repo
import dev.gitpockit.core.data.repository.CredentialRepository
import dev.gitpockit.core.github.DeviceCode
import dev.gitpockit.core.github.DeviceFlow
import dev.gitpockit.core.github.GitHubAuthException
import dev.gitpockit.core.github.GitHubClient
import dev.gitpockit.core.github.GitHubException
import dev.gitpockit.core.github.GitHubNetworkException
import dev.gitpockit.core.github.RepoSlug
import dev.gitpockit.sync.RepoOps
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A value from GitHub, or the last cached copy when offline. */
data class Loaded<T>(val value: T, val fromCache: Boolean, val fetchedAt: Long?, val error: String? = null)

/**
 * GitHub integration: accounts (device-flow or token sign-in), API access per repository,
 * an offline cache of what was viewed, and a queue for comments written offline.
 */
@Singleton
class GitHubService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: HttpClientEngine,
    private val credentials: CredentialRepository,
    private val ops: RepoOps,
    private val cache: GitHubCacheDao,
    private val pending: PendingCommentDao,
) {
    private val clients = ConcurrentHashMap<Long, GitHubClient>()

    val accounts: Flow<List<Credential>> = credentials.credentials.map { list -> list.filter { it.isGitHubAccount } }

    val canUseDeviceFlow: Boolean get() = BuildConfig.GITHUB_CLIENT_ID.isNotBlank()

    // ---- sign-in

    suspend fun startSignIn(): Pair<DeviceFlow, DeviceCode> {
        val flow = DeviceFlow(engine, BuildConfig.GITHUB_CLIENT_ID)
        return flow to flow.start()
    }

    /** Waits for approval on github.com, then saves the account. Returns the login. */
    suspend fun finishSignIn(flow: DeviceFlow, code: DeviceCode): String = saveAccount(flow.awaitToken(code))

    /** Validates a personal access token against GitHub and saves the account. */
    suspend fun signInWithToken(token: String): String = saveAccount(token.trim())

    private suspend fun saveAccount(token: String): String {
        val user = GitHubClient(engine, { token }).user()
        val id = credentials.addGitHubAccount(user.login, token)
        clients.remove(id)
        return user.login
    }

    suspend fun removeAccount(id: Long) {
        clients.remove(id)
        credentials.delete(id)
    }

    // ---- API access

    fun client(credentialId: Long): GitHubClient = clients.getOrPut(credentialId) {
        GitHubClient(engine, { credentials.token(credentialId) })
    }

    /**
     * The credential to talk to GitHub with for [repo]: its own HTTPS credential if it has one,
     * otherwise the first signed-in GitHub account. Null if neither exists.
     */
    suspend fun credentialFor(repo: Repo): Long? {
        repo.credentialId?.let { id -> if (credentials.token(id) != null) return id }
        return accounts.first().firstOrNull()?.id
    }

    /** owner/name when the repo's sync remote is on github.com. */
    suspend fun slugFor(repo: Repo): RepoSlug? = runCatching {
        ops.read(repo.id) { r, git -> git.remotes().firstOrNull { it.name == r.remoteName }?.fetchUrls?.firstOrNull() }
    }.getOrNull()?.let(RepoSlug::fromRemoteUrl)

    // ---- offline cache

    /**
     * Fetches from GitHub and caches the result; when offline (or GitHub fails), returns the cached
     * copy with [Loaded.error] set. Throws only if nothing is cached.
     */
    suspend fun <T> cached(key: String, serializer: KSerializer<T>, fetch: suspend () -> T): Loaded<T> {
        val json = GitHubClient.JSON
        return try {
            val value = fetch()
            cache.put(GitHubCacheEntity(key, json.encodeToString(serializer, value), System.currentTimeMillis()))
            Loaded(value, fromCache = false, fetchedAt = System.currentTimeMillis())
        } catch (e: GitHubException) {
            if (e is GitHubAuthException) throw e
            val hit = cache.get(key) ?: throw e
            Loaded(json.decodeFromString(serializer, hit.json), fromCache = true, fetchedAt = hit.fetchedAt, error = e.message)
        }
    }

    // ---- comments (queued when offline)

    /** Posts a comment; when offline it's queued and sent automatically later. Returns true if sent now. */
    suspend fun comment(credentialId: Long, slug: RepoSlug, number: Int, body: String): Boolean = try {
        client(credentialId).comment(slug, number, body)
        true
    } catch (e: GitHubNetworkException) {
        pending.insert(PendingCommentEntity(credentialId = credentialId, owner = slug.owner, repo = slug.name, number = number, body = body, createdAt = System.currentTimeMillis()))
        schedulePendingComments()
        false
    }

    fun pendingComments(slug: RepoSlug, number: Int) = pending.observe(slug.owner, slug.name, number)

    /** Sends queued comments. Returns true if some are still waiting for the network. */
    suspend fun flushPending(): Boolean {
        var waiting = false
        for (c in pending.all()) {
            try {
                client(c.credentialId).comment(RepoSlug(c.owner, c.repo), c.number, c.body)
                pending.delete(c.id)
            } catch (e: GitHubNetworkException) {
                waiting = true
            } catch (e: GitHubException) {
                // A permanent problem (deleted issue, revoked token): keep it visible with the reason, retry a few times.
                pending.failed(c.id, e.message)
                if (c.attempts >= 5) pending.delete(c.id) else waiting = true
            }
        }
        return waiting
    }

    fun schedulePendingComments() {
        val work = OneTimeWorkRequestBuilder<PendingCommentsWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("github-pending-comments", ExistingWorkPolicy.KEEP, work)
    }
}

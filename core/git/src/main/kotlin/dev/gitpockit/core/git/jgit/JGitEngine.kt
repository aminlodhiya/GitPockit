package dev.gitpockit.core.git.jgit

import dev.gitpockit.core.git.CloneOptions
import dev.gitpockit.core.git.GitAuthException
import dev.gitpockit.core.git.GitBranchNotMergedException
import dev.gitpockit.core.git.GitLocalChangesException
import dev.gitpockit.core.git.GitCredentials
import dev.gitpockit.core.git.GitEngine
import dev.gitpockit.core.git.GitException
import dev.gitpockit.core.git.GitNetworkException
import dev.gitpockit.core.git.GitProgress
import dev.gitpockit.core.git.GitRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.api.errors.NotMergedException
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.RepositoryCache
import dev.gitpockit.core.git.jgit.lfs.PockitLfs
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.util.FS
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * [GitEngine] backed by Eclipse JGit.
 *
 * @param appDataDir private directory used as the Git "home" (global config, known_hosts).
 *   On Android pass `context.filesDir`; there is no usable $HOME there.
 */
class JGitEngine(
    private val appDataDir: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GitEngine {

    init {
        HostConfigIsolation.ensure() // before anything reads Git config
        JschSetup.ensure()
        PockitLfs.ensure()
        appDataDir.mkdirs()
        FS.DETECTED.setUserHome(appDataDir)
    }

    internal val knownHostsFile: File get() = File(appDataDir, ".ssh/known_hosts")

    override suspend fun init(directory: File, initialBranch: String): GitRepository =
        io {
            directory.mkdirs()
            val git = Git.init().setDirectory(directory).setInitialBranch(initialBranch).call()
            JGitRepository(git, this)
        }

    override suspend fun clone(options: CloneOptions, progress: GitProgress?): GitRepository {
        val repo = cloneInternal(options, progress)
        // LFS files were checked out as pointers; fetch their content now (best effort).
        if (options.downloadLfs && repo.usesLfs()) runCatching { repo.lfsDownload("origin", options.credentials) }
        return repo
    }

    private suspend fun cloneInternal(options: CloneOptions, progress: GitProgress?): GitRepository {
        val job = coroutineContext[Job]
        return io {
            if (isRemoteEmpty(options)) return@io initTrackingEmptyRemote(options)
            val cmd = Git.cloneRepository()
                .setURI(options.url)
                .setDirectory(options.directory)
                .setCloneSubmodules(options.cloneSubmodules)
                .setProgressMonitor(progress.toMonitor(job))
            options.branch?.let { cmd.setBranch(it) }
            if (options.depth > 0) cmd.setDepth(options.depth)
            applyCredentials(cmd, options.credentials)
            try {
                JGitRepository(cmd.call(), this)
            } catch (e: Exception) {
                // Don't leave a half-cloned directory behind.
                options.directory.deleteRecursively()
                throw mapException(e)
            }
        }
    }

    /** A freshly created hosted repo has no refs at all; JGit's clone fails on those. */
    private fun isRemoteEmpty(options: CloneOptions): Boolean {
        val cmd = Git.lsRemoteRepository().setRemote(options.url)
        applyCredentials(cmd, options.credentials)
        return cmd.call().isEmpty()
    }

    /** Equivalent of cloning an empty repo: init + origin remote + upstream config for the branch. */
    private fun initTrackingEmptyRemote(options: CloneOptions): GitRepository {
        val branch = options.branch ?: "main"
        val git = Git.init().setDirectory(options.directory).setInitialBranch(branch).call()
        git.remoteAdd().setName("origin").setUri(URIish(options.url)).call()
        git.repository.config.apply {
            setString("branch", branch, "remote", "origin")
            setString("branch", branch, "merge", Constants.R_HEADS + branch)
            save()
        }
        return JGitRepository(git, this)
    }

    override suspend fun open(directory: File): GitRepository = io {
        if (!isRepository(directory)) throw GitException("Not a Git repository: $directory")
        JGitRepository(Git.open(directory), this)
    }

    override fun isRepository(directory: File): Boolean {
        val dotGit = File(directory, ".git")
        if (RepositoryCache.FileKey.isGitRepository(dotGit, FS.DETECTED)) return true
        // Submodules and worktrees use a ".git" *file*: "gitdir: <path to the real git dir>".
        if (!dotGit.isFile) return false
        val target = dotGit.readText().trim().removePrefix("gitdir:").trim().takeIf { it.isNotEmpty() } ?: return false
        val gitDir = File(target).let { if (it.isAbsolute) it else File(directory, target) }
        return RepositoryCache.FileKey.isGitRepository(gitDir.canonicalFile, FS.DETECTED)
    }

    internal suspend fun <T> io(block: () -> T): T = withContext(dispatcher) {
        try {
            block()
        } catch (e: GitException) {
            throw e
        } catch (e: Exception) {
            throw mapException(e)
        }
    }

    /** SSH session factory for [credentials] (used for LFS's git-lfs-authenticate), or null. */
    internal fun sshFactory(credentials: GitCredentials): SshSessionFactory? =
        (credentials as? GitCredentials.SshKey)?.let { KeySshSessionFactory(it, knownHostsFile) }

    internal fun applyCredentials(cmd: TransportCommand<*, *>, credentials: GitCredentials) {
        when (credentials) {
            GitCredentials.None -> Unit
            is GitCredentials.UserPassword ->
                cmd.setCredentialsProvider(
                    UsernamePasswordCredentialsProvider(credentials.username, credentials.password),
                )
            is GitCredentials.SshKey -> {
                val factory = KeySshSessionFactory(credentials, knownHostsFile)
                cmd.setTransportConfigCallback { transport ->
                    if (transport is SshTransport) transport.sshSessionFactory = factory
                }
            }
        }
    }

    internal fun mapException(e: Exception): GitException {
        if (e is GitException) return e
        val msg = e.message.orEmpty()
        return when {
            e is NotMergedException -> GitBranchNotMergedException(msg.substringAfterLast(' ').ifBlank { "branch" }, e)
            e is CheckoutConflictException -> GitLocalChangesException(e.conflictingPaths.orEmpty(), e)
            e is org.eclipse.jgit.errors.CheckoutConflictException ->
                GitLocalChangesException(listOfNotNull(e.message?.substringAfterLast(' ')), e)
            e is TransportException && AUTH_HINTS.any { msg.contains(it, ignoreCase = true) } ->
                GitAuthException("Authentication failed. Check your username, token or SSH key.", e)
            e is TransportException ->
                GitNetworkException("Could not reach the remote: $msg", e)
            else -> GitException(msg.ifBlank { e.javaClass.simpleName }, e)
        }
    }

    private companion object {
        val AUTH_HINTS = listOf("not authorized", "auth fail", "authentication", "401", "403", "credentials")
    }
}

/** Adapts a [GitProgress] to JGit's [ProgressMonitor]; cancels when the coroutine is cancelled. */
internal fun GitProgress?.toMonitor(job: Job?): ProgressMonitor = object : ProgressMonitor {
    private var task = ""
    private var total = 0
    private var done = 0

    override fun start(totalTasks: Int) = Unit

    override fun beginTask(title: String, totalWork: Int) {
        task = title
        total = totalWork
        done = 0
        this@toMonitor?.onProgress(task, done, total)
    }

    override fun update(completed: Int) {
        done += completed
        this@toMonitor?.onProgress(task, done, total)
    }

    override fun endTask() = Unit

    override fun isCancelled(): Boolean = job?.isActive == false

    override fun showDuration(enabled: Boolean) = Unit
}

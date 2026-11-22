package dev.gitpockit.core.data.repository

import androidx.room.withTransaction
import dev.gitpockit.core.data.db.AppTriggerEntity
import dev.gitpockit.core.data.db.ContainerEntity
import dev.gitpockit.core.data.db.SyncLogEntity
import dev.gitpockit.core.data.model.AppTrigger
import dev.gitpockit.core.data.model.ConflictStrategy
import dev.gitpockit.core.data.model.SyncLogEntry
import dev.gitpockit.core.data.model.SyncMode
import dev.gitpockit.core.data.model.SyncResult
import dev.gitpockit.core.data.model.SyncSettings
import dev.gitpockit.core.data.model.SyncTrigger
import dev.gitpockit.core.data.db.GitPockitDatabase
import dev.gitpockit.core.data.db.RepoEntity
import dev.gitpockit.core.data.model.Container
import dev.gitpockit.core.data.model.Repo
import dev.gitpockit.core.git.GitAuthor
import dev.gitpockit.core.git.GitEngine
import dev.gitpockit.core.git.GitException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

private const val MINUTES_PER_DAY = 24 * 60

/** The list of repositories GitPockit manages, wherever they live on the device, and their containers. */
class RepoRepository(
    private val db: GitPockitDatabase,
    private val engine: GitEngine,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val repoDao = db.repoDao()
    private val containerDao = db.containerDao()
    private val logDao = db.syncLogDao()
    private val triggerDao = db.appTriggerDao()

    val repos: Flow<List<Repo>> = repoDao.observeAll().map { list -> list.map { it.toModel() } }

    val containers: Flow<List<Container>> =
        containerDao.observeAll().map { list -> list.map { Container(it.id, it.name) } }

    fun observe(id: Long): Flow<Repo?> = repoDao.observe(id).map { it?.toModel() }

    suspend fun get(id: Long): Repo? = repoDao.get(id)?.toModel()

    /**
     * Adds an existing repository on disk to GitPockit.
     * Throws [GitException] if [directory] is not a repository or is already registered.
     */
    suspend fun register(
        directory: File,
        name: String = directory.name,
        credentialId: Long? = null,
        containerId: Long? = null,
    ): Repo {
        val dir = withContext(Dispatchers.IO) { directory.canonicalFile }
        if (!engine.isRepository(dir)) throw GitException("${dir.path} is not a Git repository")
        repoDao.getByPath(dir.path)?.let { throw GitException("\"${it.name}\" already uses this folder") }
        val id = repoDao.insert(
            RepoEntity(
                name = name.trim().ifBlank { dir.name },
                localPath = dir.path,
                credentialId = credentialId,
                containerId = containerId,
                createdAt = clock(),
            ),
        )
        return checkNotNull(get(id))
    }

    /** Removes the repo from GitPockit. Files are deleted only when [deleteFiles] is true. */
    suspend fun remove(id: Long, deleteFiles: Boolean) {
        val repo = repoDao.get(id) ?: return
        repoDao.delete(id)
        if (deleteFiles) withContext(Dispatchers.IO) { File(repo.localPath).deleteRecursively() }
    }

    suspend fun rename(id: Long, name: String) {
        val repo = repoDao.get(id) ?: return
        repoDao.update(repo.copy(name = name.trim().ifBlank { repo.name }))
    }

    suspend fun moveToContainer(id: Long, containerId: Long?) = repoDao.setContainer(id, containerId)

    suspend fun setCredential(id: Long, credentialId: Long?) = repoDao.setCredential(id, credentialId)

    /** Per-repo author override; pass null to fall back to the global author. */
    suspend fun setAuthor(id: Long, author: GitAuthor?) {
        val repo = repoDao.get(id) ?: return
        repoDao.update(repo.copy(authorName = author?.name, authorEmail = author?.email))
    }

    suspend fun setRemoteName(id: Long, remote: String) {
        val repo = repoDao.get(id) ?: return
        repoDao.update(repo.copy(remoteName = remote))
    }

    suspend fun all(): List<Repo> = repoDao.all().map { it.toModel() }

    /** Stores the outcome of a sync. [pending] marks local work still waiting to be pushed. */
    suspend fun recordSync(id: Long, error: String?, pending: Boolean = false) =
        repoDao.setSyncResult(id, clock(), error, pending)

    suspend fun updateSyncSettings(id: Long, settings: SyncSettings) {
        val repo = repoDao.get(id) ?: return
        repoDao.update(
            repo.copy(
                syncMode = settings.mode.name,
                conflictStrategy = settings.conflictStrategy.name,
                commitTemplate = settings.commitTemplate?.takeIf { it.isNotBlank() },
                syncIntervalMinutes = settings.intervalMinutes,
                syncOnChange = settings.onFileChange,
                wifiOnly = settings.wifiOnly,
                syncTimes = encodeTimes(settings.dailyTimes),
                pullRebase = settings.pullRebase,
            ),
        )
    }

    // ---- Sync log

    fun observeLog(repoId: Long): Flow<List<SyncLogEntry>> = logDao.observe(repoId).map { list ->
        list.map {
            SyncLogEntry(
                id = it.id,
                repoId = it.repoId,
                trigger = enumOr(it.trigger, SyncTrigger.MANUAL),
                startedAt = it.startedAt,
                durationMs = it.durationMs,
                result = enumOr(it.result, SyncResult.FAILED),
                message = it.message,
            )
        }
    }

    suspend fun addLog(repoId: Long, trigger: SyncTrigger, startedAt: Long, result: SyncResult, message: String?) {
        logDao.insert(
            SyncLogEntity(
                repoId = repoId,
                trigger = trigger.name,
                startedAt = startedAt,
                durationMs = clock() - startedAt,
                result = result.name,
                message = message,
            ),
        )
        logDao.prune(repoId)
    }

    // ---- App triggers

    val appTriggers: Flow<List<AppTrigger>> = triggerDao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun allAppTriggers(): List<AppTrigger> = triggerDao.all().map { it.toModel() }

    fun observeAppTriggers(repoId: Long): Flow<List<AppTrigger>> =
        triggerDao.observeForRepo(repoId).map { list -> list.map { it.toModel() } }

    suspend fun setAppTrigger(trigger: AppTrigger) {
        if (!trigger.onOpen && !trigger.onClose) {
            triggerDao.delete(trigger.packageName, trigger.repoId)
        } else {
            triggerDao.upsert(AppTriggerEntity(trigger.packageName, trigger.repoId, trigger.onOpen, trigger.onClose))
        }
    }

    suspend fun removeAppTrigger(packageName: String, repoId: Long) = triggerDao.delete(packageName, repoId)

    private fun encodeTimes(times: List<Int>): String =
        times.filter { it in 0 until MINUTES_PER_DAY }.distinct().sorted().joinToString(",")

    private fun decodeTimes(raw: String): List<Int> =
        raw.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 0 until MINUTES_PER_DAY }.distinct().sorted()

    private fun AppTriggerEntity.toModel() = AppTrigger(packageName, repoId, onOpen, onClose)

    private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
        runCatching { enumValueOf<E>(name) }.getOrDefault(fallback)

    suspend fun createContainer(name: String): Long =
        db.withTransaction { containerDao.insert(ContainerEntity(name = name.trim(), position = containerDao.nextPosition())) }

    suspend fun renameContainer(id: Long, name: String) = containerDao.rename(id, name.trim())

    /** Deletes the container; its repos move back to "no container" (never deleted). */
    suspend fun deleteContainer(id: Long) = containerDao.delete(id)

    private fun RepoEntity.toModel() = Repo(
        id = id,
        name = name,
        directory = File(localPath),
        containerId = containerId,
        credentialId = credentialId,
        remoteName = remoteName,
        authorName = authorName,
        authorEmail = authorEmail,
        lastSyncAt = lastSyncAt,
        lastError = lastError,
        sync = SyncSettings(
            mode = enumOr(syncMode, SyncMode.FULL),
            conflictStrategy = enumOr(conflictStrategy, ConflictStrategy.ASK),
            commitTemplate = commitTemplate,
            intervalMinutes = syncIntervalMinutes,
            onFileChange = syncOnChange,
            wifiOnly = wifiOnly,
            dailyTimes = decodeTimes(syncTimes),
            pullRebase = pullRebase,
        ),
        pendingSync = pendingSync,
    )
}

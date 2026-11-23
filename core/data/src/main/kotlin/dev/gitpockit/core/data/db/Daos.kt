package dev.gitpockit.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RepoDao {
    @Query("SELECT * FROM repos ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<RepoEntity>>

    @Query("SELECT * FROM repos WHERE id = :id")
    fun observe(id: Long): Flow<RepoEntity?>

    @Query("SELECT * FROM repos WHERE id = :id")
    suspend fun get(id: Long): RepoEntity?

    @Query("SELECT * FROM repos WHERE localPath = :path")
    suspend fun getByPath(path: String): RepoEntity?

    @Insert
    suspend fun insert(repo: RepoEntity): Long

    @Update
    suspend fun update(repo: RepoEntity)

    @Query("DELETE FROM repos WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE repos SET containerId = :containerId WHERE id = :id")
    suspend fun setContainer(id: Long, containerId: Long?)

    @Query("UPDATE repos SET credentialId = :credentialId WHERE id = :id")
    suspend fun setCredential(id: Long, credentialId: Long?)

    @Query("UPDATE repos SET signingCredentialId = :credentialId WHERE id = :id")
    suspend fun setSigningCredential(id: Long, credentialId: Long?)

    @Query("UPDATE repos SET lastSyncAt = :at, lastError = :error, pendingSync = :pending WHERE id = :id")
    suspend fun setSyncResult(id: Long, at: Long, error: String?, pending: Boolean)

    @Query("SELECT * FROM repos")
    suspend fun all(): List<RepoEntity>
}

@Dao
interface SyncLogDao {
    @Query("SELECT * FROM sync_log WHERE repoId = :repoId ORDER BY startedAt DESC LIMIT :limit")
    fun observe(repoId: Long, limit: Int = 200): Flow<List<SyncLogEntity>>

    @Insert
    suspend fun insert(entry: SyncLogEntity): Long

    /** Keeps only the newest [keep] entries per repo. */
    @Query(
        "DELETE FROM sync_log WHERE repoId = :repoId AND id NOT IN " +
            "(SELECT id FROM sync_log WHERE repoId = :repoId ORDER BY startedAt DESC LIMIT :keep)",
    )
    suspend fun prune(repoId: Long, keep: Int = 200)
}

@Dao
interface AppTriggerDao {
    @Query("SELECT * FROM app_triggers")
    fun observeAll(): Flow<List<AppTriggerEntity>>

    @Query("SELECT * FROM app_triggers")
    suspend fun all(): List<AppTriggerEntity>

    @Query("SELECT * FROM app_triggers WHERE repoId = :repoId")
    fun observeForRepo(repoId: Long): Flow<List<AppTriggerEntity>>

    @Upsert
    suspend fun upsert(trigger: AppTriggerEntity)

    @Query("DELETE FROM app_triggers WHERE packageName = :packageName AND repoId = :repoId")
    suspend fun delete(packageName: String, repoId: Long)
}

@Dao
interface ContainerDao {
    @Query("SELECT * FROM containers ORDER BY position, name COLLATE NOCASE")
    fun observeAll(): Flow<List<ContainerEntity>>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM containers")
    suspend fun nextPosition(): Int

    @Insert
    suspend fun insert(container: ContainerEntity): Long

    @Query("UPDATE containers SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM containers WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface CredentialDao {
    @Query("SELECT * FROM credentials ORDER BY label COLLATE NOCASE")
    fun observeAll(): Flow<List<CredentialEntity>>

    @Query("SELECT * FROM credentials WHERE id = :id")
    suspend fun get(id: Long): CredentialEntity?

    @Query("SELECT * FROM credentials")
    suspend fun all(): List<CredentialEntity>

    @Insert
    suspend fun insert(credential: CredentialEntity): Long

    @Query("UPDATE credentials SET label = :label WHERE id = :id")
    suspend fun rename(id: Long, label: String)

    @Query("DELETE FROM credentials WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface GitHubCacheDao {
    @Query("SELECT * FROM github_cache WHERE `key` = :key")
    suspend fun get(key: String): GitHubCacheEntity?

    @Upsert
    suspend fun put(entry: GitHubCacheEntity)

    @Query("DELETE FROM github_cache")
    suspend fun clear()
}

@Dao
interface PendingCommentDao {
    @Query("SELECT * FROM github_pending_comments ORDER BY createdAt")
    suspend fun all(): List<PendingCommentEntity>

    @Query("SELECT * FROM github_pending_comments WHERE owner = :owner AND repo = :repo AND number = :number ORDER BY createdAt")
    fun observe(owner: String, repo: String, number: Int): Flow<List<PendingCommentEntity>>

    @Insert
    suspend fun insert(comment: PendingCommentEntity): Long

    @Query("DELETE FROM github_pending_comments WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE github_pending_comments SET attempts = attempts + 1, lastError = :error WHERE id = :id")
    suspend fun failed(id: Long, error: String?)
}

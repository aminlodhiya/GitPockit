package dev.gitpockit.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
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

    @Query("UPDATE repos SET lastSyncAt = :at, lastError = :error WHERE id = :id")
    suspend fun setSyncResult(id: Long, at: Long, error: String?)
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

    @Insert
    suspend fun insert(credential: CredentialEntity): Long

    @Query("UPDATE credentials SET label = :label WHERE id = :id")
    suspend fun rename(id: Long, label: String)

    @Query("DELETE FROM credentials WHERE id = :id")
    suspend fun delete(id: Long)
}

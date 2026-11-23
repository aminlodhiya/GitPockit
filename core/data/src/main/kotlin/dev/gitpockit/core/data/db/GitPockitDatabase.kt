package dev.gitpockit.core.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        RepoEntity::class,
        ContainerEntity::class,
        CredentialEntity::class,
        SyncLogEntity::class,
        AppTriggerEntity::class,
        GitHubCacheEntity::class,
        PendingCommentEntity::class,
    ],
    version = 7,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7)],
)
abstract class GitPockitDatabase : RoomDatabase() {
    abstract fun repoDao(): RepoDao
    abstract fun containerDao(): ContainerDao
    abstract fun credentialDao(): CredentialDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun appTriggerDao(): AppTriggerDao
    abstract fun gitHubCacheDao(): GitHubCacheDao
    abstract fun pendingCommentDao(): PendingCommentDao

    companion object {
        fun create(context: Context): GitPockitDatabase =
            Room.databaseBuilder(context, GitPockitDatabase::class.java, "gitpockit.db").build()
    }
}

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
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class GitPockitDatabase : RoomDatabase() {
    abstract fun repoDao(): RepoDao
    abstract fun containerDao(): ContainerDao
    abstract fun credentialDao(): CredentialDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun appTriggerDao(): AppTriggerDao

    companion object {
        fun create(context: Context): GitPockitDatabase =
            Room.databaseBuilder(context, GitPockitDatabase::class.java, "gitpockit.db").build()
    }
}

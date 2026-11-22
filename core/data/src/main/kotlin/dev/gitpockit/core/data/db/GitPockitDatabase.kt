package dev.gitpockit.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [RepoEntity::class, ContainerEntity::class, CredentialEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class GitPockitDatabase : RoomDatabase() {
    abstract fun repoDao(): RepoDao
    abstract fun containerDao(): ContainerDao
    abstract fun credentialDao(): CredentialDao

    companion object {
        fun create(context: Context): GitPockitDatabase =
            Room.databaseBuilder(context, GitPockitDatabase::class.java, "gitpockit.db").build()
    }
}

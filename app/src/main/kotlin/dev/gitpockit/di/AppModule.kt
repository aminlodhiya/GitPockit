package dev.gitpockit.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.gitpockit.background.AndroidNetworkStatus
import dev.gitpockit.core.data.db.GitHubCacheDao
import dev.gitpockit.core.data.db.GitPockitDatabase
import dev.gitpockit.core.data.db.PendingCommentDao
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import dev.gitpockit.core.data.repository.CredentialRepository
import dev.gitpockit.core.data.repository.RepoRepository
import dev.gitpockit.core.data.storage.StorageLocations
import dev.gitpockit.core.git.GitEngine
import dev.gitpockit.core.git.jgit.JGitEngine
import dev.gitpockit.core.security.KeystoreSecretCipher
import dev.gitpockit.core.security.SecretCipher
import dev.gitpockit.data.SettingsRepository
import dev.gitpockit.sync.NetworkStatus
import dev.gitpockit.sync.RepoOps
import dev.gitpockit.sync.SyncEngine
import kotlinx.coroutines.flow.first
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideGitEngine(@ApplicationContext context: Context): GitEngine =
        JGitEngine(appDataDir = context.filesDir.resolve("git-home"))

    @Provides @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): GitPockitDatabase = GitPockitDatabase.create(context)

    @Provides @Singleton
    fun provideSecretCipher(): SecretCipher = KeystoreSecretCipher()

    @Provides @Singleton
    fun provideRepoRepository(db: GitPockitDatabase, engine: GitEngine): RepoRepository = RepoRepository(db, engine)

    @Provides @Singleton
    fun provideCredentialRepository(db: GitPockitDatabase, cipher: SecretCipher): CredentialRepository =
        CredentialRepository(db.credentialDao(), cipher)

    @Provides @Singleton
    fun provideRepoOps(
        engine: GitEngine,
        repos: RepoRepository,
        credentials: CredentialRepository,
        settings: SettingsRepository,
    ): RepoOps = RepoOps(engine, repos, credentials) { settings.author.first() }

    @Provides @Singleton
    fun provideNetworkStatus(@ApplicationContext context: Context): NetworkStatus = AndroidNetworkStatus(context)

    @Provides @Singleton
    fun provideSyncEngine(
        ops: RepoOps,
        repos: RepoRepository,
        network: NetworkStatus,
        settings: SettingsRepository,
    ): SyncEngine = SyncEngine(ops, repos, network, deviceName = { settings.deviceName.first() })

    /** One HTTP engine (OkHttp) shared by all GitHub API clients. */
    @Provides @Singleton
    fun provideHttpEngine(): HttpClientEngine = OkHttp.create()

    @Provides
    fun provideGitHubCacheDao(db: GitPockitDatabase): GitHubCacheDao = db.gitHubCacheDao()

    @Provides
    fun providePendingCommentDao(db: GitPockitDatabase): PendingCommentDao = db.pendingCommentDao()

    @Provides @Singleton
    fun provideStorageLocations(@ApplicationContext context: Context): StorageLocations = StorageLocations(context)
}

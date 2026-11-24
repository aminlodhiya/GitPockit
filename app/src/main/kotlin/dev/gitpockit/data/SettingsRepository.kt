package dev.gitpockit.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.gitpockit.core.designsystem.theme.ThemeMode
import dev.gitpockit.core.designsystem.theme.ThemeSettings
import dev.gitpockit.core.git.GitAuthor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val store: DataStore<Preferences>,
) {
    val theme: Flow<ThemeSettings> = store.data.map { p ->
        ThemeSettings(
            mode = p[THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.Dark,
            amoled = p[AMOLED] ?: false,
            dynamicColor = p[DYNAMIC] ?: false,
        )
    }

    val author: Flow<GitAuthor> = store.data.map { p ->
        GitAuthor(name = p[AUTHOR_NAME] ?: "GitPockit", email = p[AUTHOR_EMAIL] ?: "gitpockit@localhost")
    }

    /** Shown in auto-commit messages and conflict copies, e.g. "Sync from Pixel 8". */
    val deviceName: Flow<String> = store.data.map { p -> p[DEVICE_NAME]?.takeIf { it.isNotBlank() } ?: defaultDeviceName() }

    /** Post a (quiet) notification after successful background syncs. Problems are always notified. */
    val notifyOnSuccess: Flow<Boolean> = store.data.map { it[NOTIFY_SUCCESS] ?: false }

    /** Let automation apps (Tasker, Automate…) trigger syncs via broadcast intents. */
    val automationEnabled: Flow<Boolean> = store.data.map { it[AUTOMATION] ?: false }

    /** Ask for fingerprint / phone PIN when opening the app. */
    val appLockEnabled: Flow<Boolean> = store.data.map { it[APP_LOCK] ?: false }

    /** Minutes in the background before locking again (0 = immediately). */
    val appLockTimeoutMinutes: Flow<Int> = store.data.map { it[APP_LOCK_TIMEOUT] ?: 0 }

    suspend fun setAppLockEnabled(enabled: Boolean) = store.edit { it[APP_LOCK] = enabled }

    suspend fun setAppLockTimeoutMinutes(minutes: Int) = store.edit { it[APP_LOCK_TIMEOUT] = minutes }

    suspend fun setDeviceName(name: String) = store.edit { it[DEVICE_NAME] = name.trim() }

    suspend fun setNotifyOnSuccess(enabled: Boolean) = store.edit { it[NOTIFY_SUCCESS] = enabled }

    suspend fun setAutomationEnabled(enabled: Boolean) = store.edit { it[AUTOMATION] = enabled }

    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[THEME_MODE] = mode.name }

    suspend fun setAmoled(enabled: Boolean) = store.edit { it[AMOLED] = enabled }

    suspend fun setDynamicColor(enabled: Boolean) = store.edit { it[DYNAMIC] = enabled }

    suspend fun setAuthor(author: GitAuthor) = store.edit {
        it[AUTHOR_NAME] = author.name
        it[AUTHOR_EMAIL] = author.email
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AMOLED = booleanPreferencesKey("amoled")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val AUTHOR_NAME = stringPreferencesKey("author_name")
        val AUTHOR_EMAIL = stringPreferencesKey("author_email")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val NOTIFY_SUCCESS = booleanPreferencesKey("notify_success")
        val AUTOMATION = booleanPreferencesKey("automation_enabled")
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val APP_LOCK_TIMEOUT = androidx.datastore.preferences.core.intPreferencesKey("app_lock_timeout_minutes")

        fun defaultDeviceName(): String =
            android.os.Build.MODEL.takeIf { it.isNotBlank() } ?: "Android"
    }
}

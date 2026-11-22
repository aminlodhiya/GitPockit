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
    }
}

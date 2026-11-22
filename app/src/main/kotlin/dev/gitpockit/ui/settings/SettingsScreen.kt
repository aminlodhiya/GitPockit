package dev.gitpockit.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gitpockit.BuildConfig
import dev.gitpockit.R
import dev.gitpockit.ui.common.rememberStorageAccess
import dev.gitpockit.core.designsystem.component.GpCard
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.designsystem.theme.ThemeMode
import dev.gitpockit.core.designsystem.theme.ThemeSettings
import dev.gitpockit.core.git.GitAuthor
import dev.gitpockit.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val repo: SettingsRepository) : ViewModel() {
    val theme: StateFlow<ThemeSettings?> = repo.theme.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val author: StateFlow<GitAuthor?> = repo.author.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setMode(mode: ThemeMode) = viewModelScope.launch { repo.setThemeMode(mode) }
    fun setAmoled(v: Boolean) = viewModelScope.launch { repo.setAmoled(v) }
    fun setDynamic(v: Boolean) = viewModelScope.launch { repo.setDynamicColor(v) }
    fun setAuthor(a: GitAuthor) = viewModelScope.launch { repo.setAuthor(a) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onCredentials: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val author by viewModel.author.collectAsStateWithLifecycle()
    val ext = GitPockitTheme.extendedColors

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        val t = theme ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GpCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleMedium)
                val modes = listOf(
                    ThemeMode.System to R.string.theme_system,
                    ThemeMode.Dark to R.string.theme_dark,
                    ThemeMode.Light to R.string.theme_light,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    modes.forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = t.mode == mode,
                            onClick = { viewModel.setMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        ) { Text(stringResource(label)) }
                    }
                }
                ToggleRow(R.string.settings_amoled, R.string.settings_amoled_desc, t.amoled, viewModel::setAmoled)
                ToggleRow(R.string.settings_dynamic, R.string.settings_dynamic_desc, t.dynamicColor, viewModel::setDynamic)
            }

            author?.let { a -> AuthorCard(a, viewModel::setAuthor) }

            val storage = rememberStorageAccess()
            GpCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_security), style = MaterialTheme.typography.titleMedium)
                LinkRow(Icons.Default.Key, R.string.credentials_title, stringResource(R.string.credentials_desc), onCredentials)
                LinkRow(
                    Icons.Default.FolderOpen,
                    R.string.settings_storage,
                    stringResource(if (storage.granted) R.string.settings_storage_on else R.string.settings_storage_off),
                    onClick = { if (!storage.granted) storage.request() },
                )
            }

            GpCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.settings_license), style = MaterialTheme.typography.bodySmall, color = ext.textSecondary)
                Text(
                    stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = ext.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun LinkRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = GitPockitTheme.extendedColors.textSecondary)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = GitPockitTheme.extendedColors.textSecondary)
    }
}

@Composable
private fun ToggleRow(title: Int, desc: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = GitPockitTheme.extendedColors.textSecondary)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AuthorCard(initial: GitAuthor, onSave: (GitAuthor) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var email by remember { mutableStateOf(initial.email) }
    // Persist edits shortly after typing stops.
    LaunchedEffect(name, email) {
        kotlinx.coroutines.delay(500)
        if (name.isNotBlank() && email.isNotBlank() && (name != initial.name || email != initial.email)) {
            onSave(GitAuthor(name.trim(), email.trim()))
        }
    }
    GpCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.settings_author), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.settings_author_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.settings_author_email)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

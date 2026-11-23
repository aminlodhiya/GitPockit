package dev.gitpockit.ui.repo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gitpockit.R
import dev.gitpockit.core.data.model.AppTrigger
import dev.gitpockit.core.data.model.Container
import dev.gitpockit.core.data.model.SyncSettings
import dev.gitpockit.core.data.model.Credential
import dev.gitpockit.core.data.model.Repo
import dev.gitpockit.core.data.repository.CredentialRepository
import dev.gitpockit.core.data.repository.RepoRepository
import dev.gitpockit.core.designsystem.component.GpCard
import dev.gitpockit.core.designsystem.theme.CodeTextStyle
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.git.GitAuthor
import dev.gitpockit.data.SettingsRepository
import dev.gitpockit.ui.common.ConfirmDialog
import dev.gitpockit.ui.common.CredentialPicker
import dev.gitpockit.ui.common.SigningKeyPicker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RepoSettingsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repos: RepoRepository,
    credentialRepo: CredentialRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val repoId: Long = checkNotNull(savedState["repoId"])
    val repo: StateFlow<Repo?> = repos.observe(repoId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val credentials: StateFlow<List<Credential>> = credentialRepo.credentials.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val containers: StateFlow<List<Container>> = repos.containers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val globalAuthor: StateFlow<GitAuthor?> = settings.author.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _removed = MutableStateFlow(false)
    val removed: StateFlow<Boolean> = _removed

    fun rename(name: String) = viewModelScope.launch { if (name.isNotBlank()) repos.rename(repoId, name) }
    fun setCredential(id: Long?) = viewModelScope.launch { repos.setCredential(repoId, id) }
    fun setSigning(id: Long?) = viewModelScope.launch { repos.setSigningCredential(repoId, id) }
    fun setContainer(id: Long?) = viewModelScope.launch { repos.moveToContainer(repoId, id) }
    fun setAuthor(author: GitAuthor?) = viewModelScope.launch { repos.setAuthor(repoId, author) }
    val appTriggers: StateFlow<List<AppTrigger>> = repos.observeAppTriggers(repoId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun setSync(settings: SyncSettings) = viewModelScope.launch { repos.updateSyncSettings(repoId, settings) }
    fun setAppTrigger(trigger: AppTrigger) = viewModelScope.launch { repos.setAppTrigger(trigger) }
    fun removeAppTrigger(pkg: String) = viewModelScope.launch { repos.removeAppTrigger(pkg, repoId) }

    fun remove(deleteFiles: Boolean) = viewModelScope.launch {
        repos.remove(repoId, deleteFiles)
        _removed.value = true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoSettingsScreen(
    onBack: () -> Unit,
    onRemoved: () -> Unit,
    onAddCredential: () -> Unit,
    onOpenLog: () -> Unit,
    newCredentialId: Long?,
    viewModel: RepoSettingsViewModel = hiltViewModel(),
) {
    val triggers by viewModel.appTriggers.collectAsStateWithLifecycle()
    val repo by viewModel.repo.collectAsStateWithLifecycle()
    val credentials by viewModel.credentials.collectAsStateWithLifecycle()
    val containers by viewModel.containers.collectAsStateWithLifecycle()
    val global by viewModel.globalAuthor.collectAsStateWithLifecycle()
    val removed by viewModel.removed.collectAsStateWithLifecycle()
    LaunchedEffect(removed) { if (removed) onRemoved() }
    LaunchedEffect(newCredentialId) { newCredentialId?.let(viewModel::setCredential) }
    var confirmDelete by remember { mutableStateOf(false) }
    val ext = GitPockitTheme.extendedColors

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.repo_settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        val r = repo ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GpCard(Modifier.fillMaxWidth()) {
                var name by remember(r.id) { mutableStateOf(r.name) }
                LaunchedEffect(name) {
                    delay(500)
                    if (name != r.name) viewModel.rename(name)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.repo_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.repo_location), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                Text(r.directory.path, style = CodeTextStyle, color = ext.textSecondary)
            }

            SyncSettingsCard(r.sync, viewModel::setSync, onOpenLog)
            AppTriggersCard(triggers, r.id, viewModel::setAppTrigger, viewModel::removeAppTrigger)

            GpCard(Modifier.fillMaxWidth()) {
                CredentialPicker(credentials, r.credentialId, viewModel::setCredential, onAddCredential, Modifier.fillMaxWidth())
                ContainerPicker(containers, r.containerId, viewModel::setContainer, Modifier.fillMaxWidth().padding(top = 12.dp))
            }

            GpCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.signing_section), style = MaterialTheme.typography.titleMedium)
                SigningKeyPicker(credentials, r.signingCredentialId, viewModel::setSigning, onAddCredential, Modifier.fillMaxWidth().padding(top = 8.dp))
                Text(stringResource(R.string.signing_help), style = MaterialTheme.typography.bodySmall, color = ext.textSecondary, modifier = Modifier.padding(top = 8.dp))
            }

            GpCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.repo_author), style = MaterialTheme.typography.titleMedium)
                val useGlobal = r.authorName == null
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        stringResource(R.string.repo_author_global, global?.let { "${it.name} <${it.email}>" }.orEmpty()),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = useGlobal,
                        onCheckedChange = { on -> viewModel.setAuthor(if (on) null else global ?: GitAuthor("", "")) },
                    )
                }
                if (!useGlobal) {
                    var name by remember(r.id) { mutableStateOf(r.authorName.orEmpty()) }
                    var email by remember(r.id) { mutableStateOf(r.authorEmail.orEmpty()) }
                    LaunchedEffect(name, email) {
                        delay(500)
                        if (name != r.authorName || email != r.authorEmail) viewModel.setAuthor(GitAuthor(name.trim(), email.trim()))
                    }
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.settings_author_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.settings_author_email)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }

            GpCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.repo_danger), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { viewModel.remove(deleteFiles = false) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.repo_remove))
                }
                Text(stringResource(R.string.repo_remove_desc), style = MaterialTheme.typography.bodySmall, color = ext.textSecondary)
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.repo_delete_files), color = MaterialTheme.colorScheme.error)
                }
                Text(stringResource(R.string.repo_delete_files_desc), style = MaterialTheme.typography.bodySmall, color = ext.textSecondary)
            }
        }
        if (confirmDelete) {
            ConfirmDialog(
                title = stringResource(R.string.repo_delete_confirm_title, r.name),
                message = stringResource(R.string.repo_delete_confirm_message, r.directory.path),
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                onConfirm = { viewModel.remove(deleteFiles = true) },
                onDismiss = { confirmDelete = false },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContainerPicker(containers: List<Container>, selected: Long?, onSelect: (Long?) -> Unit, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = containers.firstOrNull { it.id == selected }?.name ?: stringResource(R.string.no_container),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.repo_container)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.no_container)) }, onClick = { onSelect(null); expanded = false })
            containers.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { onSelect(c.id); expanded = false }) }
        }
    }
}

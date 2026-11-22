package dev.gitpockit.ui.clone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gitpockit.R
import dev.gitpockit.core.data.model.Credential
import dev.gitpockit.core.data.model.CredentialType
import dev.gitpockit.core.data.repository.CredentialRepository
import dev.gitpockit.core.data.repository.RepoRepository
import dev.gitpockit.core.data.storage.StorageLocations
import dev.gitpockit.core.designsystem.component.GpCard
import dev.gitpockit.core.designsystem.component.GradientButton
import dev.gitpockit.core.designsystem.theme.CodeTextStyle
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.git.CloneOptions
import dev.gitpockit.core.git.GitEngine
import dev.gitpockit.ui.common.CredentialPicker
import dev.gitpockit.ui.common.StoragePermissionCard
import dev.gitpockit.ui.common.rememberFolderPicker
import dev.gitpockit.ui.common.rememberStorageAccess
import dev.gitpockit.ui.common.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class CloneUiState(
    val url: String = "",
    val name: String = "",
    val nameEdited: Boolean = false,
    val branch: String = "",
    val credentialId: Long? = null,
    val shallow: Boolean = false,
    val parentDir: File? = null,
    val running: Boolean = false,
    val progressTask: String = "",
    val progress: Float? = null,
    val error: String? = null,
    val clonedRepoId: Long? = null,
) {
    val target: File? get() = parentDir?.let { p -> name.trim().takeIf { it.isNotEmpty() }?.let { File(p, it) } }
    val isSshUrl: Boolean get() = url.trim().let { it.startsWith("git@") || it.startsWith("ssh://") || Regex("^[\\w.-]+@[\\w.-]+:").containsMatchIn(it) }
}

@HiltViewModel
class CloneViewModel @Inject constructor(
    private val engine: GitEngine,
    private val repos: RepoRepository,
    private val credentialRepo: CredentialRepository,
    private val storage: StorageLocations,
) : ViewModel() {
    private val _state = MutableStateFlow(CloneUiState(parentDir = storage.defaultRoot()))
    val state: StateFlow<CloneUiState> = _state
    val credentials: StateFlow<List<Credential>> =
        credentialRepo.credentials.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private var job: Job? = null
    private var parentPicked = false

    fun update(transform: (CloneUiState) -> CloneUiState) = _state.update { old ->
        val new = transform(old).copy(error = null)
        if (!new.nameEdited && new.url != old.url) new.copy(name = suggestName(new.url)) else new
    }

    fun pickParent(dir: File) {
        parentPicked = true
        _state.update { it.copy(parentDir = dir, error = null) }
    }

    /** Default location changes once all-files access is granted, unless the user chose one. */
    fun onResume() {
        if (!parentPicked) _state.update { it.copy(parentDir = storage.defaultRoot()) }
    }

    fun selectCredential(id: Long?) = _state.update { it.copy(credentialId = id, error = null) }

    fun clone(sshNeedsKey: String, folderExists: String) {
        val s = _state.value
        val target = s.target ?: return
        if (s.running || s.url.isBlank()) return
        val cred = credentials.value.firstOrNull { it.id == s.credentialId }
        if (s.isSshUrl && cred?.type != CredentialType.SSH) {
            _state.update { it.copy(error = sshNeedsKey) }
            return
        }
        if (target.exists() && target.list()?.isNotEmpty() == true) {
            _state.update { it.copy(error = folderExists) }
            return
        }
        _state.update { it.copy(running = true, error = null, progress = null, progressTask = "") }
        job = viewModelScope.launch {
            runCatching {
                target.parentFile?.mkdirs()
                engine.clone(
                    CloneOptions(
                        url = s.url.trim(),
                        directory = target,
                        credentials = credentialRepo.resolve(s.credentialId),
                        branch = s.branch.trim().ifBlank { null },
                        depth = if (s.shallow) 1 else 0,
                    ),
                ) { task, done, total ->
                    _state.update { it.copy(progressTask = task, progress = if (total > 0) done.toFloat() / total else null) }
                }.close()
                repos.register(target, name = s.name, credentialId = s.credentialId).id
            }.onSuccess { id ->
                _state.update { it.copy(running = false, clonedRepoId = id) }
            }.onFailure { e ->
                _state.update { it.copy(running = false, error = e.userMessage()) }
            }
        }
    }

    override fun onCleared() {
        job?.cancel()
    }

    private fun suggestName(url: String): String =
        url.trim().trimEnd('/').substringAfterLast('/').substringAfterLast(':').removeSuffix(".git")
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloneScreen(
    onBack: () -> Unit,
    onCloned: (Long) -> Unit,
    onAddCredential: () -> Unit,
    newCredentialId: Long?,
    viewModel: CloneViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val credentials by viewModel.credentials.collectAsStateWithLifecycle()
    val storageAccess = rememberStorageAccess()
    val context = LocalContext.current
    val pickFolder = rememberFolderPicker { dir ->
        if (dir != null) viewModel.pickParent(dir) else viewModel.update { it.copy(error = context.getString(R.string.storage_unsupported_folder)) }
    }
    LaunchedEffect(s.clonedRepoId) { s.clonedRepoId?.let(onCloned) }
    LaunchedEffect(newCredentialId) { newCredentialId?.let(viewModel::selectCredential) }
    LifecycleResumeEffect(storageAccess.granted) {
        viewModel.onResume()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.clone_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = s.url,
                onValueChange = { v -> viewModel.update { it.copy(url = v) } },
                label = { Text(stringResource(R.string.clone_url)) },
                placeholder = { Text(stringResource(R.string.clone_url_hint), style = CodeTextStyle) },
                singleLine = true,
                textStyle = CodeTextStyle,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            CredentialPicker(
                credentials = credentials,
                selectedId = s.credentialId,
                onSelect = viewModel::selectCredential,
                onAddNew = onAddCredential,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = s.name,
                    onValueChange = { v -> viewModel.update { it.copy(name = v, nameEdited = true) } },
                    label = { Text(stringResource(R.string.clone_name)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = s.branch,
                    onValueChange = { v -> viewModel.update { it.copy(branch = v) } },
                    label = { Text(stringResource(R.string.clone_branch)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            StoragePermissionCard(storageAccess)
            GpCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.clone_location), style = MaterialTheme.typography.titleSmall)
                        Text(
                            s.target?.path?.let { stringResource(R.string.clone_target, it) } ?: s.parentDir?.path.orEmpty(),
                            style = CodeTextStyle,
                            color = GitPockitTheme.extendedColors.textSecondary,
                        )
                    }
                    TextButton(onClick = pickFolder, enabled = storageAccess.granted) { Text(stringResource(R.string.action_change)) }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = s.shallow, onCheckedChange = { v -> viewModel.update { it.copy(shallow = v) } })
                Text(stringResource(R.string.clone_shallow), style = MaterialTheme.typography.bodyMedium)
            }

            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            if (s.running) {
                val p = s.progress
                if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                if (s.progressTask.isNotBlank()) {
                    Text(
                        stringResource(R.string.clone_progress, s.progressTask, ((p ?: 0f) * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = GitPockitTheme.extendedColors.textSecondary,
                    )
                }
            }

            val sshNeedsKey = stringResource(R.string.clone_ssh_needs_key)
            val folderExists = stringResource(R.string.clone_target_exists)
            GradientButton(
                text = stringResource(R.string.action_clone_start),
                onClick = { viewModel.clone(sshNeedsKey, folderExists) },
                enabled = !s.running && s.url.isNotBlank() && s.target != null,
                icon = Icons.Default.CloudDownload,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
            )
        }
    }
}

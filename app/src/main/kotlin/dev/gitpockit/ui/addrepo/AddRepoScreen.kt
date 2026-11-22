package dev.gitpockit.ui.addrepo

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gitpockit.R
import dev.gitpockit.core.data.model.Credential
import dev.gitpockit.core.data.repository.CredentialRepository
import dev.gitpockit.core.data.repository.RepoRepository
import dev.gitpockit.core.data.storage.FolderState
import dev.gitpockit.core.data.storage.StorageLocations
import dev.gitpockit.core.designsystem.component.GpCard
import dev.gitpockit.core.designsystem.component.GradientButton
import dev.gitpockit.core.designsystem.theme.CodeTextStyle
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.git.GitEngine
import dev.gitpockit.ui.common.CredentialPicker
import dev.gitpockit.ui.common.StoragePermissionCard
import dev.gitpockit.ui.common.rememberFolderPicker
import dev.gitpockit.ui.common.rememberStorageAccess
import dev.gitpockit.ui.common.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class AddRepoUiState(
    /** true = create a new folder; false = use an existing folder. */
    val createNew: Boolean,
    val folder: File? = null,
    val folderState: FolderState? = null,
    val parentDir: File? = null,
    val name: String = "",
    val branch: String = "main",
    val remoteUrl: String = "",
    val credentialId: Long? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val addedRepoId: Long? = null,
) {
    val target: File? get() = if (createNew) parentDir?.let { p -> name.trim().takeIf { it.isNotEmpty() }?.let { File(p, it) } } else folder
    val needsInit: Boolean get() = createNew || folderState != FolderState.REPOSITORY
    val canSubmit: Boolean get() = !busy && target != null && name.isNotBlank() && (createNew || folderState != null)
}

@HiltViewModel
class AddRepoViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val engine: GitEngine,
    private val repos: RepoRepository,
    credentialRepo: CredentialRepository,
    private val storage: StorageLocations,
) : ViewModel() {
    private val createNew: Boolean = savedState["create"] ?: false
    private val _state = MutableStateFlow(AddRepoUiState(createNew = createNew, parentDir = storage.defaultRoot()))
    val state: StateFlow<AddRepoUiState> = _state
    val credentials: StateFlow<List<Credential>> = credentialRepo.credentials.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private var parentPicked = false

    fun update(transform: (AddRepoUiState) -> AddRepoUiState) = _state.update { transform(it).copy(error = null) }

    fun onResume() {
        if (!parentPicked) _state.update { it.copy(parentDir = storage.defaultRoot()) }
    }

    fun onFolderPicked(dir: File?, unsupported: String) {
        if (dir == null) {
            _state.update { it.copy(error = unsupported) }
            return
        }
        if (createNew) {
            parentPicked = true
            _state.update { it.copy(parentDir = dir, error = null) }
        } else {
            _state.update { it.copy(folder = dir, folderState = storage.inspect(dir), name = dir.name, error = null) }
        }
    }

    fun submit() {
        val s = _state.value
        val dir = s.target ?: return
        if (!s.canSubmit) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching {
                if (s.needsInit) {
                    withContext(Dispatchers.IO) {
                        if (s.createNew && dir.exists() && dir.list()?.isNotEmpty() == true) {
                            error("Folder ${dir.path} already exists and isn't empty")
                        }
                        dir.mkdirs()
                    }
                    val branch = s.branch.trim().ifBlank { "main" }
                    engine.init(dir, branch).use { git ->
                        s.remoteUrl.trim().takeIf { it.isNotEmpty() }?.let { url ->
                            git.addRemote("origin", url)
                            git.setUpstream(branch, "origin")
                        }
                    }
                }
                repos.register(dir, name = s.name, credentialId = s.credentialId).id
            }.onSuccess { id -> _state.update { it.copy(busy = false, addedRepoId = id) } }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.userMessage()) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRepoScreen(
    onBack: () -> Unit,
    onAdded: (Long) -> Unit,
    onAddCredential: () -> Unit,
    newCredentialId: Long?,
    viewModel: AddRepoViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val credentials by viewModel.credentials.collectAsStateWithLifecycle()
    val storageAccess = rememberStorageAccess()
    val unsupportedFolder = stringResource(R.string.storage_unsupported_folder)
    val ext = GitPockitTheme.extendedColors
    val pickFolder = rememberFolderPicker { viewModel.onFolderPicked(it, unsupportedFolder) }
    LaunchedEffect(s.addedRepoId) { s.addedRepoId?.let(onAdded) }
    LaunchedEffect(newCredentialId) { newCredentialId?.let { id -> viewModel.update { it.copy(credentialId = id) } } }
    LifecycleResumeEffect(storageAccess.granted) {
        viewModel.onResume()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (s.createNew) R.string.create_title else R.string.existing_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StoragePermissionCard(storageAccess)

            if (s.createNew) {
                GpCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.create_parent), style = MaterialTheme.typography.titleSmall)
                            Text(s.parentDir?.path.orEmpty(), style = CodeTextStyle, color = ext.textSecondary)
                        }
                        TextButton(onClick = pickFolder, enabled = storageAccess.granted) { Text(stringResource(R.string.action_change)) }
                    }
                }
                OutlinedTextField(
                    value = s.name,
                    onValueChange = { v -> viewModel.update { it.copy(name = v) } },
                    label = { Text(stringResource(R.string.clone_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedButton(onClick = pickFolder, enabled = storageAccess.granted, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.FolderOpen, null)
                    Text(stringResource(R.string.pick_folder), modifier = Modifier.padding(start = 8.dp))
                }
                s.folder?.let { folder ->
                    GpCard(Modifier.fillMaxWidth()) {
                        Text(folder.path, style = CodeTextStyle)
                        Text(
                            stringResource(
                                when (s.folderState) {
                                    FolderState.REPOSITORY -> R.string.folder_is_repo
                                    FolderState.EMPTY, FolderState.MISSING -> R.string.folder_empty
                                    else -> R.string.folder_not_repo
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ext.textSecondary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    OutlinedTextField(
                        value = s.name,
                        onValueChange = { v -> viewModel.update { it.copy(name = v) } },
                        label = { Text(stringResource(R.string.repo_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (s.needsInit && (s.createNew || s.folder != null)) {
                OutlinedTextField(
                    value = s.branch,
                    onValueChange = { v -> viewModel.update { it.copy(branch = v) } },
                    label = { Text(stringResource(R.string.initial_branch)) },
                    singleLine = true,
                    textStyle = CodeTextStyle,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = s.remoteUrl,
                    onValueChange = { v -> viewModel.update { it.copy(remoteUrl = v) } },
                    label = { Text(stringResource(R.string.remote_url_optional)) },
                    supportingText = { Text(stringResource(R.string.remote_url_optional_desc)) },
                    singleLine = true,
                    textStyle = CodeTextStyle,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (s.createNew || s.folder != null) {
                CredentialPicker(
                    credentials = credentials,
                    selectedId = s.credentialId,
                    onSelect = { id -> viewModel.update { it.copy(credentialId = id) } },
                    onAddNew = onAddCredential,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            GradientButton(
                text = stringResource(if (s.createNew) R.string.action_create else R.string.action_add_repo_confirm),
                onClick = viewModel::submit,
                enabled = s.canSubmit,
                icon = Icons.Default.Check,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
            )
        }
    }
}

package dev.gitpockit.ui.repo

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gitpockit.R
import dev.gitpockit.core.data.repository.RepoRepository
import dev.gitpockit.core.designsystem.component.GradientButton
import dev.gitpockit.core.designsystem.component.StatusPill
import dev.gitpockit.core.designsystem.theme.CodeTextStyle
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.git.LfsFile
import dev.gitpockit.core.git.SubmoduleInfo
import dev.gitpockit.core.git.SubmoduleState
import dev.gitpockit.sync.RepoOps
import dev.gitpockit.ui.common.InputDialog
import dev.gitpockit.ui.files.humanSize
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModuleScaffold(
    title: String,
    onBack: () -> Unit,
    messages: kotlinx.coroutines.flow.Flow<String>,
    busy: Boolean,
    fab: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { messages.collect { snackbar.showSnackbar(it) } }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = { fab?.invoke() },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            content()
        }
    }
}

// ---------------------------------------------------------------- submodules

data class SubmodulesState(val loaded: Boolean = false, val items: List<SubmoduleInfo> = emptyList(), val busy: Boolean = false)

@HiltViewModel
class SubmodulesViewModel @Inject constructor(
    savedState: SavedStateHandle,
    ops: RepoOps,
    private val repos: RepoRepository,
    @ApplicationContext private val context: Context,
) : RepoScopedViewModel(savedState, ops) {
    private val _state = MutableStateFlow(SubmodulesState())
    val state: StateFlow<SubmodulesState> = _state
    private val _open = Channel<Long>(Channel.CONFLATED)
    val openRepo = _open.receiveAsFlow()

    init { refresh() }

    override fun refresh() {
        viewModelScope.launch {
            runCatching { ops.read(repoId) { _, git -> git.submodules() } }
                .onSuccess { list -> _state.update { it.copy(loaded = true, items = list) } }
                .onFailure { _state.update { s -> s.copy(loaded = true) }; it.report() }
        }
    }

    fun update(path: String?) = busy {
        val updated = ops.write(repoId) { repo, git -> git.updateSubmodules(listOfNotNull(path), ops.credentialsFor(repo)) }
        message(context.getString(R.string.submodules_updated, updated.size))
    }

    fun add(path: String, url: String) = busy {
        ops.write(repoId) { repo, git -> git.addSubmodule(path, url, ops.credentialsFor(repo)) }
        message(context.getString(R.string.submodule_added))
    }

    /** Registers the submodule as its own repository (same credential as the parent) and opens it. */
    fun open(path: String) = busy {
        val parent = ops.repo(repoId)
        val dir = File(parent.directory, path)
        val repo = repos.findByDirectory(dir)
            ?: repos.register(dir, name = "${parent.name}/$path", credentialId = parent.credentialId, containerId = parent.containerId)
        _open.trySend(repo.id)
    }

    private fun busy(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { block() }.onFailure { it.report() }
            _state.update { it.copy(busy = false) }
        }
    }
}

@Composable
fun SubmodulesScreen(onBack: () -> Unit, onOpenRepo: (Long) -> Unit, viewModel: SubmodulesViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.openRepo.collect(onOpenRepo) }
    var adding by remember { mutableStateOf(false) }
    val ext = GitPockitTheme.extendedColors

    ModuleScaffold(
        stringResource(R.string.menu_submodules), onBack, viewModel.messages, s.busy,
        fab = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.submodule_add)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) {
        if (!s.loaded) return@ModuleScaffold
        if (s.items.isEmpty()) {
            Text(stringResource(R.string.submodules_empty), color = ext.textSecondary, modifier = Modifier.padding(24.dp))
            return@ModuleScaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                GradientButton(
                    text = stringResource(R.string.submodules_update_all),
                    onClick = { viewModel.update(null) },
                    enabled = !s.busy,
                    icon = Icons.Default.CloudDownload,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
            items(s.items, key = { it.path }) { sm ->
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sm.path, style = CodeTextStyle)
                        sm.url?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ext.textSecondary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) }
                        val (label, color) = when (sm.state) {
                            SubmoduleState.UNINITIALIZED -> R.string.submodule_uninitialized to ext.textSecondary
                            SubmoduleState.MISSING -> R.string.submodule_missing to ext.removed
                            SubmoduleState.UP_TO_DATE -> R.string.submodule_up_to_date to ext.added
                            SubmoduleState.DIFFERENT_COMMIT -> R.string.submodule_different to ext.modified
                        }
                        StatusPill(stringResource(label), color, Modifier.padding(top = 4.dp))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.submodule_update)) }, onClick = { menu = false; viewModel.update(sm.path) })
                            if (sm.state != SubmoduleState.UNINITIALIZED && sm.state != SubmoduleState.MISSING) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.submodule_open)) }, onClick = { menu = false; viewModel.open(sm.path) })
                            }
                        }
                    }
                }
                HorizontalDivider(color = ext.hairline)
            }
        }
    }
    if (adding) {
        InputDialog(
            title = stringResource(R.string.submodule_add),
            labels = listOf(stringResource(R.string.submodule_path), stringResource(R.string.submodule_url)),
            confirmText = stringResource(R.string.action_add),
            onConfirm = { viewModel.add(it[0], it[1]) },
            onDismiss = { adding = false },
        )
    }
}

// ---------------------------------------------------------------- LFS

data class LfsState(val loaded: Boolean = false, val uses: Boolean = false, val files: List<LfsFile> = emptyList(), val busy: Boolean = false)

@HiltViewModel
class LfsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    ops: RepoOps,
    @ApplicationContext private val context: Context,
) : RepoScopedViewModel(savedState, ops) {
    private val _state = MutableStateFlow(LfsState())
    val state: StateFlow<LfsState> = _state

    init { refresh() }

    override fun refresh() {
        viewModelScope.launch {
            runCatching { ops.read(repoId) { _, git -> git.usesLfs() to (if (git.usesLfs()) git.lfsFiles() else emptyList()) } }
                .onSuccess { (uses, files) -> _state.update { it.copy(loaded = true, uses = uses, files = files.sortedBy { f -> f.path }) } }
                .onFailure { _state.update { s -> s.copy(loaded = true) }; it.report() }
        }
    }

    fun downloadAll() = busy {
        val n = ops.write(repoId) { repo, git -> git.lfsDownload(repo.remoteName, ops.credentialsFor(repo)) }
        message(context.getString(R.string.lfs_download_done, n))
    }

    fun track(pattern: String) = busy {
        ops.write(repoId) { _, git -> git.lfsTrack(pattern) }
        message(context.getString(R.string.lfs_tracked, pattern))
    }

    private fun busy(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { block() }.onFailure { it.report() }
            _state.update { it.copy(busy = false) }
        }
    }
}

@Composable
fun LfsScreen(onBack: () -> Unit, viewModel: LfsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var tracking by remember { mutableStateOf(false) }
    val ext = GitPockitTheme.extendedColors

    ModuleScaffold(
        stringResource(R.string.menu_lfs), onBack, viewModel.messages, s.busy,
        fab = {
            ExtendedFloatingActionButton(
                onClick = { tracking = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.lfs_track)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) {
        if (!s.loaded) return@ModuleScaffold
        if (!s.uses) {
            Text(stringResource(R.string.lfs_not_used), color = ext.textSecondary, modifier = Modifier.padding(24.dp))
            return@ModuleScaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.lfs_summary, s.files.size, s.files.count { it.downloaded }),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = viewModel::downloadAll, enabled = !s.busy && s.files.any { !it.downloaded }) {
                        Text(stringResource(R.string.lfs_download_all))
                    }
                }
            }
            items(s.files, key = { it.path }) { f ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.path, style = CodeTextStyle, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                        Text(humanSize(f.size), style = MaterialTheme.typography.bodySmall, color = ext.textSecondary)
                    }
                    StatusPill(
                        stringResource(if (f.downloaded) R.string.lfs_downloaded else R.string.lfs_not_downloaded),
                        if (f.downloaded) ext.added else ext.textSecondary,
                    )
                }
                HorizontalDivider(color = ext.hairline)
            }
        }
    }
    if (tracking) {
        InputDialog(
            title = stringResource(R.string.lfs_track),
            labels = listOf(stringResource(R.string.lfs_pattern)),
            confirmText = stringResource(R.string.action_add),
            onConfirm = { viewModel.track(it[0]) },
            onDismiss = { tracking = false },
        )
    }
}

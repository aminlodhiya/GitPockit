package dev.gitpockit.ui.repo

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
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
import dev.gitpockit.background.SyncCoordinator
import dev.gitpockit.core.data.model.SyncTrigger
import dev.gitpockit.core.designsystem.component.GpCard
import dev.gitpockit.core.designsystem.component.GradientButton
import dev.gitpockit.core.designsystem.theme.CodeTextStyle
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.git.ConflictVersions
import dev.gitpockit.core.git.GitOperation
import dev.gitpockit.core.git.OperationResult
import dev.gitpockit.core.git.incoming
import dev.gitpockit.core.git.yours
import dev.gitpockit.sync.RepoOps
import dev.gitpockit.sync.SyncEngine
import dev.gitpockit.ui.common.ConfirmDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** One side of a conflict, ready to display. */
data class ConflictSide(val text: String?, val deleted: Boolean, val binary: Boolean)

data class ConflictItem(val path: String, val mine: ConflictSide, val remote: ConflictSide)

data class ConflictsState(
    val loaded: Boolean = false,
    val operation: GitOperation = GitOperation.NONE,
    val items: List<ConflictItem> = emptyList(),
    val busy: Boolean = false,
    val done: Boolean = false,
)

private const val PREVIEW_CHARS = 4_000

private fun ByteArray?.toSide(): ConflictSide = when {
    this == null -> ConflictSide(null, deleted = true, binary = false)
    any { it == 0.toByte() } -> ConflictSide(null, deleted = false, binary = true)
    else -> ConflictSide(decodeToString().take(PREVIEW_CHARS), deleted = false, binary = false)
}

@HiltViewModel
class ConflictsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    ops: RepoOps,
    private val engine: SyncEngine,
    private val coordinator: SyncCoordinator,
    @ApplicationContext private val context: Context,
) : RepoScopedViewModel(savedState, ops) {
    private val _state = MutableStateFlow(ConflictsState())
    val state: StateFlow<ConflictsState> = _state

    init { refresh() }

    override fun refresh() {
        viewModelScope.launch {
            runCatching {
                ops.read(repoId) { _, git ->
                    val op = git.operation()
                    val items = git.status().conflicting.sorted().map { path ->
                        val v: ConflictVersions = git.conflictVersions(path)
                        ConflictItem(path, v.yours(op).toSide(), v.incoming(op).toSide())
                    }
                    op to items
                }
            }.onSuccess { (op, items) -> _state.update { it.copy(loaded = true, operation = op, items = items) } }
                .onFailure { _state.update { s -> s.copy(loaded = true) }; it.report() }
        }
    }

    fun keepMine(path: String) = resolve { git -> git.resolveConflict(path, git.conflictVersions(path).yours(git.operation())) }

    fun keepRemote(path: String) = resolve { git -> git.resolveConflict(path, git.conflictVersions(path).incoming(git.operation())) }

    /** Incoming keeps the original name; yours is saved next to it as a named copy (same as automatic "keep both"). */
    fun keepBoth(path: String) = resolve { git ->
        val op = git.operation()
        val v = git.conflictVersions(path)
        git.resolveConflict(path, v.incoming(op))
        v.yours(op)?.let { git.resolveConflict(engine.conflictCopyName(path), it) }
    }

    /**
     * Finishes the paused operation. A rebase may stop again at the next commit; then the new
     * conflicts are shown. Merges and rebases usually come from a sync, so a sync follows to push.
     */
    fun finish() {
        val op = _state.value.operation
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching {
                val result = ops.write(repoId) { repo, git -> git.continueOperation("", ops.authorFor(repo)) }
                if (result !is OperationResult.Conflicts && (op == GitOperation.MERGE || op == GitOperation.REBASE)) {
                    coordinator.run(repoId, SyncTrigger.MANUAL)
                }
                result
            }.onSuccess { result ->
                if (result is OperationResult.Conflicts) {
                    message(context.getString(R.string.rebase_next_conflicts))
                    refresh()
                } else {
                    message(context.getString(R.string.operation_done))
                    _state.update { it.copy(done = true) }
                }
            }.onFailure { it.report() }
            _state.update { it.copy(busy = false) }
        }
    }

    fun abort() {
        viewModelScope.launch {
            runCatching { ops.write(repoId) { _, git -> git.abortOperation() } }
                .onSuccess { _state.update { it.copy(done = true) } }
                .onFailure { it.report() }
        }
    }

    private fun resolve(block: suspend (dev.gitpockit.core.git.GitRepository) -> Unit) {
        viewModelScope.launch {
            runCatching { ops.write(repoId) { _, git -> block(git) } }.onFailure { it.report() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictsScreen(onBack: () -> Unit, onEdit: (String) -> Unit, viewModel: ConflictsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(s.done) { if (s.done) onBack() }
    var confirmAbort by remember { mutableStateOf(false) }
    val ext = GitPockitTheme.extendedColors

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conflicts_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    if (s.operation != GitOperation.NONE) {
                        TextButton(onClick = { confirmAbort = true }) { Text(stringResource(R.string.abort_operation), color = MaterialTheme.colorScheme.error) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (s.operation != GitOperation.NONE) {
                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp)) {
                    GradientButton(
                        text = stringResource(
                            when (s.operation) {
                                GitOperation.REBASE -> R.string.continue_rebase
                                GitOperation.CHERRY_PICK -> R.string.finish_cherry_pick
                                GitOperation.REVERT -> R.string.finish_revert
                                else -> R.string.finish_merge_sync
                            },
                        ),
                        onClick = viewModel::finish,
                        enabled = s.items.isEmpty() && !s.busy,
                        icon = Icons.Default.Check,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    ) { padding ->
        if (!s.loaded) return@Scaffold
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    stringResource(if (s.items.isEmpty()) R.string.conflicts_none else R.string.conflicts_explain),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ext.textSecondary,
                )
            }
            items(s.items, key = { it.path }) { item ->
                GpCard(Modifier.fillMaxWidth()) {
                    Text(item.path, style = CodeTextStyle, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                    Side(stringResource(R.string.conflict_yours), item.mine, ext.modified)
                    Side(stringResource(R.string.conflict_incoming), item.remote, MaterialTheme.colorScheme.secondary)
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.keepMine(item.path) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.keep_yours), maxLines = 1) }
                        OutlinedButton(onClick = { viewModel.keepRemote(item.path) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.keep_incoming), maxLines = 1) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.keepBoth(item.path) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.keep_both), maxLines = 1) }
                        val editable = !item.mine.binary && !item.remote.binary
                        OutlinedButton(onClick = { onEdit(item.path) }, enabled = editable, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.edit_manually), maxLines = 1) }
                    }
                }
            }
        }
    }
    if (confirmAbort) {
        ConfirmDialog(
            title = stringResource(R.string.abort_operation_title, s.operation.label()),
            message = stringResource(R.string.abort_operation_message),
            confirmText = stringResource(R.string.abort_operation),
            destructive = true,
            onConfirm = viewModel::abort,
            onDismiss = { confirmAbort = false },
        )
    }
}

@Composable
fun GitOperation.label(): String = stringResource(operationTitle(this)).lowercase()

@Composable
private fun Side(label: String, side: ConflictSide, color: androidx.compose.ui.graphics.Color) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    val body = when {
        side.deleted -> stringResource(R.string.conflict_deleted)
        side.binary -> stringResource(R.string.conflict_binary)
        else -> side.text.orEmpty()
    }
    Box(
        Modifier.fillMaxWidth().heightIn(max = 180.dp)
            .background(color.copy(alpha = 0.08f), MaterialTheme.shapes.small)
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        Text(body, style = CodeTextStyle)
    }
}

// ---------------------------------------------------------------- manual edit

data class EditState(val loaded: Boolean = false, val text: String = "", val saved: Boolean = false)

@HiltViewModel
class EditConflictViewModel @Inject constructor(savedState: SavedStateHandle, ops: RepoOps) : RepoScopedViewModel(savedState, ops) {
    val path: String = checkNotNull(savedState["path"])
    private val _state = MutableStateFlow(EditState())
    val state: StateFlow<EditState> = _state

    init { load() }

    // Don't reload over the user's edits when other screens change the repo.
    override fun refresh() = Unit

    private fun load() {
        viewModelScope.launch {
            runCatching { ops.read(repoId) { repo, _ -> File(repo.directory, path).takeIf { it.isFile }?.readText().orEmpty() } }
                .onSuccess { text -> _state.update { it.copy(loaded = true, text = text) } }
                .onFailure { it.report() }
        }
    }

    fun setText(t: String) = _state.update { it.copy(text = t) }

    fun save() {
        viewModelScope.launch {
            runCatching { ops.write(repoId) { _, git -> git.resolveConflict(path, _state.value.text.toByteArray()) } }
                .onSuccess { _state.update { it.copy(saved = true) } }
                .onFailure { it.report() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditConflictScreen(onBack: () -> Unit, viewModel: EditConflictViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(s.saved) { if (s.saved) onBack() }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_file_title, viewModel.path.substringAfterLast('/')), maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = { TextButton(onClick = viewModel::save, enabled = s.loaded) { Text(stringResource(R.string.save_resolve)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Text(
                stringResource(R.string.edit_markers_help),
                style = MaterialTheme.typography.bodySmall,
                color = GitPockitTheme.extendedColors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (s.loaded) {
                BasicTextField(
                    value = s.text,
                    onValueChange = viewModel::setText,
                    textStyle = CodeTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                )
            }
        }
    }
}


package dev.gitpockit.ui.repo

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import dev.gitpockit.core.designsystem.component.EmptyState
import dev.gitpockit.core.designsystem.component.GradientButton
import dev.gitpockit.core.designsystem.theme.CodeTextStyle
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.git.GitRepository
import dev.gitpockit.core.git.RepoStatus
import dev.gitpockit.sync.RepoOps
import dev.gitpockit.ui.common.ConfirmDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class FileStatus(val code: String) { ADDED("A"), MODIFIED("M"), DELETED("D"), UNTRACKED("U"), CONFLICT("!") }

data class FileEntry(val path: String, val status: FileStatus)

data class ChangesState(
    val loaded: Boolean = false,
    val conflicts: List<FileEntry> = emptyList(),
    val staged: List<FileEntry> = emptyList(),
    val unstaged: List<FileEntry> = emptyList(),
    val message: String = "",
    val amend: Boolean = false,
    val busy: Boolean = false,
) {
    val isClean: Boolean get() = conflicts.isEmpty() && staged.isEmpty() && unstaged.isEmpty()
}

@HiltViewModel
class ChangesViewModel @Inject constructor(
    savedState: SavedStateHandle,
    ops: RepoOps,
    @ApplicationContext private val context: Context,
) : RepoScopedViewModel(savedState, ops) {
    private val _state = MutableStateFlow(ChangesState())
    val state: StateFlow<ChangesState> = _state

    init { refresh() }

    override fun refresh() {
        viewModelScope.launch {
            runCatching { ops.read(repoId) { _, git -> git.status() } }
                .onSuccess { st -> _state.update { it.copy(loaded = true).withStatus(st) } }
                .onFailure { _state.update { s -> s.copy(loaded = true) }; it.report() }
        }
    }

    private fun ChangesState.withStatus(s: RepoStatus) = copy(
        conflicts = s.conflicting.sorted().map { FileEntry(it, FileStatus.CONFLICT) },
        staged = buildList {
            s.added.forEach { add(FileEntry(it, FileStatus.ADDED)) }
            s.changed.forEach { add(FileEntry(it, FileStatus.MODIFIED)) }
            s.removed.forEach { add(FileEntry(it, FileStatus.DELETED)) }
        }.filterNot { it.path in s.conflicting }.sortedBy { it.path },
        unstaged = buildList {
            s.modified.forEach { add(FileEntry(it, FileStatus.MODIFIED)) }
            s.missing.forEach { add(FileEntry(it, FileStatus.DELETED)) }
            s.untracked.forEach { add(FileEntry(it, FileStatus.UNTRACKED)) }
        }.filterNot { it.path in s.conflicting }.sortedBy { it.path },
    )

    fun setMessage(v: String) = _state.update { it.copy(message = v) }

    fun setAmend(v: Boolean) {
        _state.update { it.copy(amend = v) }
        if (v && _state.value.message.isBlank()) {
            viewModelScope.launch {
                runCatching { ops.read(repoId) { _, git -> git.log(maxCount = 1).firstOrNull()?.message?.trim() } }
                    .getOrNull()?.let { last -> _state.update { it.copy(message = last) } }
            }
        }
    }

    fun stage(paths: List<String>) = mutate { it.stage(paths) }
    fun unstage(paths: List<String>) = mutate { it.unstage(paths) }
    fun discard(path: String) = mutate { it.discardChanges(listOf(path)) }

    fun commit() {
        val s = _state.value
        if (s.busy) return
        val stageEverything = s.staged.isEmpty() && !s.amend
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching {
                ops.write(repoId) { repo, git ->
                    if (stageEverything) git.stageAll()
                    val msg = s.message.trim().ifBlank { context.getString(R.string.default_sync_message) }
                    git.commit(msg, ops.authorFor(repo), amend = s.amend)
                }
            }.onSuccess { c ->
                _state.update { it.copy(message = "", amend = false) }
                message(context.getString(R.string.committed, c.shortId))
            }.onFailure { it.report() }
            _state.update { it.copy(busy = false) }
        }
    }

    private fun mutate(block: suspend (GitRepository) -> Unit) {
        viewModelScope.launch {
            runCatching { ops.write(repoId) { _, git -> block(git) } }.onFailure { it.report() }
        }
    }
}

@Composable
fun ChangesTab(
    onOpenDiff: (path: String, staged: Boolean) -> Unit,
    onOpenConflicts: () -> Unit,
    showMessage: (String) -> Unit,
    viewModel: ChangesViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.messages.collect(showMessage) }
    var discard by remember { mutableStateOf<String?>(null) }
    val ext = GitPockitTheme.extendedColors

    if (!s.loaded) return
    if (s.isClean) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(Icons.Default.CheckCircle, stringResource(R.string.changes_clean_title), stringResource(R.string.changes_clean_message))
        }
        return
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
            if (s.conflicts.isNotEmpty()) {
                section(
                    title = R.string.section_conflicts,
                    entries = s.conflicts,
                    hint = R.string.conflict_hint,
                    action = R.string.resolve_conflicts to onOpenConflicts,
                    rowAction = { e -> RowAction(Icons.Default.Add, R.string.action_stage) { viewModel.stage(listOf(e.path)) } },
                    onClick = { onOpenDiff(it.path, false) },
                    onDiscard = { discard = it.path },
                )
            }
            if (s.staged.isNotEmpty()) {
                section(
                    title = R.string.section_staged,
                    entries = s.staged,
                    action = R.string.action_unstage_all to { viewModel.unstage(s.staged.map { it.path }) },
                    rowAction = { e -> RowAction(Icons.Default.Remove, R.string.action_unstage) { viewModel.unstage(listOf(e.path)) } },
                    onClick = { onOpenDiff(it.path, true) },
                    onDiscard = { discard = it.path },
                )
            }
            if (s.unstaged.isNotEmpty()) {
                section(
                    title = R.string.section_unstaged,
                    entries = s.unstaged,
                    action = R.string.action_stage_all to { viewModel.stage(s.unstaged.map { it.path }) },
                    rowAction = { e -> RowAction(Icons.Default.Add, R.string.action_stage) { viewModel.stage(listOf(e.path)) } },
                    onClick = { onOpenDiff(it.path, false) },
                    onDiscard = { discard = it.path },
                )
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                HorizontalDivider(color = ext.hairline, modifier = Modifier.padding(bottom = 12.dp))
                OutlinedTextField(
                    value = s.message,
                    onValueChange = viewModel::setMessage,
                    label = { Text(stringResource(R.string.commit_message)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 5,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = s.amend, onCheckedChange = viewModel::setAmend)
                    Text(stringResource(R.string.commit_amend), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    GradientButton(
                        text = stringResource(if (s.staged.isEmpty() && !s.amend) R.string.action_commit_all else R.string.action_commit),
                        onClick = viewModel::commit,
                        enabled = !s.busy && s.conflicts.isEmpty(),
                    )
                }
            }
        }
    }

    discard?.let { path ->
        ConfirmDialog(
            title = stringResource(R.string.discard_title, path.substringAfterLast('/')),
            message = stringResource(R.string.discard_message),
            confirmText = stringResource(R.string.action_discard),
            destructive = true,
            onConfirm = { viewModel.discard(path) },
            onDismiss = { discard = null },
        )
    }
}

private class RowAction(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: Int, val onClick: () -> Unit)

private fun LazyListScope.section(
    title: Int,
    entries: List<FileEntry>,
    hint: Int? = null,
    action: Pair<Int, () -> Unit>,
    rowAction: (FileEntry) -> RowAction,
    onClick: (FileEntry) -> Unit,
    onDiscard: (FileEntry) -> Unit,
) {
    item(key = "header-$title") {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${stringResource(title)} · ${entries.size}",
                style = MaterialTheme.typography.titleSmall,
                color = GitPockitTheme.extendedColors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = action.second) { Text(stringResource(action.first)) }
        }
        hint?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = GitPockitTheme.extendedColors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
    items(entries, key = { "$title-${it.path}" }) { entry ->
        val a = rowAction(entry)
        FileRow(entry, onClick = { onClick(entry) }, onDiscard = { onDiscard(entry) }, action = a)
    }
}

@Composable
private fun FileRow(entry: FileEntry, onClick: () -> Unit, onDiscard: () -> Unit, action: RowAction) {
    val ext = GitPockitTheme.extendedColors
    val color: Color = when (entry.status) {
        FileStatus.ADDED, FileStatus.UNTRACKED -> ext.added
        FileStatus.MODIFIED -> ext.modified
        FileStatus.DELETED, FileStatus.CONFLICT -> ext.removed
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(entry.status.code, style = CodeTextStyle, color = color, modifier = Modifier.width(22.dp))
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(entry.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            entry.path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let {
                Text(it, style = CodeTextStyle, color = ext.textSecondary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            }
        }
        IconButton(onClick = onDiscard) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.action_discard), tint = ext.textSecondary) }
        IconButton(onClick = action.onClick) { Icon(action.icon, stringResource(action.label), tint = MaterialTheme.colorScheme.primary) }
    }
}


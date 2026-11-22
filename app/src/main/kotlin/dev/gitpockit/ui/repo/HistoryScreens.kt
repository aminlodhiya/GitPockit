package dev.gitpockit.ui.repo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gitpockit.R
import dev.gitpockit.core.designsystem.theme.CodeTextStyle
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.git.BlameLine
import dev.gitpockit.core.git.ChangeType
import dev.gitpockit.core.git.Comparison
import dev.gitpockit.core.git.FileHistoryEntry
import dev.gitpockit.sync.RepoOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Loads one value under the repo lock; [error] is shown instead when loading fails. */
abstract class RepoLoadViewModel<T>(savedState: SavedStateHandle, ops: RepoOps) : RepoScopedViewModel(savedState, ops) {
    private val _value = MutableStateFlow<T?>(null)
    val value: StateFlow<T?> = _value
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    protected abstract suspend fun load(git: dev.gitpockit.core.git.GitRepository): T

    override fun refresh() {
        viewModelScope.launch {
            runCatching { ops.read(repoId) { _, git -> load(git) } }
                .onSuccess { _value.value = it; _error.value = null }
                .onFailure { _error.value = it.message }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleScaffold(title: String, subtitle: String?, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        subtitle?.let { Text(it, style = CodeTextStyle, color = GitPockitTheme.extendedColors.textSecondary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding -> Box(Modifier.fillMaxSize().padding(padding)) { content() } }
}

@Composable
private fun ErrorText(text: String) = Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))

// ---------------------------------------------------------------- file history

@HiltViewModel
class FileHistoryViewModel @Inject constructor(savedState: SavedStateHandle, ops: RepoOps) : RepoLoadViewModel<List<FileHistoryEntry>>(savedState, ops) {
    val path: String = checkNotNull(savedState["path"])
    init { refresh() }
    override suspend fun load(git: dev.gitpockit.core.git.GitRepository) = git.fileHistory(path)
}

@Composable
fun FileHistoryScreen(onBack: () -> Unit, onOpenDiff: (commit: String, path: String) -> Unit, viewModel: FileHistoryViewModel = hiltViewModel()) {
    val list by viewModel.value.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val ext = GitPockitTheme.extendedColors
    SimpleScaffold(stringResource(R.string.file_history), viewModel.path, onBack) {
        error?.let { return@SimpleScaffold ErrorText(it) }
        val entries = list ?: return@SimpleScaffold
        LazyColumn(Modifier.fillMaxSize()) {
            items(entries, key = { it.commit.id }) { e ->
                Column(Modifier.fillMaxWidth().clickable { onOpenDiff(e.commit.id, e.path) }.padding(horizontal = 16.dp)) {
                    Text(e.commit.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)) {
                        Text(e.commit.shortId, style = CodeTextStyle, color = MaterialTheme.colorScheme.primary)
                        Text("${e.commit.authorName} · ${relativeTime(e.commit.time)}", style = MaterialTheme.typography.bodySmall, color = ext.textSecondary)
                    }
                    if (e.path != viewModel.path) {
                        Text(stringResource(R.string.file_history_was, e.path), style = CodeTextStyle, color = ext.modified, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    HorizontalDivider(color = ext.hairline)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- blame

@HiltViewModel
class BlameViewModel @Inject constructor(savedState: SavedStateHandle, ops: RepoOps) : RepoLoadViewModel<List<BlameLine>>(savedState, ops) {
    val path: String = checkNotNull(savedState["path"])
    init { refresh() }
    override suspend fun load(git: dev.gitpockit.core.git.GitRepository) = git.blame(path)
}

@Composable
fun BlameScreen(onBack: () -> Unit, onOpenCommit: (String) -> Unit, viewModel: BlameViewModel = hiltViewModel()) {
    val lines by viewModel.value.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val ext = GitPockitTheme.extendedColors
    SimpleScaffold(stringResource(R.string.blame), viewModel.path, onBack) {
        error?.let { return@SimpleScaffold ErrorText(it) }
        val all = lines ?: return@SimpleScaffold
        val digits = all.size.toString().length
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.fillMaxSize().width((digits * 8 + 160 + (all.maxOfOrNull { it.text.length } ?: 0) * 8).dp)) {
                items(all, key = { it.line }) { l ->
                    // Show the commit only on the first line of each block from the same commit.
                    val first = l.line == 1 || all[l.line - 2].commitId != l.commitId
                    Row(
                        Modifier.fillMaxWidth()
                            .then(if (first) Modifier.padding(top = 2.dp) else Modifier)
                            .clickable(enabled = l.commitId != null) { l.commitId?.let(onOpenCommit) },
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(Modifier.width(150.dp).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 6.dp)) {
                            if (first) {
                                Text(
                                    "${l.commitId?.take(7).orEmpty()} ${l.authorName.orEmpty()}",
                                    style = CodeTextStyle.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Text(l.line.toString(), style = CodeTextStyle.copy(fontSize = 11.sp), color = ext.textSecondary, modifier = Modifier.width((digits * 8 + 10).dp).padding(start = 4.dp))
                        Text(l.text.replace("\t", "    "), style = CodeTextStyle, softWrap = false)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- compare

@HiltViewModel
class CompareViewModel @Inject constructor(savedState: SavedStateHandle, ops: RepoOps) : RepoLoadViewModel<Comparison>(savedState, ops) {
    val from: String = checkNotNull(savedState["from"])
    val to: String = checkNotNull(savedState["to"])
    init { refresh() }
    override suspend fun load(git: dev.gitpockit.core.git.GitRepository) = git.compare(from, to)
}

@Composable
fun CompareScreen(
    onBack: () -> Unit,
    onSwap: (from: String, to: String) -> Unit,
    onOpenCommit: (String) -> Unit,
    onOpenDiff: (from: String, to: String, path: String) -> Unit,
    viewModel: CompareViewModel = hiltViewModel(),
) {
    val cmp by viewModel.value.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val ext = GitPockitTheme.extendedColors
    SimpleScaffold(stringResource(R.string.compare_title), "${viewModel.from} → ${viewModel.to}", onBack) {
        error?.let { return@SimpleScaffold ErrorText(it) }
        val c = cmp ?: return@SimpleScaffold
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.compare_commits, c.commits.size, viewModel.to, viewModel.from),
                        style = MaterialTheme.typography.titleSmall,
                        color = ext.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onSwap(viewModel.to, viewModel.from) }) { Icon(Icons.Default.SwapHoriz, stringResource(R.string.compare_swap)) }
                }
            }
            items(c.commits, key = { "c-" + it.id }) { commit ->
                Row(Modifier.fillMaxWidth().clickable { onOpenCommit(commit.id) }.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(commit.shortId, style = CodeTextStyle, color = MaterialTheme.colorScheme.primary)
                    Text(commit.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            item {
                Text(
                    stringResource(R.string.commit_files, c.changes.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = ext.textSecondary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(c.changes, key = { "f-" + it.path + it.type }) { change ->
                val (code, color) = when (change.type) {
                    ChangeType.ADDED -> "A" to ext.added
                    ChangeType.MODIFIED -> "M" to ext.modified
                    ChangeType.DELETED -> "D" to ext.removed
                    ChangeType.RENAMED -> "R" to ext.modified
                    ChangeType.COPIED -> "C" to ext.added
                }
                Row(Modifier.fillMaxWidth().clickable { onOpenDiff(viewModel.from, viewModel.to, change.path) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(code, style = CodeTextStyle, color = color, modifier = Modifier.width(22.dp))
                    Text(change.path, style = CodeTextStyle, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                }
            }
        }
    }
}

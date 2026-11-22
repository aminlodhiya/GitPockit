package dev.gitpockit.ui.repo

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gitpockit.R
import dev.gitpockit.core.designsystem.component.GradientButton
import dev.gitpockit.core.designsystem.theme.CodeTextStyle
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.git.ChangeType
import dev.gitpockit.core.git.DiffHunk
import dev.gitpockit.core.git.DiffLine
import dev.gitpockit.core.git.DiffLineType
import dev.gitpockit.core.git.FileDiff
import dev.gitpockit.core.git.LineSelection
import dev.gitpockit.core.git.WorkingDiffMode
import dev.gitpockit.sync.RepoOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the diff compares. Only working-tree diffs allow staging or unstaging lines. */
sealed interface DiffSource {
    data class Commit(val id: String) : DiffSource
    data class Compare(val from: String, val to: String) : DiffSource
    data class Working(val staged: Boolean) : DiffSource
}

/** A changed line's identity for selection: removed lines by old number, added by new number. */
private fun DiffLine.key(): Pair<DiffLineType, Int>? = when (type) {
    DiffLineType.REMOVED -> type to oldLine!!
    DiffLineType.ADDED -> type to newLine!!
    DiffLineType.CONTEXT -> null
}

data class DiffState(
    val diff: FileDiff? = null,
    val error: String? = null,
    val selected: Set<Pair<DiffLineType, Int>> = emptySet(),
    val busy: Boolean = false,
)

@HiltViewModel
class DiffViewModel @Inject constructor(
    savedState: SavedStateHandle,
    ops: RepoOps,
    @ApplicationContext private val context: Context,
) : RepoScopedViewModel(savedState, ops) {
    val path: String = checkNotNull(savedState["path"])
    val source: DiffSource = run {
        val commit = savedState.get<String>("commit")?.ifBlank { null }
        val from = savedState.get<String>("from")?.ifBlank { null }
        val to = savedState.get<String>("to")?.ifBlank { null }
        when {
            commit != null -> DiffSource.Commit(commit)
            from != null && to != null -> DiffSource.Compare(from, to)
            else -> DiffSource.Working(savedState["staged"] ?: false)
        }
    }
    private val _state = MutableStateFlow(DiffState())
    val state: StateFlow<DiffState> = _state

    init { refresh() }

    override fun refresh() {
        viewModelScope.launch {
            runCatching {
                ops.read(repoId) { _, git ->
                    when (val s = source) {
                        is DiffSource.Commit -> git.commitFileDiff(s.id, path)
                        is DiffSource.Compare -> git.compareFileDiff(s.from, s.to, path)
                        is DiffSource.Working -> git.workingFileDiff(path, if (s.staged) WorkingDiffMode.STAGED else WorkingDiffMode.UNSTAGED)
                    }
                }
            }.onSuccess { d -> _state.update { DiffState(diff = d) } }
                .onFailure { e -> _state.update { DiffState(error = e.message) } }
        }
    }

    fun toggle(line: DiffLine) {
        val key = line.key() ?: return
        _state.update { it.copy(selected = if (key in it.selected) it.selected - key else it.selected + key) }
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    /** Stage (or unstage, in the staged view) the selected lines. */
    fun applySelection() = apply(_state.value.selected)

    /** Stage (or unstage) one whole hunk. */
    fun applyHunk(hunk: DiffHunk) = apply(hunk.lines.mapNotNull { it.key() }.toSet())

    private fun apply(keys: Set<Pair<DiffLineType, Int>>) {
        val working = source as? DiffSource.Working ?: return
        if (keys.isEmpty() || _state.value.busy) return
        val selection = LineSelection(
            oldLines = keys.filter { it.first == DiffLineType.REMOVED }.map { it.second }.toSet(),
            newLines = keys.filter { it.first == DiffLineType.ADDED }.map { it.second }.toSet(),
        )
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching {
                ops.write(repoId) { _, git -> if (working.staged) git.unstageLines(path, selection) else git.stageLines(path, selection) }
            }.onSuccess {
                message(context.getString(if (working.staged) R.string.lines_unstaged else R.string.lines_staged, keys.size))
            }.onFailure { it.report() }
            _state.update { it.copy(busy = false, selected = emptySet()) }
            // ops.write broadcasts a change, which reloads the diff via refresh().
        }
    }
}

private const val MAX_LINES = 5000

private sealed interface DiffRow {
    data class Header(val hunk: DiffHunk) : DiffRow
    data class Line(val line: DiffLine) : DiffRow
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffScreen(
    onBack: () -> Unit,
    onFileHistory: (String) -> Unit,
    onBlame: (String) -> Unit,
    viewModel: DiffViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var wrap by rememberSaveable { mutableStateOf(true) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    val ext = GitPockitTheme.extendedColors
    val working = viewModel.source as? DiffSource.Working
    val deleted = s.diff?.type == ChangeType.DELETED

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(viewModel.path.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        s.diff?.let { d ->
                            Row {
                                Text("+${d.additions}", style = CodeTextStyle, color = ext.added)
                                Text(" −${d.deletions}", style = CodeTextStyle, color = ext.removed)
                                d.oldPath?.let { Text("  ${stringResource(R.string.diff_renamed_from, it)}", style = CodeTextStyle, color = ext.textSecondary, maxLines = 1) }
                            }
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    if (!deleted) {
                        IconButton(onClick = { onFileHistory(viewModel.path) }) { Icon(Icons.Default.History, stringResource(R.string.file_history)) }
                        IconButton(onClick = { onBlame(viewModel.path) }) { Icon(Icons.Default.PersonSearch, stringResource(R.string.blame)) }
                    }
                    IconToggleButton(checked = wrap, onCheckedChange = { wrap = it }) { Icon(Icons.AutoMirrored.Filled.WrapText, null) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (working != null && s.selected.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = viewModel::clearSelection) { Text(stringResource(R.string.selection_clear)) }
                    Box(Modifier.weight(1f))
                    GradientButton(
                        text = stringResource(if (working.staged) R.string.unstage_lines else R.string.stage_lines, s.selected.size),
                        onClick = viewModel::applySelection,
                        enabled = !s.busy,
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val d = s.diff
            when {
                s.error != null -> Message(s.error!!)
                d == null -> Unit
                d.isBinary -> Message(stringResource(R.string.diff_binary))
                d.hunks.isEmpty() -> Message(stringResource(R.string.diff_no_changes))
                else -> Column {
                    if (working != null) {
                        Text(
                            stringResource(if (working.staged) R.string.diff_select_hint_staged else R.string.diff_select_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = ext.textSecondary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    DiffBody(
                        diff = d,
                        wrap = wrap,
                        selected = s.selected,
                        onToggle = if (working != null) viewModel::toggle else null,
                        hunkAction = working?.let { w -> stringResource(if (w.staged) R.string.unstage_hunk else R.string.stage_hunk) },
                        onHunk = viewModel::applyHunk,
                    )
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, color = GitPockitTheme.extendedColors.textSecondary, modifier = Modifier.padding(24.dp))
}

@Composable
private fun DiffBody(
    diff: FileDiff,
    wrap: Boolean,
    selected: Set<Pair<DiffLineType, Int>>,
    onToggle: ((DiffLine) -> Unit)?,
    hunkAction: String?,
    onHunk: (DiffHunk) -> Unit,
) {
    val rows = buildList {
        var count = 0
        for (h in diff.hunks) {
            if (count >= MAX_LINES) break
            add(DiffRow.Header(h))
            for (l in h.lines) {
                if (count++ >= MAX_LINES) break
                add(DiffRow.Line(l))
            }
        }
    }
    val total = diff.hunks.sumOf { it.lines.size }
    val digits = (diff.hunks.maxOfOrNull { h -> h.lines.maxOf { maxOf(it.oldLine ?: 0, it.newLine ?: 0) } } ?: 0).toString().length
    val numberWidth = (digits * 8 + 8).dp
    // Monospace at 13sp is ~7.8dp per character; size the unwrapped list to the longest line.
    val longest = rows.maxOfOrNull { (it as? DiffRow.Line)?.line?.text?.length ?: 0 } ?: 0
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val contentWidth = maxOf(maxWidth, numberWidth * 2 + 30.dp + (longest * 8).dp)
        val listModifier = if (wrap) Modifier.fillMaxSize() else Modifier.fillMaxHeight().width(contentWidth)
        val list = @Composable {
            LazyColumn(listModifier) {
                items(rows.size) { i ->
                    when (val row = rows[i]) {
                        is DiffRow.Header -> Row(
                            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                row.hunk.header,
                                style = CodeTextStyle.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 1,
                                modifier = Modifier.weight(1f, fill = false).padding(vertical = 6.dp),
                            )
                            if (hunkAction != null) {
                                Box(Modifier.weight(1f))
                                TextButton(onClick = { onHunk(row.hunk) }) { Text(hunkAction, style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                        is DiffRow.Line -> LineRow(row.line, numberWidth, wrap, row.line.key()?.let { it in selected } == true, onToggle)
                    }
                }
                if (total > MAX_LINES) {
                    item { Message(stringResource(R.string.diff_truncated, MAX_LINES)) }
                }
            }
        }
        if (wrap) list() else Box(Modifier.horizontalScroll(rememberScrollState())) { list() }
    }
}

@Composable
private fun LineRow(line: DiffLine, numberWidth: Dp, wrap: Boolean, isSelected: Boolean, onToggle: ((DiffLine) -> Unit)?) {
    val ext = GitPockitTheme.extendedColors
    val (bg, fg, sign) = when (line.type) {
        DiffLineType.ADDED -> Triple(ext.addedContainer, ext.added, "+")
        DiffLineType.REMOVED -> Triple(ext.removedContainer, ext.removed, "−")
        DiffLineType.CONTEXT -> Triple(Color.Transparent, ext.textSecondary, " ")
    }
    val selectable = onToggle != null && line.type != DiffLineType.CONTEXT
    var modifier = Modifier.fillMaxWidth().background(if (isSelected) fg.copy(alpha = 0.32f) else bg)
    if (isSelected) modifier = modifier.border(1.dp, fg)
    if (selectable) modifier = modifier.clickable { onToggle?.invoke(line) }
    val small = CodeTextStyle.copy(fontSize = 11.sp)
    Row(modifier, verticalAlignment = Alignment.Top) {
        Text(line.oldLine?.toString().orEmpty(), style = small, color = ext.textSecondary, modifier = Modifier.width(numberWidth).padding(start = 4.dp))
        Text(line.newLine?.toString().orEmpty(), style = small, color = ext.textSecondary, modifier = Modifier.width(numberWidth))
        Text(if (isSelected) "✓" else sign, style = CodeTextStyle, color = fg, modifier = Modifier.width(14.dp))
        Text(
            line.text.replace("\t", "    "),
            style = CodeTextStyle,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = wrap,
            modifier = Modifier.padding(end = 8.dp),
        )
    }
}

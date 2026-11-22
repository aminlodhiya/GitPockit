package dev.gitpockit.ui.repo

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import dev.gitpockit.core.git.DiffLine
import dev.gitpockit.core.git.DiffLineType
import dev.gitpockit.core.git.FileDiff
import dev.gitpockit.core.git.WorkingDiffMode
import dev.gitpockit.domain.RepoOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiffState(val diff: FileDiff? = null, val error: String? = null)

@HiltViewModel
class DiffViewModel @Inject constructor(savedState: SavedStateHandle, ops: RepoOps) : RepoScopedViewModel(savedState, ops) {
    val path: String = checkNotNull(savedState["path"])
    private val commit: String? = savedState.get<String>("commit")?.ifBlank { null }
    private val staged: Boolean = savedState["staged"] ?: false
    private val _state = MutableStateFlow(DiffState())
    val state: StateFlow<DiffState> = _state

    init { refresh() }

    override fun refresh() {
        viewModelScope.launch {
            runCatching {
                ops.read(repoId) { _, git ->
                    if (commit != null) git.commitFileDiff(commit, path)
                    else git.workingFileDiff(path, if (staged) WorkingDiffMode.STAGED else WorkingDiffMode.UNSTAGED)
                }
            }.onSuccess { _state.value = DiffState(it) }
                .onFailure { _state.value = DiffState(error = it.message) }
        }
    }
}

private const val MAX_LINES = 5000

private sealed interface DiffRow {
    data class Header(val text: String) : DiffRow
    data class Line(val line: DiffLine) : DiffRow
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffScreen(onBack: () -> Unit, viewModel: DiffViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var wrap by rememberSaveable { mutableStateOf(true) }
    val ext = GitPockitTheme.extendedColors
    Scaffold(
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
                    IconToggleButton(checked = wrap, onCheckedChange = { wrap = it }) { Icon(Icons.AutoMirrored.Filled.WrapText, null) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val d = s.diff
            when {
                s.error != null -> Message(s.error!!)
                d == null -> Unit
                d.isBinary -> Message(stringResource(R.string.diff_binary))
                d.hunks.isEmpty() -> Message(stringResource(R.string.diff_no_changes))
                else -> DiffBody(d, wrap)
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, color = GitPockitTheme.extendedColors.textSecondary, modifier = Modifier.padding(24.dp))
}

@Composable
private fun DiffBody(diff: FileDiff, wrap: Boolean) {
    val rows = buildList {
        var count = 0
        for (h in diff.hunks) {
            if (count >= MAX_LINES) break
            add(DiffRow.Header(h.header))
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
                        is DiffRow.Header -> Text(
                            row.text,
                            style = CodeTextStyle.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                        is DiffRow.Line -> LineRow(row.line, numberWidth, wrap)
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
private fun LineRow(line: DiffLine, numberWidth: androidx.compose.ui.unit.Dp, wrap: Boolean) {
    val ext = GitPockitTheme.extendedColors
    val (bg, fg, sign) = when (line.type) {
        DiffLineType.ADDED -> Triple(ext.addedContainer, ext.added, "+")
        DiffLineType.REMOVED -> Triple(ext.removedContainer, ext.removed, "−")
        DiffLineType.CONTEXT -> Triple(Color.Transparent, ext.textSecondary, " ")
    }
    val small = CodeTextStyle.copy(fontSize = 11.sp)
    Row(Modifier.fillMaxWidth().background(bg), verticalAlignment = Alignment.Top) {
        Text(line.oldLine?.toString().orEmpty(), style = small, color = ext.textSecondary, modifier = Modifier.width(numberWidth).padding(start = 4.dp))
        Text(line.newLine?.toString().orEmpty(), style = small, color = ext.textSecondary, modifier = Modifier.width(numberWidth))
        Text(sign, style = CodeTextStyle, color = fg, modifier = Modifier.width(14.dp))
        Text(
            line.text.replace("\t", "    "),
            style = CodeTextStyle,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = wrap,
            modifier = Modifier.padding(end = 8.dp),
        )
    }
}

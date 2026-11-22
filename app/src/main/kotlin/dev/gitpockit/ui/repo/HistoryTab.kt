package dev.gitpockit.ui.repo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gitpockit.R
import dev.gitpockit.core.designsystem.component.EmptyState
import dev.gitpockit.core.designsystem.theme.CodeTextStyle
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.git.CommitGraph
import dev.gitpockit.core.git.CommitInfo
import dev.gitpockit.core.git.RefKind
import dev.gitpockit.core.git.RefLabel
import dev.gitpockit.sync.RepoOps
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

data class HistoryState(
    val loaded: Boolean = false,
    val commits: List<CommitInfo> = emptyList(),
    val graph: List<CommitGraph.Row> = emptyList(),
    val labels: Map<String, List<RefLabel>> = emptyMap(),
    val endReached: Boolean = false,
    val allBranches: Boolean = false,
    /** Non-null while searching; results are shown without the graph. */
    val query: String? = null,
    val results: List<CommitInfo> = emptyList(),
)

@HiltViewModel
class HistoryViewModel @Inject constructor(savedState: SavedStateHandle, ops: RepoOps) : RepoScopedViewModel(savedState, ops) {
    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state
    private var loading: Job? = null
    private var searching: Job? = null

    init { refresh() }

    override fun refresh() {
        loading?.cancel()
        loading = viewModelScope.launch {
            val s = _state.value
            val size = s.commits.size.coerceAtLeast(PAGE)
            runCatching { ops.read(repoId) { _, git -> git.graphLog(size, 0, s.allBranches) to git.refLabels() } }
                .onSuccess { (list, labels) ->
                    _state.update { it.copy(loaded = true, commits = list, graph = CommitGraph.layout(list), labels = labels, endReached = list.size < size) }
                }
                .onFailure { _state.update { st -> st.copy(loaded = true) }; it.report() }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.endReached || s.query != null || loading?.isActive == true) return
        loading = viewModelScope.launch {
            runCatching { ops.read(repoId) { _, git -> git.graphLog(PAGE, s.commits.size, s.allBranches) } }
                .onSuccess { more ->
                    _state.update {
                        val all = it.commits + more
                        it.copy(commits = all, graph = CommitGraph.layout(all), endReached = more.size < PAGE)
                    }
                }
                .onFailure { it.report() }
        }
    }

    fun setAllBranches(all: Boolean) {
        _state.update { it.copy(allBranches = all, commits = emptyList()) }
        refresh()
    }

    fun search(query: String?) {
        _state.update { it.copy(query = query, results = if (query.isNullOrBlank()) emptyList() else it.results) }
        searching?.cancel()
        if (query.isNullOrBlank()) return
        searching = viewModelScope.launch {
            delay(300) // wait for typing to pause
            runCatching { ops.read(repoId) { _, git -> git.searchCommits(query) } }
                .onSuccess { r -> _state.update { if (it.query == query) it.copy(results = r) else it } }
                .onFailure { it.report() }
        }
    }

    private companion object { const val PAGE = 100 }
}

/** Distinct, readable lane colours for both themes. */
private val LaneColors = listOf(
    Color(0xFF34D399), Color(0xFF22D3EE), Color(0xFFA78BFA), Color(0xFFF59E0B),
    Color(0xFFF472B6), Color(0xFF60A5FA), Color(0xFFFB7185), Color(0xFFA3E635),
)

private val LaneWidth = 14.dp
private val NodeY = 20.dp

@Composable
fun HistoryTab(onOpenCommit: (String) -> Unit, showMessage: (String) -> Unit, viewModel: HistoryViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.messages.collect(showMessage) }
    if (!s.loaded) return

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s.query == null) {
                FilterChip(
                    selected = s.allBranches,
                    onClick = { viewModel.setAllBranches(!s.allBranches) },
                    label = { Text(stringResource(R.string.history_all_branches)) },
                )
                Box(Modifier.weight(1f))
                IconButton(onClick = { viewModel.search("") }) { Icon(Icons.Default.Search, stringResource(R.string.history_search)) }
            } else {
                OutlinedTextField(
                    value = s.query.orEmpty(),
                    onValueChange = viewModel::search,
                    placeholder = { Text(stringResource(R.string.history_search_hint)) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { IconButton(onClick = { viewModel.search(null) }) { Icon(Icons.Default.Close, stringResource(R.string.action_cancel)) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        when {
            s.query != null -> LazyColumn(Modifier.fillMaxSize()) {
                if (s.query!!.isNotBlank() && s.results.isEmpty()) {
                    item { Text(stringResource(R.string.history_no_results), color = GitPockitTheme.extendedColors.textSecondary, modifier = Modifier.padding(16.dp)) }
                }
                itemsIndexed(s.results, key = { _, c -> c.id }) { _, c -> CommitRow(c, null, s.labels[c.id].orEmpty()) { onOpenCommit(c.id) } }
            }
            s.commits.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Default.History, stringResource(R.string.history_empty), "")
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(s.commits, key = { _, c -> c.id }) { i, c ->
                    if (i >= s.commits.size - 20) LaunchedEffect(s.commits.size) { viewModel.loadMore() }
                    CommitRow(c, s.graph.getOrNull(i), s.labels[c.id].orEmpty()) { onOpenCommit(c.id) }
                }
            }
        }
    }
}

private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

fun relativeTime(time: Instant, now: Instant = Instant.now()): String {
    val d = Duration.between(time, now)
    return when {
        d.isNegative || d.toMinutes() < 1 -> "now"
        d.toHours() < 1 -> "${d.toMinutes()}m ago"
        d.toDays() < 1 -> "${d.toHours()}h ago"
        d.toDays() < 7 -> "${d.toDays()}d ago"
        else -> dateFormat.format(time.atZone(ZoneId.systemDefault()))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommitRow(c: CommitInfo, graph: CommitGraph.Row?, labels: List<RefLabel>, onClick: () -> Unit) {
    val ext = GitPockitTheme.extendedColors
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            if (graph != null) GraphCell(graph)
            Column(Modifier.weight(1f).padding(start = if (graph == null) 16.dp else 4.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)) {
                if (labels.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                        labels.sortedBy { it.kind.ordinal }.forEach { RefChip(it) }
                    }
                }
                Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
                    Text(c.shortId, style = CodeTextStyle, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "${c.authorName} · ${relativeTime(c.time)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ext.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
        HorizontalDivider(color = ext.hairline)
    }
}

/** Draws this row's slice of the commit graph: lines through the row and the commit's dot. */
@Composable
private fun GraphCell(row: CommitGraph.Row) {
    val bg = MaterialTheme.colorScheme.background
    Canvas(Modifier.width(LaneWidth * row.width + 8.dp).fillMaxHeight()) {
        val lane = LaneWidth.toPx()
        val nodeY = NodeY.toPx()
        val stroke = 2.dp.toPx()
        fun x(i: Int) = 8.dp.toPx() / 2 + lane * i + lane / 2
        for (seg in row.segments) {
            val color = LaneColors[seg.color % LaneColors.size]
            if (seg.top) {
                drawLine(color, Offset(x(seg.from), 0f), Offset(x(seg.to), nodeY), stroke, StrokeCap.Round)
            } else {
                drawLine(color, Offset(x(seg.from), nodeY), Offset(x(seg.to), size.height), stroke, StrokeCap.Round)
            }
        }
        val nodeColor = LaneColors[row.color % LaneColors.size]
        drawCircle(nodeColor, radius = 5.dp.toPx(), center = Offset(x(row.lane), nodeY))
        drawCircle(bg, radius = 2.5.dp.toPx(), center = Offset(x(row.lane), nodeY))
    }
}

@Composable
private fun RefChip(label: RefLabel) {
    val (bg, fg) = when (label.kind) {
        RefKind.HEAD -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        RefKind.LOCAL_BRANCH -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        RefKind.REMOTE_BRANCH -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f) to MaterialTheme.colorScheme.secondary
        RefKind.TAG -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f) to MaterialTheme.colorScheme.tertiary
    }
    Text(
        label.name,
        style = CodeTextStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
        color = fg,
        modifier = Modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

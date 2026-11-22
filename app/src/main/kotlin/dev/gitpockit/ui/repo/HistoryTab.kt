package dev.gitpockit.ui.repo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import dev.gitpockit.R
import dev.gitpockit.core.designsystem.component.EmptyState
import dev.gitpockit.core.designsystem.theme.CodeTextStyle
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.git.CommitInfo
import dev.gitpockit.sync.RepoOps
import kotlinx.coroutines.Job
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

data class HistoryState(val loaded: Boolean = false, val commits: List<CommitInfo> = emptyList(), val endReached: Boolean = false)

@HiltViewModel
class HistoryViewModel @Inject constructor(savedState: SavedStateHandle, ops: RepoOps) : RepoScopedViewModel(savedState, ops) {
    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state
    private var loading: Job? = null

    init { refresh() }

    override fun refresh() {
        loading?.cancel()
        loading = viewModelScope.launch {
            val size = _state.value.commits.size.coerceAtLeast(PAGE)
            runCatching { ops.read(repoId) { _, git -> git.log(maxCount = size) } }
                .onSuccess { list -> _state.update { HistoryState(true, list, list.size < size) } }
                .onFailure { _state.update { s -> s.copy(loaded = true) }; it.report() }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.endReached || loading?.isActive == true) return
        loading = viewModelScope.launch {
            runCatching { ops.read(repoId) { _, git -> git.log(maxCount = PAGE, skip = s.commits.size) } }
                .onSuccess { more -> _state.update { it.copy(commits = it.commits + more, endReached = more.size < PAGE) } }
                .onFailure { it.report() }
        }
    }

    private companion object { const val PAGE = 50 }
}

@Composable
fun HistoryTab(onOpenCommit: (String) -> Unit, showMessage: (String) -> Unit, viewModel: HistoryViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.messages.collect(showMessage) }
    if (!s.loaded) return
    if (s.commits.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(Icons.Default.History, stringResource(R.string.history_empty), "")
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(s.commits, key = { _, c -> c.id }) { i, c ->
            if (i >= s.commits.size - 10) LaunchedEffect(s.commits.size) { viewModel.loadMore() }
            CommitRow(c, onClick = { onOpenCommit(c.id) })
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

@Composable
private fun CommitRow(c: CommitInfo, onClick: () -> Unit) {
    val ext = GitPockitTheme.extendedColors
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp)) {
        Text(
            c.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)) {
            Text(c.shortId, style = CodeTextStyle, color = MaterialTheme.colorScheme.primary)
            Text(
                "${c.authorName} · ${relativeTime(c.time)}" + if (c.parentIds.size > 1) " · merge" else "",
                style = MaterialTheme.typography.bodySmall,
                color = ext.textSecondary,
                maxLines = 1,
            )
        }
        HorizontalDivider(color = ext.hairline)
    }
}

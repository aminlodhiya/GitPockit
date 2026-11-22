package dev.gitpockit.ui.repo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gitpockit.R
import dev.gitpockit.core.designsystem.component.GpCard
import dev.gitpockit.core.designsystem.theme.CodeTextStyle
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.git.ChangeType
import dev.gitpockit.core.git.CommitDetail
import dev.gitpockit.domain.RepoOps
import dev.gitpockit.ui.credentials.copyToClipboard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

@HiltViewModel
class CommitViewModel @Inject constructor(savedState: SavedStateHandle, ops: RepoOps) : RepoScopedViewModel(savedState, ops) {
    val commitId: String = checkNotNull(savedState["commit"])
    private val _detail = MutableStateFlow<CommitDetail?>(null)
    val detail: StateFlow<CommitDetail?> = _detail
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init { refresh() }

    override fun refresh() {
        viewModelScope.launch {
            runCatching { ops.read(repoId) { _, git -> git.commitDetail(commitId) } }
                .onSuccess { _detail.value = it }
                .onFailure { _error.value = it.message }
        }
    }
}

private val fullDate = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommitScreen(onBack: () -> Unit, onOpenDiff: (String) -> Unit, viewModel: CommitViewModel = hiltViewModel()) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ext = GitPockitTheme.extendedColors
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.commit_title) + " " + viewModel.commitId.take(7)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    IconButton(onClick = { context.copyToClipboard("commit", viewModel.commitId) }) {
                        Icon(Icons.Default.ContentCopy, stringResource(R.string.copy_hash))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(padding).padding(16.dp)) }
        val d = detail ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                GpCard(Modifier.fillMaxWidth().padding(16.dp)) {
                    SelectionContainer {
                        Text(d.info.message.trim(), style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(
                        "${d.info.authorName} <${d.info.authorEmail}>",
                        style = MaterialTheme.typography.bodySmall,
                        color = ext.textSecondary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(fullDate.format(d.info.time.atZone(ZoneId.systemDefault())), style = MaterialTheme.typography.bodySmall, color = ext.textSecondary)
                    SelectionContainer { Text(d.info.id, style = CodeTextStyle, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
                    if (d.info.parentIds.isNotEmpty()) {
                        Text(
                            stringResource(R.string.commit_parents) + ": " + d.info.parentIds.joinToString { it.take(7) },
                            style = CodeTextStyle,
                            color = ext.textSecondary,
                        )
                    }
                }
                Text(
                    stringResource(R.string.commit_files, d.changes.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = ext.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(d.changes, key = { it.path + it.type }) { change ->
                val (code, color) = when (change.type) {
                    ChangeType.ADDED -> "A" to ext.added
                    ChangeType.MODIFIED -> "M" to ext.modified
                    ChangeType.DELETED -> "D" to ext.removed
                    ChangeType.RENAMED -> "R" to ext.modified
                    ChangeType.COPIED -> "C" to ext.added
                }
                Column(Modifier.fillMaxWidth().clickable { onOpenDiff(change.path) }.padding(horizontal = 16.dp)) {
                    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
                        Text(code, style = CodeTextStyle, color = color, modifier = Modifier.width(22.dp))
                        Column {
                            Text(change.path, style = CodeTextStyle, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                            change.oldPath?.let { Text("← $it", style = CodeTextStyle, color = ext.textSecondary, maxLines = 1) }
                        }
                    }
                    HorizontalDivider(color = ext.hairline)
                }
            }
        }
    }
}

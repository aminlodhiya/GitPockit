package dev.gitpockit.ui.repo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.WarningAmber
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gitpockit.R
import dev.gitpockit.core.data.model.SyncLogEntry
import dev.gitpockit.core.data.model.SyncResult
import dev.gitpockit.core.data.model.SyncTrigger
import dev.gitpockit.core.data.repository.RepoRepository
import dev.gitpockit.core.designsystem.component.EmptyState
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class SyncLogViewModel @Inject constructor(savedState: SavedStateHandle, repos: RepoRepository) : ViewModel() {
    private val repoId: Long = checkNotNull(savedState["repoId"])
    val log: StateFlow<List<SyncLogEntry>?> = repos.observeLog(repoId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@Composable
fun SyncTrigger.label(): String = stringResource(
    when (this) {
        SyncTrigger.MANUAL -> R.string.trigger_manual
        SyncTrigger.SCHEDULE -> R.string.trigger_schedule
        SyncTrigger.FILE_CHANGE -> R.string.trigger_file_change
        SyncTrigger.APP_OPEN -> R.string.trigger_app_open
        SyncTrigger.APP_CLOSE -> R.string.trigger_app_close
        SyncTrigger.TILE -> R.string.trigger_tile
        SyncTrigger.WIDGET -> R.string.trigger_widget
        SyncTrigger.INTENT -> R.string.trigger_intent
        SyncTrigger.RETRY -> R.string.trigger_retry
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncLogScreen(onBack: () -> Unit, viewModel: SyncLogViewModel = hiltViewModel()) {
    val log by viewModel.log.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_log)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        val entries = log ?: return@Scaffold
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Default.History, stringResource(R.string.sync_log_empty), "")
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(entries, key = { it.id }) { LogRow(it) }
        }
    }
}

@Composable
private fun LogRow(e: SyncLogEntry) {
    val ext = GitPockitTheme.extendedColors
    val (icon, color, label) = when (e.result) {
        SyncResult.SYNCED -> Triple(Icons.Default.CheckCircle, ext.added, R.string.result_synced)
        SyncResult.UP_TO_DATE -> Triple(Icons.Default.Done, ext.textSecondary, R.string.result_up_to_date)
        SyncResult.OFFLINE -> Triple(Icons.Default.CloudOff, ext.modified, R.string.result_offline)
        SyncResult.CONFLICTS -> Triple(Icons.Default.WarningAmber, ext.removed, R.string.result_conflicts)
        SyncResult.FAILED -> Triple(Icons.Default.ErrorOutline, ext.removed, R.string.result_failed)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, color = color)
                Text(
                    "${e.trigger.label()} · ${relativeTime(Instant.ofEpochMilli(e.startedAt))} · ${"%.1f".format(e.durationMs / 1000.0)} s",
                    style = MaterialTheme.typography.bodySmall,
                    color = ext.textSecondary,
                )
                e.message?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        HorizontalDivider(color = ext.hairline)
    }
}

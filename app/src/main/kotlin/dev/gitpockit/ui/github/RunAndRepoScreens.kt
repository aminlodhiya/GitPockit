package dev.gitpockit.ui.github

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gitpockit.R
import dev.gitpockit.core.designsystem.component.GpCard
import dev.gitpockit.core.designsystem.component.StatusPill
import dev.gitpockit.core.designsystem.theme.CodeTextStyle
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.github.GhJob
import dev.gitpockit.core.github.GhRepo
import dev.gitpockit.core.github.GhWorkflowRun
import dev.gitpockit.github.GitHubService
import dev.gitpockit.github.Loaded
import dev.gitpockit.sync.RepoOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject

// ---------------------------------------------------------------- workflow run

data class RunState(
    val run: GhWorkflowRun? = null,
    val jobs: Loaded<List<GhJob>>? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class RunViewModel @Inject constructor(
    savedState: SavedStateHandle,
    ops: RepoOps,
    github: GitHubService,
    @ApplicationContext private val context: Context,
) : GitHubScopedViewModel(savedState, ops, github) {
    val runId: Long = checkNotNull(savedState["runId"])
    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state

    init { refresh() }

    override fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching {
                val slug = slug()
                val gh = client()
                val runs = github.cached("runs:${slug.fullName}:", ListSerializer(GhWorkflowRun.serializer())) { gh.workflowRuns(slug) }
                val jobs = github.cached("jobs:${slug.fullName}:$runId", ListSerializer(GhJob.serializer())) { gh.runJobs(slug, runId) }
                _state.update { it.copy(run = runs.value.firstOrNull { r -> r.id == runId } ?: it.run, jobs = jobs) }
            }.onFailure { e -> _state.update { it.copy(error = e.message) } }
            _state.update { it.copy(busy = false) }
        }
    }

    fun rerun(failedOnly: Boolean) {
        viewModelScope.launch {
            runCatching { client().rerun(slug(), runId, failedOnly) }
                .onSuccess { message(context.getString(R.string.gh_rerun_started)); refresh() }
                .onFailure { it.report() }
        }
    }
}

@Composable
fun RunScreen(onBack: () -> Unit, viewModel: RunViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    val ext = GitPockitTheme.extendedColors
    val run = s.run
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = { GhTopBar(run?.name ?: stringResource(R.string.gh_actions), run?.htmlUrl, onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OfflineBanner(s.jobs)
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                run?.let { r ->
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RunStatusIcon(r.status, r.conclusion)
                            Text(statusLabel(r.status, r.conclusion), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
                        }
                        Text(r.title.orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
                        Text(
                            listOfNotNull("#${r.runNumber}", r.event, r.branch, r.sha.take(7), ghTime(r.createdAt)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = ext.textSecondary,
                        )
                        if (r.status == "completed") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                OutlinedButton(onClick = { viewModel.rerun(false) }) { Text(stringResource(R.string.gh_rerun)) }
                                if (r.conclusion != "success") OutlinedButton(onClick = { viewModel.rerun(true) }) { Text(stringResource(R.string.gh_rerun_failed)) }
                            }
                        }
                    }
                }
                item { Text(stringResource(R.string.gh_run_jobs), style = MaterialTheme.typography.titleSmall, color = ext.textSecondary) }
                items(s.jobs?.value.orEmpty(), key = { it.id }) { job ->
                    GpCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RunStatusIcon(job.status, job.conclusion)
                            Text(job.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp))
                        }
                        job.steps.forEach { step ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, top = 6.dp)) {
                                RunStatusIcon(step.status, step.conclusion)
                                Text(step.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- pick a GitHub repo to clone

data class GhReposState(
    val loading: Boolean = true,
    val repos: List<GhRepo> = emptyList(),
    val credentialId: Long? = null,
    val error: String? = null,
)

@HiltViewModel
class GitHubReposViewModel @Inject constructor(private val github: GitHubService) : ViewModel() {
    private val _state = MutableStateFlow(GhReposState())
    val state: StateFlow<GhReposState> = _state

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val account = github.accounts.first().firstOrNull()
            if (account == null) {
                _state.update { it.copy(loading = false, credentialId = null) }
                return@launch
            }
            runCatching {
                val gh = github.client(account.id)
                github.cached("repos:${account.id}", ListSerializer(GhRepo.serializer())) {
                    // Up to 500 repositories, most recently updated first.
                    buildList { for (page in 1..5) { val p = gh.repos(page); addAll(p); if (p.size < 100) break } }
                }
            }.onSuccess { loaded -> _state.update { it.copy(loading = false, repos = loaded.value, credentialId = account.id, error = loaded.error) } }
                .onFailure { e -> _state.update { it.copy(loading = false, credentialId = account.id, error = e.message) } }
        }
    }
}

@Composable
fun GitHubReposScreen(onBack: () -> Unit, onPick: (url: String, credentialId: Long) -> Unit, viewModel: GitHubReposViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val accountVm: GitHubAccountViewModel = hiltViewModel()
    val accounts by accountVm.accounts.collectAsStateWithLifecycle()
    LaunchedEffect(accounts?.size) { viewModel.load() }
    var filter by rememberSaveable { mutableStateOf("") }
    val ext = GitPockitTheme.extendedColors

    Scaffold(topBar = { GhTopBar(stringResource(R.string.gh_repos_title), null, onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!s.loading && s.credentialId == null) {
                Column(Modifier.padding(16.dp)) { GitHubAccountCard(compact = true, viewModel = accountVm) }
                return@Column
            }
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text(stringResource(R.string.gh_repos_filter)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
            val shown = s.repos.filter { filter.isBlank() || it.fullName.contains(filter.trim(), ignoreCase = true) || it.description.orEmpty().contains(filter.trim(), ignoreCase = true) }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.fullName }) { repo ->
                    Column(Modifier.fillMaxWidth().clickable { s.credentialId?.let { onPick(repo.cloneUrl, it) } }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(repo.fullName, style = CodeTextStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (repo.private) StatusPill(stringResource(R.string.gh_private), ext.modified)
                            if (repo.fork) StatusPill(stringResource(R.string.gh_fork), ext.textSecondary)
                            if (repo.archived) StatusPill(stringResource(R.string.gh_archived), ext.textSecondary)
                        }
                        repo.description?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = ext.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            listOfNotNull(repo.language, "★ ${repo.stars}", ghTime(repo.updatedAt)).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = ext.textSecondary,
                        )
                    }
                    HorizontalDivider(color = ext.hairline)
                }
            }
        }
    }
}

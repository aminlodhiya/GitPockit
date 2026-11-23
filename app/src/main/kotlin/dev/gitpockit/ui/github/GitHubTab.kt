package dev.gitpockit.ui.github

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import dev.gitpockit.core.designsystem.theme.CodeTextStyle
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.github.GhIssue
import dev.gitpockit.core.github.GhPull
import dev.gitpockit.core.github.GhWorkflowRun
import dev.gitpockit.core.github.GitHubClient
import dev.gitpockit.core.github.GitHubException
import dev.gitpockit.core.github.RepoSlug
import dev.gitpockit.github.GitHubService
import dev.gitpockit.github.Loaded
import dev.gitpockit.sync.RepoOps
import dev.gitpockit.ui.repo.RepoScopedViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject

/** Which GitHub repository and account a screen works with. */
data class GitHubTarget(val slug: RepoSlug, val credentialId: Long)

/** Thrown when the repo has a GitHub remote but no account/token is available. */
class NeedsGitHubAccount : GitHubException("Sign in to GitHub first")

/** Base for screens about one GitHub repository; resolves owner/name and the account once. */
abstract class GitHubScopedViewModel(savedState: SavedStateHandle, ops: RepoOps, protected val github: GitHubService) :
    RepoScopedViewModel(savedState, ops) {
    private var target: GitHubTarget? = null

    protected suspend fun target(): GitHubTarget {
        target?.let { return it }
        val repo = ops.repo(repoId)
        val slug = github.slugFor(repo) ?: throw GitHubException("This repository isn't on GitHub")
        val cred = github.credentialFor(repo) ?: throw NeedsGitHubAccount()
        return GitHubTarget(slug, cred).also { target = it }
    }

    protected suspend fun client(): GitHubClient = github.client(target().credentialId)

    protected suspend fun slug(): RepoSlug = target().slug
}

enum class GhSection { ISSUES, PULLS, ACTIONS }

data class GitHubTabState(
    val section: GhSection = GhSection.ISSUES,
    val open: Boolean = true,
    val query: String = "",
    val thisBranch: Boolean = false,
    val loading: Boolean = false,
    val needsAccount: Boolean = false,
    val error: String? = null,
    val issues: Loaded<List<GhIssue>>? = null,
    val pulls: Loaded<List<GhPull>>? = null,
    val runs: Loaded<List<GhWorkflowRun>>? = null,
)

@HiltViewModel
class GitHubTabViewModel @Inject constructor(savedState: SavedStateHandle, ops: RepoOps, github: GitHubService) :
    GitHubScopedViewModel(savedState, ops, github) {
    private val _state = MutableStateFlow(GitHubTabState())
    val state: StateFlow<GitHubTabState> = _state
    private var job: Job? = null

    init { refresh() }

    override fun refresh() = load()

    fun setSection(s: GhSection) { _state.update { it.copy(section = s) }; load() }
    fun setOpen(open: Boolean) { _state.update { it.copy(open = open) }; load() }
    fun setThisBranch(v: Boolean) { _state.update { it.copy(thisBranch = v) }; load() }
    fun setQuery(q: String) { _state.update { it.copy(query = q) }; load(debounce = true) }

    private fun load(debounce: Boolean = false) {
        job?.cancel()
        job = viewModelScope.launch {
            if (debounce) delay(400)
            _state.update { it.copy(loading = true, error = null) }
            val s = _state.value
            val stateParam = if (s.open) "open" else "closed"
            runCatching {
                val slug = slug()
                val gh = client()
                when (s.section) {
                    GhSection.ISSUES -> {
                        val loaded = if (s.query.isBlank()) {
                            github.cached("issues:${slug.fullName}:$stateParam", ListSerializer(GhIssue.serializer())) { gh.issues(slug, stateParam) }
                        } else {
                            github.cached("search:${slug.fullName}:$stateParam:${s.query}", ListSerializer(GhIssue.serializer())) {
                                gh.searchIssues(slug, s.query, stateParam)
                            }
                        }
                        _state.update { it.copy(issues = loaded) }
                    }
                    GhSection.PULLS -> {
                        val loaded = github.cached("pulls:${slug.fullName}:$stateParam", ListSerializer(GhPull.serializer())) { gh.pulls(slug, stateParam) }
                        _state.update { it.copy(pulls = loaded) }
                    }
                    GhSection.ACTIONS -> {
                        val branch = if (s.thisBranch) ops.read(repoId) { _, git -> git.currentBranch() } else null
                        val loaded = github.cached("runs:${slug.fullName}:${branch.orEmpty()}", ListSerializer(GhWorkflowRun.serializer())) {
                            gh.workflowRuns(slug, branch)
                        }
                        _state.update { it.copy(runs = loaded) }
                    }
                }
            }.onFailure { e ->
                _state.update { it.copy(needsAccount = e is NeedsGitHubAccount, error = if (e is NeedsGitHubAccount) null else e.message) }
            }
            _state.update { it.copy(loading = false) }
        }
    }

    /** Called after signing in from the tab. */
    fun retry() {
        _state.update { it.copy(needsAccount = false) }
        load()
    }
}

@Composable
fun GitHubTab(
    onOpenIssue: (Int) -> Unit,
    onOpenPull: (Int) -> Unit,
    onOpenRun: (Long) -> Unit,
    onNewIssue: () -> Unit,
    onNewPull: () -> Unit,
    viewModel: GitHubTabViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val ext = GitPockitTheme.extendedColors
    val accountVm: GitHubAccountViewModel = hiltViewModel()
    val accounts by accountVm.accounts.collectAsStateWithLifecycle()
    LaunchedEffect(accounts?.size) { if (s.needsAccount && !accounts.isNullOrEmpty()) viewModel.retry() }

    if (s.needsAccount) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.gh_need_account), style = MaterialTheme.typography.bodyMedium, color = ext.textSecondary)
            GitHubAccountCard(compact = true, viewModel = accountVm)
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            val sections = listOf(GhSection.ISSUES to R.string.gh_issues, GhSection.PULLS to R.string.gh_pulls, GhSection.ACTIONS to R.string.gh_actions)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                sections.forEachIndexed { i, (section, label) ->
                    SegmentedButton(
                        selected = s.section == section,
                        onClick = { viewModel.setSection(section) },
                        shape = SegmentedButtonDefaults.itemShape(i, sections.size),
                    ) { Text(stringResource(label), maxLines = 1) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (s.section == GhSection.ACTIONS) {
                    FilterChip(selected = s.thisBranch, onClick = { viewModel.setThisBranch(!s.thisBranch) }, label = { Text(stringResource(R.string.gh_this_branch)) })
                } else {
                    FilterChip(selected = s.open, onClick = { viewModel.setOpen(true) }, label = { Text(stringResource(R.string.gh_open)) })
                    FilterChip(selected = !s.open, onClick = { viewModel.setOpen(false) }, label = { Text(stringResource(R.string.gh_closed)) })
                }
            }
            if (s.section == GhSection.ISSUES) {
                OutlinedTextField(
                    value = s.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text(stringResource(R.string.gh_search_issues)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            when (s.section) {
                GhSection.ISSUES -> {
                    OfflineBanner(s.issues)
                    ItemList(s.issues?.value, R.string.gh_no_issues) { issue -> IssueRow(issue) { onOpenIssue(issue.number) } }
                }
                GhSection.PULLS -> {
                    OfflineBanner(s.pulls)
                    ItemList(s.pulls?.value, R.string.gh_no_pulls) { pull -> PullRow(pull) { onOpenPull(pull.number) } }
                }
                GhSection.ACTIONS -> {
                    OfflineBanner(s.runs)
                    ItemList(s.runs?.value, R.string.gh_no_runs) { run -> RunRow(run) { onOpenRun(run.id) } }
                }
            }
        }
        if (s.section != GhSection.ACTIONS) {
            ExtendedFloatingActionButton(
                onClick = if (s.section == GhSection.ISSUES) onNewIssue else onNewPull,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(if (s.section == GhSection.ISSUES) R.string.gh_new_issue else R.string.gh_new_pull)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

@Composable
private fun <T> ItemList(items: List<T>?, empty: Int, row: @Composable (T) -> Unit) {
    if (items == null) return
    if (items.isEmpty()) {
        Text(stringResource(empty), color = GitPockitTheme.extendedColors.textSecondary, modifier = Modifier.padding(24.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        items(items) { item ->
            row(item)
            HorizontalDivider(color = GitPockitTheme.extendedColors.hairline)
        }
    }
}

@Composable
private fun IssueRow(issue: GhIssue, onClick: () -> Unit) {
    val ext = GitPockitTheme.extendedColors
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StateLabel(issue.isOpen)
            Text("#${issue.number}", style = CodeTextStyle, color = ext.textSecondary)
        }
        Text(issue.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text(
            listOfNotNull(issue.user.login, ghTime(issue.createdAt), issue.comments.takeIf { it > 0 }?.let { "$it comments" }).joinToString(" · ") +
                issue.labels.joinToString("") { "  ·  ${it.name}" },
            style = MaterialTheme.typography.bodySmall,
            color = ext.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun PullRow(pull: GhPull, onClick: () -> Unit) {
    val ext = GitPockitTheme.extendedColors
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StateLabel(pull.isOpen, merged = pull.mergedAt != null, draft = pull.draft && pull.isOpen)
            Text("#${pull.number}", style = CodeTextStyle, color = ext.textSecondary)
        }
        Text(pull.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text(
            "${pull.user.login} · ${pull.head.ref} → ${pull.base.ref} · ${ghTime(pull.createdAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = ext.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RunRow(run: GhWorkflowRun, onClick: () -> Unit) {
    val ext = GitPockitTheme.extendedColors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RunStatusIcon(run.status, run.conclusion)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(run.title ?: run.name.orEmpty(), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(run.name, "#${run.runNumber}", run.branch, run.sha.take(7), ghTime(run.createdAt)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = ext.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

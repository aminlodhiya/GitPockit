package dev.gitpockit.ui.repo

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gitpockit.R
import dev.gitpockit.core.data.model.Repo
import dev.gitpockit.core.data.repository.RepoRepository
import dev.gitpockit.core.designsystem.component.StatusPill
import dev.gitpockit.core.designsystem.theme.CodeTextStyle
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.git.PullResult
import dev.gitpockit.domain.RepoOps
import dev.gitpockit.ui.common.describe
import dev.gitpockit.ui.common.userMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Base for repo-scoped view models: repo id from navigation, a message channel, change-driven refresh. */
abstract class RepoScopedViewModel(savedState: SavedStateHandle, protected val ops: RepoOps) : ViewModel() {
    val repoId: Long = checkNotNull(savedState.get<Long>("repoId"))
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        viewModelScope.launch { ops.changes.filter { it == repoId }.collect { refresh() } }
    }

    abstract fun refresh()

    protected fun message(text: String) {
        _messages.trySend(text)
    }

    protected fun Throwable.report() = message(userMessage())
}

data class RepoHeaderState(
    val repo: Repo? = null,
    val branch: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val conflicts: Int = 0,
    val busy: Boolean = false,
)

@HiltViewModel
class RepoViewModel @javax.inject.Inject constructor(
    savedState: SavedStateHandle,
    ops: RepoOps,
    private val repos: RepoRepository,
    @ApplicationContext private val context: Context,
) : RepoScopedViewModel(savedState, ops) {
    private val _state = MutableStateFlow(RepoHeaderState())
    val state: StateFlow<RepoHeaderState> = _state
    val repo: StateFlow<Repo?> = repos.observe(repoId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init { refresh() }

    override fun refresh() {
        viewModelScope.launch {
            runCatching {
                ops.read(repoId) { _, git ->
                    val (ahead, behind) = git.aheadBehind() ?: (0 to 0)
                    _state.update { it.copy(branch = git.currentBranch(), ahead = ahead, behind = behind, conflicts = git.status().conflicting.size) }
                }
            }.onFailure { it.report() }
        }
    }

    fun fetch() = network { repo, git ->
        git.fetch(remote = repo.remoteName, credentials = ops.credentialsFor(repo))
        context.getString(R.string.fetch_done)
    }

    fun pull() = network { repo, git -> context.describe(git.pull(remote = repo.remoteName, credentials = ops.credentialsFor(repo))) }

    fun push() = network { repo, git -> context.describe(git.push(remote = repo.remoteName, credentials = ops.credentialsFor(repo))) }

    /** Commit everything, pull, push. The Phase 2 sync engine runs this same flow in the background. */
    fun sync() = network { repo, git ->
        val creds = ops.credentialsFor(repo)
        if (!git.status().isClean) {
            git.stageAll()
            git.commit(context.getString(R.string.default_sync_message), ops.authorFor(repo))
        }
        when (val pulled = git.pull(remote = repo.remoteName, credentials = creds)) {
            is PullResult.Conflicts -> context.describe(pulled)
            else -> {
                val pushed = git.push(remote = repo.remoteName, credentials = creds)
                if (pushed.isSuccess) context.getString(R.string.sync_done) else context.describe(pushed)
            }
        }
    }

    private fun network(block: suspend (Repo, dev.gitpockit.core.git.GitRepository) -> String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { ops.write(repoId, block) }
                .onSuccess { msg ->
                    repos.recordSync(repoId, null)
                    message(msg)
                }
                .onFailure { e ->
                    repos.recordSync(repoId, e.userMessage())
                    e.report()
                }
            _state.update { it.copy(busy = false) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCommit: (String) -> Unit,
    onOpenDiff: (path: String, staged: Boolean) -> Unit,
    viewModel: RepoViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val repo by viewModel.repo.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { msg -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(msg) } }
    LaunchedEffect(Unit) { viewModel.messages.collect(showMessage) }
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val ext = GitPockitTheme.extendedColors
    val tabs = listOf(R.string.tab_changes, R.string.tab_history, R.string.tab_branches, R.string.tab_remotes)
    val pager = rememberPagerState { tabs.size }
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(repo?.name.orEmpty(), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                s.branch?.let { Text(it, style = CodeTextStyle, color = ext.textSecondary) }
                                if (s.ahead > 0) Text("↑${s.ahead}", style = CodeTextStyle, color = MaterialTheme.colorScheme.primary)
                                if (s.behind > 0) Text("↓${s.behind}", style = CodeTextStyle, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                    actions = {
                        IconButton(onClick = viewModel::sync, enabled = !s.busy) { Icon(Icons.Default.Sync, stringResource(R.string.action_sync)) }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_pull)) },
                                leadingIcon = { Icon(Icons.Default.CloudDownload, null) },
                                enabled = !s.busy,
                                onClick = { menu = false; viewModel.pull() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_push)) },
                                leadingIcon = { Icon(Icons.Default.CloudUpload, null) },
                                enabled = !s.busy,
                                onClick = { menu = false; viewModel.push() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_fetch)) },
                                leadingIcon = { Icon(Icons.Default.CloudSync, null) },
                                enabled = !s.busy,
                                onClick = { menu = false; viewModel.fetch() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.repo_settings)) },
                                leadingIcon = { Icon(Icons.Default.Settings, null) },
                                onClick = { menu = false; onOpenSettings() },
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 4.dp))
                if (s.conflicts > 0) {
                    StatusPill(
                        stringResource(R.string.sync_conflicts, s.conflicts),
                        ext.removed,
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                PrimaryTabRow(selectedTabIndex = pager.currentPage, containerColor = MaterialTheme.colorScheme.background) {
                    tabs.forEachIndexed { i, label ->
                        Tab(
                            selected = pager.currentPage == i,
                            onClick = { scope.launch { pager.animateScrollToPage(i) } },
                            text = { Text(stringResource(label), maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), beyondViewportPageCount = 1) { page ->
            when (page) {
                0 -> ChangesTab(onOpenDiff = onOpenDiff, showMessage = showMessage)
                1 -> HistoryTab(onOpenCommit = onOpenCommit, showMessage = showMessage)
                2 -> BranchesTab(showMessage = showMessage)
                else -> RemotesTab(showMessage = showMessage)
            }
        }
    }
}

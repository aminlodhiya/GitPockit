package dev.gitpockit.ui.repo

import android.content.Context
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.Label
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
import androidx.compose.material3.PrimaryScrollableTabRow
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
import dev.gitpockit.background.SyncCoordinator
import dev.gitpockit.core.data.model.SyncResult
import dev.gitpockit.core.data.model.SyncTrigger
import dev.gitpockit.core.files.RepoFiles
import dev.gitpockit.core.git.GitOperation
import dev.gitpockit.ui.files.FilesTab
import dev.gitpockit.sync.RepoOps
import dev.gitpockit.sync.SyncDirection
import dev.gitpockit.ui.common.userMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
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
    val branch: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val conflicts: Int = 0,
    val operation: GitOperation = GitOperation.NONE,
)

@HiltViewModel
class RepoViewModel @javax.inject.Inject constructor(
    savedState: SavedStateHandle,
    ops: RepoOps,
    repos: RepoRepository,
    private val coordinator: SyncCoordinator,
    @ApplicationContext private val context: Context,
) : RepoScopedViewModel(savedState, ops) {
    private val _state = MutableStateFlow(RepoHeaderState())
    val state: StateFlow<RepoHeaderState> = _state
    val repo: StateFlow<Repo?> = repos.observe(repoId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** True while any sync (this screen's or a background one) works on the repo. */
    val busy: StateFlow<Boolean> = ops.busy.map { repoId in it }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init { refresh() }

    override fun refresh() {
        viewModelScope.launch {
            runCatching {
                ops.read(repoId) { _, git ->
                    val (ahead, behind) = git.aheadBehind() ?: (0 to 0)
                    val conflicts = git.status().conflicting.size
                    val operation = git.operation()
                    _state.update {
                        it.copy(branch = git.currentBranch(), ahead = ahead, behind = behind, conflicts = conflicts, operation = operation)
                    }
                }
            }.onFailure { it.report() }
        }
    }

    fun fetch() {
        viewModelScope.launch {
            runCatching { ops.write(repoId) { repo, git -> git.fetch(remote = repo.remoteName, credentials = ops.credentialsFor(repo)) } }
                .onSuccess { message(context.getString(R.string.fetch_done)) }
                .onFailure { it.report() }
        }
    }

    fun sync() = run(SyncDirection.BOTH)
    fun pull() = run(SyncDirection.PULL)
    fun push() = run(SyncDirection.PUSH)

    private fun run(direction: SyncDirection) {
        if (busy.value) return
        viewModelScope.launch {
            val outcome = coordinator.run(repoId, SyncTrigger.MANUAL, direction)
            message(
                when (outcome.result) {
                    SyncResult.SYNCED -> outcome.message ?: context.getString(R.string.sync_done)
                    SyncResult.UP_TO_DATE -> context.getString(R.string.sync_up_to_date)
                    SyncResult.OFFLINE -> context.getString(R.string.status_pending)
                    SyncResult.CONFLICTS -> context.getString(R.string.sync_conflicts, outcome.conflicts.size.coerceAtLeast(1))
                    SyncResult.FAILED -> outcome.message ?: context.getString(R.string.result_failed)
                },
            )
        }
    }
}

fun operationTitle(op: GitOperation): Int = when (op) {
    GitOperation.REBASE -> R.string.op_rebase
    GitOperation.CHERRY_PICK -> R.string.op_cherry_pick
    GitOperation.REVERT -> R.string.op_revert
    else -> R.string.op_merge
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCommit: (String) -> Unit,
    onOpenDiff: (path: String, staged: Boolean) -> Unit,
    onOpenConflicts: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenStashes: () -> Unit,
    onOpenTags: () -> Unit,
    onOpenReflog: () -> Unit,
    onCompare: (from: String, to: String) -> Unit,
    onOpenEditor: (String) -> Unit,
    onOpenImage: (String) -> Unit,
    onFileHistory: (String) -> Unit,
    onOpenSubmodules: () -> Unit,
    onOpenLfs: () -> Unit,
    viewModel: RepoViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
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
    val tabs = listOf(R.string.tab_changes, R.string.tab_files, R.string.tab_history, R.string.tab_branches, R.string.tab_remotes)
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
                        IconButton(onClick = viewModel::sync, enabled = !busy) { Icon(Icons.Default.Sync, stringResource(R.string.action_sync)) }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_pull)) },
                                leadingIcon = { Icon(Icons.Default.CloudDownload, null) },
                                enabled = !busy,
                                onClick = { menu = false; viewModel.pull() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_push)) },
                                leadingIcon = { Icon(Icons.Default.CloudUpload, null) },
                                enabled = !busy,
                                onClick = { menu = false; viewModel.push() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_fetch)) },
                                leadingIcon = { Icon(Icons.Default.CloudSync, null) },
                                enabled = !busy,
                                onClick = { menu = false; viewModel.fetch() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_stashes)) },
                                leadingIcon = { Icon(Icons.Default.Inventory2, null) },
                                onClick = { menu = false; onOpenStashes() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_tags)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null) },
                                onClick = { menu = false; onOpenTags() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_submodules)) },
                                leadingIcon = { Icon(Icons.Default.AccountTree, null) },
                                onClick = { menu = false; onOpenSubmodules() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_lfs)) },
                                leadingIcon = { Icon(Icons.Default.Storage, null) },
                                onClick = { menu = false; onOpenLfs() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_gitignore)) },
                                leadingIcon = { Icon(Icons.Default.VisibilityOff, null) },
                                onClick = { menu = false; onOpenEditor(".gitignore") },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_exclude)) },
                                leadingIcon = { Icon(Icons.Default.PhoneAndroid, null) },
                                onClick = { menu = false; onOpenEditor(RepoFiles.EXCLUDE_FILE) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_reflog)) },
                                leadingIcon = { Icon(Icons.Default.Restore, null) },
                                onClick = { menu = false; onOpenReflog() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sync_log)) },
                                leadingIcon = { Icon(Icons.Default.History, null) },
                                onClick = { menu = false; onOpenLog() },
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
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 4.dp))
                if (s.conflicts > 0 || s.operation != GitOperation.NONE) {
                    StatusPill(
                        if (s.operation != GitOperation.NONE) {
                            stringResource(R.string.op_tap_to_resolve, stringResource(operationTitle(s.operation)))
                        } else {
                            stringResource(R.string.resolve_conflicts)
                        },
                        ext.removed,
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clickable(onClick = onOpenConflicts),
                    )
                } else if (repo?.pendingSync == true) {
                    StatusPill(stringResource(R.string.status_pending), ext.modified, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                PrimaryScrollableTabRow(selectedTabIndex = pager.currentPage, containerColor = MaterialTheme.colorScheme.background, edgePadding = 8.dp) {
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
                0 -> ChangesTab(onOpenDiff = onOpenDiff, onOpenConflicts = onOpenConflicts, showMessage = showMessage)
                1 -> FilesTab(
                    active = pager.currentPage == 1,
                    onOpenEditor = onOpenEditor,
                    onOpenImage = onOpenImage,
                    onFileHistory = onFileHistory,
                    showMessage = showMessage,
                )
                2 -> HistoryTab(onOpenCommit = onOpenCommit, showMessage = showMessage)
                3 -> BranchesTab(showMessage = showMessage, onOpenConflicts = onOpenConflicts, onCompare = onCompare)
                else -> RemotesTab(showMessage = showMessage)
            }
        }
    }
}

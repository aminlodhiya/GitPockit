package dev.gitpockit.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gitpockit.R
import dev.gitpockit.core.data.model.Container
import dev.gitpockit.core.data.model.Repo
import dev.gitpockit.core.data.repository.RepoRepository
import dev.gitpockit.core.designsystem.component.EmptyState
import dev.gitpockit.core.designsystem.component.GpCard
import dev.gitpockit.core.designsystem.component.GradientButton
import dev.gitpockit.core.designsystem.component.StatusPill
import dev.gitpockit.core.designsystem.theme.CodeTextStyle
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.domain.RepoOps
import dev.gitpockit.ui.common.ConfirmDialog
import dev.gitpockit.ui.common.InputDialog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RepoStatusSummary(
    val branch: String?,
    val lastCommit: String?,
    val dirty: Boolean,
    val conflicts: Boolean,
    val ahead: Int,
    val behind: Int,
    val missing: Boolean = false,
)

data class HomeUiState(
    val loaded: Boolean = false,
    val repos: List<Repo> = emptyList(),
    val containers: List<Container> = emptyList(),
    val filter: Long? = null,
    val statuses: Map<Long, RepoStatusSummary> = emptyMap(),
) {
    val visibleRepos: List<Repo> get() = if (filter == null) repos else repos.filter { it.containerId == filter }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repoRepository: RepoRepository,
    private val ops: RepoOps,
) : ViewModel() {
    private val filter = MutableStateFlow<Long?>(null)
    private val statuses = MutableStateFlow<Map<Long, RepoStatusSummary>>(emptyMap())

    val state: StateFlow<HomeUiState> = combine(
        repoRepository.repos, repoRepository.containers, filter, statuses,
    ) { repos, containers, f, st ->
        HomeUiState(true, repos, containers, f?.takeIf { id -> containers.any { it.id == id } }, st)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch { repoRepository.repos.collect { refreshStatuses(it) } }
        viewModelScope.launch { ops.changes.collect { refresh() } }
    }

    fun refresh() = viewModelScope.launch { refreshStatuses(state.value.repos) }

    private suspend fun refreshStatuses(repos: List<Repo>) {
        val results = repos.map { repo ->
            viewModelScope.async {
                repo.id to runCatching {
                    ops.read(repo.id) { _, git ->
                        val status = git.status()
                        val (ahead, behind) = git.aheadBehind() ?: (0 to 0)
                        RepoStatusSummary(
                            branch = git.currentBranch(),
                            lastCommit = git.log(maxCount = 1).firstOrNull()?.title,
                            dirty = !status.isClean,
                            conflicts = status.conflicting.isNotEmpty(),
                            ahead = ahead,
                            behind = behind,
                        )
                    }
                }.getOrElse { RepoStatusSummary(null, null, false, false, 0, 0, missing = true) }
            }
        }.awaitAll()
        statuses.update { results.toMap() }
    }

    fun setFilter(containerId: Long?) = filter.update { containerId }

    fun createContainer(name: String) = viewModelScope.launch { filter.value = repoRepository.createContainer(name) }

    fun renameContainer(id: Long, name: String) = viewModelScope.launch { repoRepository.renameContainer(id, name) }

    fun deleteContainer(id: Long) = viewModelScope.launch { repoRepository.deleteContainer(id) }

    fun moveRepo(repoId: Long, containerId: Long?) = viewModelScope.launch { repoRepository.moveToContainer(repoId, containerId) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onClone: () -> Unit,
    onAddExisting: () -> Unit,
    onCreate: () -> Unit,
    onSettings: () -> Unit,
    onOpenRepo: (Long) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showAddSheet by remember { mutableStateOf(false) }
    var newContainer by remember { mutableStateOf(false) }
    var editContainer by remember { mutableStateOf<Container?>(null) }
    var deleteContainer by remember { mutableStateOf<Container?>(null) }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
        },
        floatingActionButton = {
            if (s.repos.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showAddSheet = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_add_repo)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { padding ->
        when {
            !s.loaded -> Unit
            s.repos.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Default.FolderCopy,
                    title = stringResource(R.string.home_empty_title),
                    message = stringResource(R.string.home_empty_message),
                    action = {
                        GradientButton(
                            text = stringResource(R.string.action_add_repo),
                            onClick = { showAddSheet = true },
                            icon = Icons.Default.Add,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    },
                )
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    ContainerChips(
                        containers = s.containers,
                        selected = s.filter,
                        onSelect = viewModel::setFilter,
                        onNew = { newContainer = true },
                        onEdit = { editContainer = it },
                    )
                }
                if (s.visibleRepos.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.home_container_empty),
                            color = GitPockitTheme.extendedColors.textSecondary,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                items(s.visibleRepos, key = { it.id }) { repo ->
                    RepoCard(
                        repo = repo,
                        status = s.statuses[repo.id],
                        containers = s.containers,
                        onClick = { onOpenRepo(repo.id) },
                        onMove = { viewModel.moveRepo(repo.id, it) },
                    )
                }
            }
        }
    }

    if (showAddSheet) {
        AddRepoSheet(
            onDismiss = { showAddSheet = false },
            onClone = { showAddSheet = false; onClone() },
            onAddExisting = { showAddSheet = false; onAddExisting() },
            onCreate = { showAddSheet = false; onCreate() },
        )
    }
    if (newContainer) {
        InputDialog(
            title = stringResource(R.string.container_new),
            labels = listOf(stringResource(R.string.name)),
            confirmText = stringResource(R.string.action_create),
            onConfirm = { viewModel.createContainer(it[0]) },
            onDismiss = { newContainer = false },
        )
    }
    editContainer?.let { c ->
        InputDialog(
            title = stringResource(R.string.container_rename),
            labels = listOf(stringResource(R.string.name)),
            initial = listOf(c.name),
            onConfirm = { viewModel.renameContainer(c.id, it[0]) },
            onDismiss = { editContainer = null },
            extra = {
                androidx.compose.material3.TextButton(onClick = { deleteContainer = c; editContainer = null }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
    deleteContainer?.let { c ->
        ConfirmDialog(
            title = stringResource(R.string.container_delete_title, c.name),
            message = stringResource(R.string.container_delete_message),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = { viewModel.deleteContainer(c.id) },
            onDismiss = { deleteContainer = null },
        )
    }
}

@Composable
private fun ContainerChips(
    containers: List<Container>,
    selected: Long?,
    onSelect: (Long?) -> Unit,
    onNew: () -> Unit,
    onEdit: (Container) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(stringResource(R.string.filter_all)) })
        }
        items(containers, key = { it.id }) { c ->
            FilterChip(
                selected = selected == c.id,
                onClick = { if (selected == c.id) onEdit(c) else onSelect(c.id) },
                label = { Text(c.name) },
            )
        }
        item {
            FilterChip(
                selected = false,
                onClick = onNew,
                label = { Text(stringResource(R.string.container_new)) },
                leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RepoCard(
    repo: Repo,
    status: RepoStatusSummary?,
    containers: List<Container>,
    onClick: () -> Unit,
    onMove: (Long?) -> Unit,
) {
    val ext = GitPockitTheme.extendedColors
    var menu by remember { mutableStateOf(false) }
    Box {
        GpCard(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(40.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.CallSplit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                    Text(repo.name, style = MaterialTheme.typography.titleMedium)
                    status?.branch?.let { Text(stringResource(R.string.repo_branch, it), style = CodeTextStyle, color = ext.textSecondary) }
                }
            }
            status?.lastCommit?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = ext.textSecondary, maxLines = 1, modifier = Modifier.padding(top = 12.dp))
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status?.missing == true) {
                    StatusPill(stringResource(R.string.repo_missing), ext.removed)
                }
                if (status?.conflicts == true) StatusPill(stringResource(R.string.status_conflicts), ext.removed)
                else if (status?.dirty == true) StatusPill(stringResource(R.string.tab_changes), ext.modified)
                if ((status?.ahead ?: 0) > 0) StatusPill(stringResource(R.string.status_ahead, status!!.ahead), MaterialTheme.colorScheme.primary)
                if ((status?.behind ?: 0) > 0) StatusPill(stringResource(R.string.status_behind, status!!.behind), MaterialTheme.colorScheme.secondary)
                repo.lastError?.let { StatusPill("!", ext.removed) }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Text(
                stringResource(R.string.move_to_container),
                style = MaterialTheme.typography.labelMedium,
                color = ext.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            DropdownMenuItem(text = { Text(stringResource(R.string.no_container)) }, onClick = { onMove(null); menu = false })
            containers.forEach { c ->
                DropdownMenuItem(text = { Text(c.name) }, onClick = { onMove(c.id); menu = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRepoSheet(onDismiss: () -> Unit, onClone: () -> Unit, onAddExisting: () -> Unit, onCreate: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            SheetOption(Icons.Default.CloudDownload, R.string.add_clone, R.string.add_clone_desc, onClone)
            SheetOption(Icons.Default.FolderOpen, R.string.add_existing, R.string.add_existing_desc, onAddExisting)
            SheetOption(Icons.Default.CreateNewFolder, R.string.add_create, R.string.add_create_desc, onCreate)
        }
    }
}

@Composable
private fun SheetOption(icon: ImageVector, title: Int, desc: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(desc)) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onClick),
    )
}



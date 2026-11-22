package dev.gitpockit.ui.repo

import android.content.Context
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gitpockit.R
import dev.gitpockit.core.designsystem.component.StatusPill
import dev.gitpockit.core.designsystem.theme.CodeTextStyle
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.git.GitRepository
import dev.gitpockit.core.git.OperationResult
import dev.gitpockit.core.git.ReflogEntryInfo
import dev.gitpockit.core.git.ResetMode
import dev.gitpockit.core.git.StashInfo
import dev.gitpockit.core.git.TagInfo
import dev.gitpockit.sync.RepoOps
import dev.gitpockit.ui.common.ConfirmDialog
import dev.gitpockit.ui.common.InputDialog
import dev.gitpockit.ui.common.ResetDialog
import dev.gitpockit.ui.common.describe
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Shared base: loads a list, runs actions under the repo lock, reports results. */
abstract class RepoListViewModel<T>(savedState: SavedStateHandle, ops: RepoOps) : RepoScopedViewModel(savedState, ops) {
    private val _items = MutableStateFlow<List<T>?>(null)
    val items: StateFlow<List<T>?> = _items
    private val _conflicts = Channel<Unit>(Channel.CONFLATED)
    val conflicts = _conflicts.receiveAsFlow()

    protected abstract suspend fun load(git: GitRepository): List<T>

    override fun refresh() {
        viewModelScope.launch {
            runCatching { ops.read(repoId) { _, git -> load(git) } }
                .onSuccess { _items.value = it }
                .onFailure { _items.value = emptyList(); it.report() }
        }
    }

    protected fun act(success: String?, block: suspend (dev.gitpockit.core.data.model.Repo, GitRepository) -> Any?) {
        viewModelScope.launch {
            runCatching { ops.write(repoId, block) }
                .onSuccess { r ->
                    if (r is OperationResult.Conflicts) _conflicts.trySend(Unit) else success?.let(::message)
                }
                .onFailure { it.report() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolScaffold(
    title: String,
    onBack: () -> Unit,
    messages: kotlinx.coroutines.flow.Flow<String>,
    fab: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { messages.collect { snackbar.showSnackbar(it) } }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = { fab?.invoke() },
        content = content,
    )
}

@Composable
private fun Fab(text: String, onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(Icons.Default.Add, null) },
        text = { Text(text) },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    )
}

@Composable
private fun EmptyText(text: String, padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = GitPockitTheme.extendedColors.textSecondary)
    }
}

@Composable
private fun RowMenu(actions: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() }) }
        }
    }
}

/** "Stash changes" dialog: optional message and whether to include new files. */
@Composable
fun StashDialog(onStash: (message: String, includeUntracked: Boolean) -> Unit, onDismiss: () -> Unit) {
    var message by remember { mutableStateOf("") }
    var untracked by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stash_save)) },
        text = {
            Column {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text(stringResource(R.string.stash_message)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = untracked, onCheckedChange = { untracked = it })
                    Text(stringResource(R.string.stash_include_untracked))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onStash(message, untracked); onDismiss() }) { Text(stringResource(R.string.stash_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ---------------------------------------------------------------- stashes

@HiltViewModel
class StashesViewModel @Inject constructor(
    savedState: SavedStateHandle,
    ops: RepoOps,
    @ApplicationContext private val context: Context,
) : RepoListViewModel<StashInfo>(savedState, ops) {
    init { refresh() }

    override suspend fun load(git: GitRepository) = git.stashes()

    fun save(message: String, untracked: Boolean) = act(null) { repo, git ->
        val saved = git.stashSave(message, untracked, ops.authorFor(repo))
        message(context.getString(if (saved) R.string.stash_saved else R.string.stash_nothing))
    }

    fun apply(index: Int, pop: Boolean) = act(context.getString(R.string.stash_applied)) { _, git -> git.stashApply(index, pop) }

    fun drop(index: Int) = act(null) { _, git -> git.stashDrop(index) }
}

@Composable
fun StashesScreen(onBack: () -> Unit, onOpenConflicts: () -> Unit, viewModel: StashesViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.conflicts.collect { onOpenConflicts() } }
    var saving by remember { mutableStateOf(false) }
    var dropping by remember { mutableStateOf<Int?>(null) }
    val ext = GitPockitTheme.extendedColors

    ToolScaffold(stringResource(R.string.menu_stashes), onBack, viewModel.messages, fab = { Fab(stringResource(R.string.stash_save)) { saving = true } }) { padding ->
        val list = items ?: return@ToolScaffold
        if (list.isEmpty()) return@ToolScaffold EmptyText(stringResource(R.string.stashes_empty), padding)
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            items(list, key = { it.id }) { stash ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stash.message, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("stash@{${stash.index}} · ${relativeTime(stash.time)}", style = CodeTextStyle, color = ext.textSecondary)
                    }
                    RowMenu(
                        listOf(
                            stringResource(R.string.stash_pop) to { viewModel.apply(stash.index, pop = true) },
                            stringResource(R.string.stash_apply) to { viewModel.apply(stash.index, pop = false) },
                            stringResource(R.string.action_delete) to { dropping = stash.index },
                        ),
                    )
                }
                HorizontalDivider(color = ext.hairline)
            }
        }
    }
    if (saving) StashDialog(viewModel::save) { saving = false }
    dropping?.let { index ->
        ConfirmDialog(
            title = stringResource(R.string.stash_drop_title),
            message = stringResource(R.string.stash_drop_message),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = { viewModel.drop(index) },
            onDismiss = { dropping = null },
        )
    }
}

// ---------------------------------------------------------------- tags

@HiltViewModel
class TagsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    ops: RepoOps,
    @ApplicationContext private val context: Context,
) : RepoListViewModel<TagInfo>(savedState, ops) {
    private val _remote = MutableStateFlow("origin")
    val remote: StateFlow<String> = _remote

    init { refresh() }

    override suspend fun load(git: GitRepository): List<TagInfo> {
        _remote.value = ops.repo(repoId).remoteName
        return git.tags()
    }

    fun create(name: String, message: String) = act(null) { repo, git ->
        git.createTag(name.replace(' ', '-'), message.ifBlank { null }, null, ops.authorFor(repo))
    }

    fun checkout(tag: TagInfo) = act(context.getString(R.string.detached_at, tag.name)) { _, git -> git.checkoutDetached(tag.commitId) }

    fun push(name: String) = network(name, delete = false, done = R.string.tag_pushed)

    fun deleteRemote(name: String) = network(name, delete = true, done = R.string.tag_deleted_remote)

    fun delete(name: String) = act(null) { _, git -> git.deleteTag(name) }

    private fun network(name: String, delete: Boolean, done: Int) = act(null) { repo, git ->
        val result = git.pushTag(name, repo.remoteName, ops.credentialsFor(repo), delete)
        message(if (result.isSuccess) context.getString(done) else context.describe(result))
    }
}

@Composable
fun TagsScreen(onBack: () -> Unit, onOpenCommit: (String) -> Unit, viewModel: TagsViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val remote by viewModel.remote.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val ext = GitPockitTheme.extendedColors

    ToolScaffold(stringResource(R.string.menu_tags), onBack, viewModel.messages, fab = { Fab(stringResource(R.string.tag_create)) { creating = true } }) { padding ->
        val list = items ?: return@ToolScaffold
        if (list.isEmpty()) return@ToolScaffold EmptyText(stringResource(R.string.tags_empty), padding)
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            items(list, key = { it.name }) { tag ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenCommit(tag.commitId) }.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(tag.name, style = CodeTextStyle, color = MaterialTheme.colorScheme.primary)
                            if (tag.annotated) StatusPill(stringResource(R.string.tag_annotated), ext.textSecondary)
                        }
                        tag.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        Text(
                            listOfNotNull(tag.commitId.take(7), tag.taggerName, tag.time?.let { relativeTime(it) }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = ext.textSecondary,
                        )
                    }
                    RowMenu(
                        listOf(
                            stringResource(R.string.action_checkout_commit) to { viewModel.checkout(tag) },
                            stringResource(R.string.tag_push, remote) to { viewModel.push(tag.name) },
                            stringResource(R.string.tag_delete_remote, remote) to { viewModel.deleteRemote(tag.name) },
                            stringResource(R.string.tag_delete_local) to { deleting = tag.name },
                        ),
                    )
                }
                HorizontalDivider(color = ext.hairline)
            }
        }
    }
    if (creating) {
        InputDialog(
            title = stringResource(R.string.tag_create),
            labels = listOf(stringResource(R.string.tag_name), stringResource(R.string.tag_message)),
            confirmText = stringResource(R.string.action_create),
            optional = setOf(1),
            onConfirm = { viewModel.create(it[0], it[1]) },
            onDismiss = { creating = false },
        )
    }
    deleting?.let { name ->
        ConfirmDialog(
            title = stringResource(R.string.tag_delete_title, name),
            message = null,
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = { viewModel.delete(name) },
            onDismiss = { deleting = null },
        )
    }
}

// ---------------------------------------------------------------- reflog

@HiltViewModel
class ReflogViewModel @Inject constructor(
    savedState: SavedStateHandle,
    ops: RepoOps,
    @ApplicationContext private val context: Context,
) : RepoListViewModel<ReflogEntryInfo>(savedState, ops) {
    private val _branch = MutableStateFlow("HEAD")
    val branch: StateFlow<String> = _branch

    init { refresh() }

    override suspend fun load(git: GitRepository): List<ReflogEntryInfo> {
        _branch.value = git.currentBranch().orEmpty()
        return git.reflog()
    }

    fun reset(id: String, mode: ResetMode) = act(context.getString(R.string.reset_done, id.take(7))) { _, git -> git.reset(id, mode) }
}

@Composable
fun ReflogScreen(onBack: () -> Unit, onOpenCommit: (String) -> Unit, viewModel: ReflogViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val branch by viewModel.branch.collectAsStateWithLifecycle()
    var resetTo by remember { mutableStateOf<String?>(null) }
    val ext = GitPockitTheme.extendedColors

    ToolScaffold(stringResource(R.string.menu_reflog), onBack, viewModel.messages) { padding ->
        val list = items ?: return@ToolScaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    stringResource(R.string.reflog_explain),
                    style = MaterialTheme.typography.bodySmall,
                    color = ext.textSecondary,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (list.isEmpty()) item { Text(stringResource(R.string.reflog_empty), color = ext.textSecondary, modifier = Modifier.padding(16.dp)) }
            items(list.withIndex().toList(), key = { (i, e) -> "$i-${e.newId}" }) { (_, e) ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.message, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(e.newId.take(7), style = CodeTextStyle, color = MaterialTheme.colorScheme.primary)
                            Text(relativeTime(e.time), style = MaterialTheme.typography.bodySmall, color = ext.textSecondary)
                        }
                    }
                    RowMenu(
                        listOf(
                            stringResource(R.string.action_view_commit) to { onOpenCommit(e.newId) },
                            stringResource(R.string.action_reset_here) to { resetTo = e.newId },
                        ),
                    )
                }
                HorizontalDivider(color = ext.hairline)
            }
        }
    }
    resetTo?.let { id -> ResetDialog(branch, id.take(7), { mode -> viewModel.reset(id, mode) }) { resetTo = null } }
}

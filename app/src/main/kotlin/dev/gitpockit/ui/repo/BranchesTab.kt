package dev.gitpockit.ui.repo

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gitpockit.R
import dev.gitpockit.core.designsystem.theme.CodeTextStyle
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.git.BranchInfo
import dev.gitpockit.core.git.GitBranchNotMergedException
import dev.gitpockit.core.git.OperationResult
import kotlinx.coroutines.flow.receiveAsFlow
import dev.gitpockit.sync.RepoOps
import dev.gitpockit.ui.common.ConfirmDialog
import dev.gitpockit.ui.common.InputDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BranchesState(
    val loaded: Boolean = false,
    val local: List<BranchInfo> = emptyList(),
    val remote: List<BranchInfo> = emptyList(),
    /** Set when a delete needs confirmation because the branch isn't merged. */
    val confirmForceDelete: String? = null,
)

@HiltViewModel
class BranchesViewModel @Inject constructor(
    savedState: SavedStateHandle,
    ops: RepoOps,
    @ApplicationContext private val context: Context,
) : RepoScopedViewModel(savedState, ops) {
    private val _state = MutableStateFlow(BranchesState())
    val state: StateFlow<BranchesState> = _state

    init { refresh() }

    override fun refresh() {
        viewModelScope.launch {
            runCatching { ops.read(repoId) { _, git -> git.branches(includeRemote = true) } }
                .onSuccess { all ->
                    _state.update {
                        it.copy(
                            loaded = true,
                            local = all.filterNot(BranchInfo::isRemote).sortedWith(compareByDescending<BranchInfo> { b -> b.isCurrent }.thenBy { b -> b.name }),
                            // origin/HEAD is a pointer, not a branch.
                            remote = all.filter { b -> b.isRemote && !b.name.endsWith("/HEAD") }.sortedBy { b -> b.name },
                        )
                    }
                }
                .onFailure { _state.update { s -> s.copy(loaded = true) }; it.report() }
        }
    }

    fun create(name: String, checkout: Boolean) = act(if (checkout) context.getString(R.string.branch_switched, name) else null) {
        it.createBranch(name, checkout = checkout)
    }

    fun checkout(name: String) = act(context.getString(R.string.branch_switched, name.substringAfter('/'))) { it.checkout(name) }

    fun rename(old: String, new: String) = act(null) { it.renameBranch(old, new) }

    fun delete(name: String, force: Boolean) {
        viewModelScope.launch {
            runCatching { ops.write(repoId) { _, git -> git.deleteBranch(name, force) } }
                .onFailure { e ->
                    if (e is GitBranchNotMergedException) _state.update { it.copy(confirmForceDelete = name) } else e.report()
                }
        }
    }

    fun dismissForceDelete() = _state.update { it.copy(confirmForceDelete = null) }

    /** Emits once when an operation stopped with conflicts, so the screen can open the resolver. */
    private val _conflicts = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    val conflicts = _conflicts.receiveAsFlow()

    fun merge(name: String) = operation(context.getString(R.string.merge_done, name)) { it.merge(name) }

    fun rebaseOnto(name: String) = operation(context.getString(R.string.rebase_done, name)) { it.rebase(name) }

    private fun operation(success: String, block: suspend (dev.gitpockit.core.git.GitRepository) -> OperationResult) {
        viewModelScope.launch {
            runCatching { ops.write(repoId) { _, git -> block(git) } }
                .onSuccess { r ->
                    when (r) {
                        is OperationResult.Conflicts -> {
                            message(context.getString(R.string.conflicts_to_resolve, r.paths.size))
                            _conflicts.trySend(Unit)
                        }
                        OperationResult.UpToDate -> message(context.getString(R.string.sync_up_to_date))
                        else -> message(success)
                    }
                }
                .onFailure { it.report() }
        }
    }

    private fun act(success: String?, block: suspend (dev.gitpockit.core.git.GitRepository) -> Unit) {
        viewModelScope.launch {
            runCatching { ops.write(repoId) { _, git -> block(git) } }
                .onSuccess { success?.let(::message) }
                .onFailure { it.report() }
        }
    }
}

@Composable
fun BranchesTab(
    showMessage: (String) -> Unit,
    onOpenConflicts: () -> Unit,
    onCompare: (from: String, to: String) -> Unit,
    viewModel: BranchesViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.messages.collect(showMessage) }
    LaunchedEffect(Unit) { viewModel.conflicts.collect { onOpenConflicts() } }
    val current = s.local.firstOrNull { it.isCurrent }?.name.orEmpty()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    if (!s.loaded) return

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item { SectionTitle(R.string.branches_local) }
            items(s.local, key = { it.fullRef }) { b ->
                BranchRow(
                    branch = b,
                    onClick = { if (!b.isCurrent) viewModel.checkout(b.name) },
                    menu = if (b.isCurrent) {
                        listOf(stringResource(R.string.action_rename) to { renaming = b.name })
                    } else {
                        listOf(
                            stringResource(R.string.branch_checkout) to { viewModel.checkout(b.name) },
                            stringResource(R.string.branch_merge_into_current, current) to { viewModel.merge(b.name) },
                            stringResource(R.string.branch_rebase_onto, current) to { viewModel.rebaseOnto(b.name) },
                            stringResource(R.string.branch_compare_with, current) to { onCompare(current, b.name) },
                            stringResource(R.string.action_rename) to { renaming = b.name },
                            stringResource(R.string.action_delete) to { deleting = b.name },
                        )
                    },
                )
            }
            if (s.remote.isNotEmpty()) {
                item { SectionTitle(R.string.branches_remote) }
                items(s.remote, key = { it.fullRef }) { b ->
                    BranchRow(
                        branch = b,
                        onClick = { viewModel.checkout(b.name) },
                        menu = listOf(
                            stringResource(R.string.branch_checkout) to { viewModel.checkout(b.name) },
                            stringResource(R.string.branch_merge_into_current, current) to { viewModel.merge(b.name) },
                            stringResource(R.string.branch_rebase_onto, current) to { viewModel.rebaseOnto(b.name) },
                            stringResource(R.string.branch_compare_with, current) to { onCompare(current, b.name) },
                        ),
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text(stringResource(R.string.branch_new)) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (creating) {
        var checkout by remember { mutableStateOf(true) }
        InputDialog(
            title = stringResource(R.string.branch_new),
            labels = listOf(stringResource(R.string.branch_name)),
            confirmText = stringResource(R.string.action_create),
            onConfirm = { viewModel.create(it[0].replace(' ', '-'), checkout) },
            onDismiss = { creating = false },
            extra = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checkout, onCheckedChange = { checkout = it })
                    Text(stringResource(R.string.branch_checkout_after))
                }
            },
        )
    }
    renaming?.let { old ->
        InputDialog(
            title = stringResource(R.string.branch_rename_title),
            labels = listOf(stringResource(R.string.branch_name)),
            initial = listOf(old),
            onConfirm = { viewModel.rename(old, it[0].replace(' ', '-')) },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { name ->
        ConfirmDialog(
            title = stringResource(R.string.branch_delete_title, name),
            message = null,
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = { viewModel.delete(name, force = false) },
            onDismiss = { deleting = null },
        )
    }
    s.confirmForceDelete?.let { name ->
        ConfirmDialog(
            title = stringResource(R.string.branch_delete_title, name),
            message = stringResource(R.string.branch_delete_unmerged),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = { viewModel.delete(name, force = true) },
            onDismiss = viewModel::dismissForceDelete,
        )
    }
}

@Composable
internal fun SectionTitle(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.titleSmall,
        color = GitPockitTheme.extendedColors.textSecondary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun BranchRow(branch: BranchInfo, onClick: () -> Unit, menu: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when {
                branch.isCurrent -> Icons.Default.Check
                branch.isRemote -> Icons.Default.Cloud
                else -> Icons.AutoMirrored.Filled.CallSplit
            },
            null,
            tint = if (branch.isCurrent) MaterialTheme.colorScheme.primary else GitPockitTheme.extendedColors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                branch.name,
                style = CodeTextStyle,
                color = if (branch.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (branch.isCurrent) {
                Text(stringResource(R.string.branch_current), style = MaterialTheme.typography.labelSmall, color = GitPockitTheme.extendedColors.textSecondary)
            }
        }
        Box {
            IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                menu.forEach { (label, action) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
                }
            }
        }
    }
}

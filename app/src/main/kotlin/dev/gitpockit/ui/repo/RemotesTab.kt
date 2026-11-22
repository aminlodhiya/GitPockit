package dev.gitpockit.ui.repo

import android.content.Context
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
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
import dev.gitpockit.core.data.repository.RepoRepository
import dev.gitpockit.core.designsystem.component.GpCard
import dev.gitpockit.core.designsystem.component.StatusPill
import dev.gitpockit.core.designsystem.theme.CodeTextStyle
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.git.RemoteInfo
import dev.gitpockit.sync.RepoOps
import dev.gitpockit.ui.common.ConfirmDialog
import dev.gitpockit.ui.common.InputDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RemotesState(val loaded: Boolean = false, val remotes: List<RemoteInfo> = emptyList(), val syncRemote: String = "origin")

@HiltViewModel
class RemotesViewModel @Inject constructor(
    savedState: SavedStateHandle,
    ops: RepoOps,
    private val repos: RepoRepository,
    @ApplicationContext private val context: Context,
) : RepoScopedViewModel(savedState, ops) {
    private val _state = MutableStateFlow(RemotesState())
    val state: StateFlow<RemotesState> = _state

    init { refresh() }

    override fun refresh() {
        viewModelScope.launch {
            runCatching { ops.read(repoId) { repo, git -> repo.remoteName to git.remotes() } }
                .onSuccess { (sync, list) -> _state.update { RemotesState(true, list.sortedBy { it.name }, sync) } }
                .onFailure { _state.update { s -> s.copy(loaded = true) }; it.report() }
        }
    }

    fun add(name: String, url: String) = act {
        it.addRemote(name, url)
        // First remote of a repo becomes the sync remote and the current branch's upstream.
        if (_state.value.remotes.isEmpty()) {
            repos.setRemoteName(repoId, name)
            it.currentBranch()?.let { b -> it.setUpstream(b, name) }
        }
    }

    fun setUrl(name: String, url: String) = act { it.setRemoteUrl(name, url) }

    fun rename(old: String, new: String) = act {
        it.renameRemote(old, new)
        if (_state.value.syncRemote == old) repos.setRemoteName(repoId, new)
    }

    fun delete(name: String) = act { it.removeRemote(name) }

    fun useForSync(name: String) = viewModelScope.launch {
        repos.setRemoteName(repoId, name)
        refresh()
    }

    fun fetch(name: String) {
        viewModelScope.launch {
            runCatching { ops.write(repoId) { repo, git -> git.fetch(remote = name, credentials = ops.credentialsFor(repo)) } }
                .onSuccess { message(context.getString(R.string.fetch_done)) }
                .onFailure { it.report() }
        }
    }

    private fun act(block: suspend (dev.gitpockit.core.git.GitRepository) -> Unit) {
        viewModelScope.launch {
            runCatching { ops.write(repoId) { _, git -> block(git) } }.onFailure { it.report() }
        }
    }
}

@Composable
fun RemotesTab(showMessage: (String) -> Unit, viewModel: RemotesViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.messages.collect(showMessage) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RemoteInfo?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val ext = GitPockitTheme.extendedColors
    if (!s.loaded) return

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (s.remotes.isEmpty()) {
                item { Text(stringResource(R.string.remotes_empty), color = ext.textSecondary, modifier = Modifier.padding(vertical = 24.dp)) }
            }
            items(s.remotes, key = { it.name }) { r ->
                var menu by remember { mutableStateOf(false) }
                GpCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.name, style = MaterialTheme.typography.titleMedium)
                                if (r.name == s.syncRemote) {
                                    StatusPill(stringResource(R.string.remote_default), MaterialTheme.colorScheme.primary, Modifier.padding(start = 8.dp))
                                }
                            }
                            r.fetchUrls.forEach { Text(it, style = CodeTextStyle, color = ext.textSecondary) }
                            r.pushUrls.forEach { Text(stringResource(R.string.remote_push_url, it), style = CodeTextStyle, color = ext.textSecondary) }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_fetch)) }, onClick = { menu = false; viewModel.fetch(r.name) })
                                if (r.name != s.syncRemote) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.remote_use_for_sync)) }, onClick = { menu = false; viewModel.useForSync(r.name) })
                                }
                                DropdownMenuItem(text = { Text(stringResource(R.string.remote_edit)) }, onClick = { menu = false; editing = r })
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menu = false; renaming = r.name })
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; deleting = r.name })
                            }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text(stringResource(R.string.remote_add)) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (adding) {
        InputDialog(
            title = stringResource(R.string.remote_add),
            labels = listOf(stringResource(R.string.remote_name), stringResource(R.string.remote_url)),
            initial = listOf(if (s.remotes.none { it.name == "origin" }) "origin" else "", ""),
            confirmText = stringResource(R.string.action_add),
            onConfirm = { viewModel.add(it[0], it[1]) },
            onDismiss = { adding = false },
        )
    }
    editing?.let { r ->
        InputDialog(
            title = stringResource(R.string.remote_edit),
            labels = listOf(stringResource(R.string.remote_url)),
            initial = listOf(r.fetchUrls.firstOrNull().orEmpty()),
            onConfirm = { viewModel.setUrl(r.name, it[0]) },
            onDismiss = { editing = null },
        )
    }
    renaming?.let { old ->
        InputDialog(
            title = stringResource(R.string.action_rename),
            labels = listOf(stringResource(R.string.remote_name)),
            initial = listOf(old),
            onConfirm = { viewModel.rename(old, it[0]) },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { name ->
        ConfirmDialog(
            title = stringResource(R.string.remote_delete_title, name),
            message = null,
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = { viewModel.delete(name) },
            onDismiss = { deleting = null },
        )
    }
}

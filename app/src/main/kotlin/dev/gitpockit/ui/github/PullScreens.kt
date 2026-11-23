package dev.gitpockit.ui.github

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gitpockit.R
import dev.gitpockit.core.data.db.PendingCommentEntity
import dev.gitpockit.core.designsystem.component.GpCard
import dev.gitpockit.core.designsystem.component.GradientButton
import dev.gitpockit.core.designsystem.theme.CodeTextStyle
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.github.CiState
import dev.gitpockit.core.github.GhComment
import dev.gitpockit.core.github.GhPull
import dev.gitpockit.core.github.GhPullFile
import dev.gitpockit.core.github.MergeMethod
import dev.gitpockit.github.GitHubService
import dev.gitpockit.github.Loaded
import dev.gitpockit.sync.RepoOps
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject

// ---------------------------------------------------------------- pull request detail

data class PullState(
    val pull: Loaded<GhPull>? = null,
    val comments: Loaded<List<GhComment>>? = null,
    val files: Loaded<List<GhPullFile>>? = null,
    val ci: CiState? = null,
    val pending: List<PendingCommentEntity> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class PullViewModel @Inject constructor(
    savedState: SavedStateHandle,
    ops: RepoOps,
    github: GitHubService,
    @ApplicationContext private val context: Context,
) : GitHubScopedViewModel(savedState, ops, github) {
    val number: Int = checkNotNull(savedState["number"])
    private val _state = MutableStateFlow(PullState())
    val state: StateFlow<PullState> = _state

    init {
        refresh()
        viewModelScope.launch {
            runCatching { slug() }.getOrNull()?.let { slug ->
                github.pendingComments(slug, number).collect { p -> _state.update { it.copy(pending = p) } }
            }
        }
    }

    override fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching {
                val slug = slug()
                val gh = client()
                val pull = github.cached("pull:${slug.fullName}:$number", GhPull.serializer()) { gh.pull(slug, number) }
                _state.update { it.copy(pull = pull) }
                val comments = github.cached("comments:${slug.fullName}:$number", ListSerializer(GhComment.serializer())) { gh.comments(slug, number) }
                val files = github.cached("files:${slug.fullName}:$number", ListSerializer(GhPullFile.serializer())) { gh.pullFiles(slug, number) }
                _state.update { it.copy(comments = comments, files = files) }
                val ci = runCatching { gh.ciState(slug, pull.value.head.sha) }.getOrNull()
                _state.update { it.copy(ci = ci) }
            }.onFailure { e -> _state.update { it.copy(error = e.message) } }
            _state.update { it.copy(busy = false) }
        }
    }

    fun comment(body: String) {
        viewModelScope.launch {
            runCatching {
                val t = target()
                if (github.comment(t.credentialId, t.slug, number, body)) refresh() else message(context.getString(R.string.gh_comment_queued))
            }.onFailure { it.report() }
        }
    }

    fun merge(method: MergeMethod) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { client().merge(slug(), number, method) }
                .onSuccess { r -> message(if (r.merged) context.getString(R.string.gh_merged_ok) else r.message.orEmpty()); refresh() }
                .onFailure { it.report(); _state.update { s -> s.copy(busy = false) } }
        }
    }

    fun setOpen(open: Boolean) {
        viewModelScope.launch {
            runCatching { client().setPullOpen(slug(), number, open) }.onSuccess { refresh() }.onFailure { it.report() }
        }
    }
}

@Composable
fun PullScreen(onBack: () -> Unit, viewModel: PullViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    val pull = s.pull?.value
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            GhTopBar(
                "#${viewModel.number}",
                pull?.htmlUrl,
                onBack,
                menu = pull?.takeIf { it.mergedAt == null }?.let {
                    listOf(stringResource(if (it.isOpen) R.string.gh_close_pull else R.string.gh_reopen_pull) to { viewModel.setOpen(!it.isOpen) })
                }.orEmpty(),
            )
        },
        bottomBar = { if (tab == 0) CommentBox(enabled = pull != null, onSend = viewModel::comment) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OfflineBanner(s.pull)
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            if (pull == null) return@Column
            PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.gh_conversation)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.gh_files) + " (${pull.changedFiles})") })
            }
            if (tab == 0) Conversation(s, pull, viewModel::merge) else Files(s.files?.value.orEmpty())
        }
    }
}

@Composable
private fun Conversation(s: PullState, pull: GhPull, onMerge: (MergeMethod) -> Unit) {
    val ext = GitPockitTheme.extendedColors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(pull.title, style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                StateLabel(pull.isOpen, merged = pull.mergedAt != null, draft = pull.draft && pull.isOpen)
                Text("+${pull.additions} −${pull.deletions}", style = CodeTextStyle, color = ext.textSecondary)
            }
            Text(
                stringResource(R.string.gh_wants_merge, pull.user.login, pull.head.ref, pull.base.ref),
                style = MaterialTheme.typography.bodySmall,
                color = ext.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (pull.isOpen && pull.mergedAt == null) item { MergeCard(s, pull, onMerge) }
        item { CommentCard(pull.user.login, pull.createdAt, pull.body) }
        items(s.comments?.value.orEmpty(), key = { it.id }) { c -> CommentCard(c.user.login, c.createdAt, c.body) }
        items(s.pending, key = { "p${it.id}" }) { p -> CommentCard("…", null, p.body, pending = true) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MergeCard(s: PullState, pull: GhPull, onMerge: (MergeMethod) -> Unit) {
    val ext = GitPockitTheme.extendedColors
    var method by rememberSaveable { mutableStateOf(MergeMethod.MERGE) }
    var expanded by remember { mutableStateOf(false) }
    GpCard(Modifier.fillMaxWidth()) {
        s.ci?.let { ci ->
            val (text, color) = when (ci) {
                CiState.SUCCESS -> R.string.gh_ci_success to ext.added
                CiState.FAILURE -> R.string.gh_ci_failure to ext.removed
                CiState.PENDING -> R.string.gh_ci_pending to ext.modified
                CiState.NONE -> R.string.gh_ci_none to ext.textSecondary
            }
            Text(stringResource(text), color = color, style = MaterialTheme.typography.bodyMedium)
        }
        when (pull.mergeable) {
            false -> Text(stringResource(R.string.gh_not_mergeable), color = ext.removed, style = MaterialTheme.typography.bodySmall)
            null -> Text(stringResource(R.string.gh_checking_mergeable), color = ext.textSecondary, style = MaterialTheme.typography.bodySmall)
            true -> Unit
        }
        val labels = mapOf(
            MergeMethod.MERGE to R.string.gh_merge_commit,
            MergeMethod.SQUASH to R.string.gh_merge_squash,
            MergeMethod.REBASE to R.string.gh_merge_rebase,
        )
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.padding(top = 8.dp)) {
            OutlinedTextField(
                value = stringResource(labels.getValue(method)),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.gh_merge_method)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                labels.forEach { (m, label) -> DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { method = m; expanded = false }) }
            }
        }
        GradientButton(
            stringResource(R.string.gh_merge),
            onClick = { onMerge(method) },
            enabled = !s.busy && pull.mergeable != false && !pull.draft,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

@Composable
private fun Files(files: List<GhPullFile>) {
    val ext = GitPockitTheme.extendedColors
    var expanded by remember { mutableStateOf(setOf<String>()) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(files, key = { it.filename }) { f ->
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clickable { expanded = if (f.filename in expanded) expanded - f.filename else expanded + f.filename }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(f.filename, style = CodeTextStyle, maxLines = 2, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
                    Text("+${f.additions}", style = CodeTextStyle, color = ext.added)
                    Text(" −${f.deletions}", style = CodeTextStyle, color = ext.removed)
                }
                if (f.filename in expanded) PatchView(f.patch)
                HorizontalDivider(color = ext.hairline)
            }
        }
    }
}

/** Renders a unified-diff patch from the GitHub API with diff colours. */
@Composable
private fun PatchView(patch: String?) {
    val ext = GitPockitTheme.extendedColors
    if (patch == null) {
        Text(stringResource(R.string.diff_binary), color = ext.textSecondary, modifier = Modifier.padding(16.dp))
        return
    }
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        patch.lineSequence().take(2000).forEach { line ->
            val (bg, fg) = when {
                line.startsWith("@@") -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.secondary
                line.startsWith("+") -> ext.addedContainer to MaterialTheme.colorScheme.onSurface
                line.startsWith("-") -> ext.removedContainer to MaterialTheme.colorScheme.onSurface
                else -> Color.Transparent to MaterialTheme.colorScheme.onSurface
            }
            Text(line, style = CodeTextStyle.copy(fontSize = 12.sp), color = fg, softWrap = false, modifier = Modifier.background(bg).padding(horizontal = 8.dp))
        }
    }
}

// ---------------------------------------------------------------- new pull request

data class NewPullState(
    val loaded: Boolean = false,
    val head: String? = null,
    val bases: List<String> = emptyList(),
    val base: String = "",
    val title: String = "",
    val body: String = "",
    val draft: Boolean = false,
    val headOnRemote: Boolean = true,
    val busy: Boolean = false,
)

@HiltViewModel
class NewPullViewModel @Inject constructor(savedState: SavedStateHandle, ops: RepoOps, github: GitHubService) :
    GitHubScopedViewModel(savedState, ops, github) {
    private val _state = MutableStateFlow(NewPullState())
    val state: StateFlow<NewPullState> = _state
    private val _created = Channel<Int>(Channel.CONFLATED)
    val created = _created.receiveAsFlow()

    init { refresh() }

    override fun refresh() {
        viewModelScope.launch {
            runCatching {
                val slug = slug()
                val defaultBranch = runCatching { client().repo(slug).defaultBranch }.getOrNull()
                ops.read(repoId) { repo, git ->
                    val head = git.currentBranch()
                    val remoteBranches = git.branches(includeRemote = true)
                        .filter { it.isRemote && it.name.startsWith("${repo.remoteName}/") && !it.name.endsWith("/HEAD") }
                        .map { it.name.removePrefix("${repo.remoteName}/") }
                    val bases = remoteBranches.filter { it != head }.sortedBy { if (it == defaultBranch) "" else it }
                    NewPullState(
                        loaded = true,
                        head = head,
                        bases = bases,
                        base = defaultBranch?.takeIf { it in bases } ?: bases.firstOrNull().orEmpty(),
                        title = git.log(maxCount = 1).firstOrNull()?.title.orEmpty(),
                        headOnRemote = head in remoteBranches,
                    )
                }
            }.onSuccess { _state.value = it }.onFailure { _state.update { s -> s.copy(loaded = true) }; it.report() }
        }
    }

    fun update(t: (NewPullState) -> NewPullState) = _state.update(t)

    fun create() {
        val s = _state.value
        val head = s.head ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching {
                if (!s.headOnRemote) {
                    // The branch must exist on GitHub before a pull request can point at it.
                    ops.write(repoId) { repo, git ->
                        val result = git.push(remote = repo.remoteName, credentials = ops.credentialsFor(repo))
                        if (!result.isSuccess) throw IllegalStateException("Push failed: ${result.rejectedRefs.values.joinToString()}")
                        git.setUpstream(head, repo.remoteName)
                    }
                }
                client().createPull(slug(), s.title.trim(), s.body.trim(), head = head, base = s.base, draft = s.draft)
            }.onSuccess { _created.trySend(it.number) }.onFailure { it.report() }
            _state.update { it.copy(busy = false) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewPullScreen(onBack: () -> Unit, onCreated: (Int) -> Unit, viewModel: NewPullViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { viewModel.created.collect(onCreated) }
    val ext = GitPockitTheme.extendedColors
    var expanded by remember { mutableStateOf(false) }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = { GhTopBar(stringResource(R.string.gh_new_pull), null, onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!s.loaded) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                return@Column
            }
            OutlinedTextField(s.head.orEmpty(), {}, readOnly = true, label = { Text(stringResource(R.string.gh_head_branch)) }, textStyle = CodeTextStyle, modifier = Modifier.fillMaxWidth())
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = s.base,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.gh_base_branch)) },
                    textStyle = CodeTextStyle,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    s.bases.forEach { b -> DropdownMenuItem(text = { Text(b, style = CodeTextStyle) }, onClick = { viewModel.update { it.copy(base = b) }; expanded = false }) }
                }
            }
            if (s.base.isEmpty() || s.head == s.base) {
                Text(stringResource(R.string.gh_same_branch), color = ext.removed, style = MaterialTheme.typography.bodySmall)
            } else if (!s.headOnRemote) {
                Text(stringResource(R.string.gh_branch_not_pushed, s.head.orEmpty()), color = ext.modified, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(s.title, { v -> viewModel.update { it.copy(title = v) } }, label = { Text(stringResource(R.string.gh_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(s.body, { v -> viewModel.update { it.copy(body = v) } }, label = { Text(stringResource(R.string.gh_body)) }, minLines = 6, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = s.draft, onCheckedChange = { v -> viewModel.update { it.copy(draft = v) } })
                Text(stringResource(R.string.gh_draft_pr))
            }
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            GradientButton(
                stringResource(R.string.gh_create),
                onClick = viewModel::create,
                enabled = !s.busy && s.title.isNotBlank() && s.base.isNotEmpty() && s.head != null && s.head != s.base,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

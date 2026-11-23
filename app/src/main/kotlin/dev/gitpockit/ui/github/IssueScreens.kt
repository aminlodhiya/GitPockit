package dev.gitpockit.ui.github

import android.content.Context
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
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
import dev.gitpockit.core.data.db.PendingCommentEntity
import dev.gitpockit.core.designsystem.component.GradientButton
import dev.gitpockit.core.designsystem.theme.CodeTextStyle
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.github.GhComment
import dev.gitpockit.core.github.GhIssue
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

/** Shared top bar for GitHub detail screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GhTopBar(title: String, url: String?, onBack: () -> Unit, menu: List<Pair<String, () -> Unit>> = emptyList()) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
        actions = {
            url?.let { IconButton(onClick = { context.openUrl(it) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.gh_open_browser)) } }
            if (menu.isNotEmpty()) {
                IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more)) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    menu.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() }) }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

// ---------------------------------------------------------------- issue detail

data class IssueState(
    val issue: Loaded<GhIssue>? = null,
    val comments: Loaded<List<GhComment>>? = null,
    val pending: List<PendingCommentEntity> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class IssueViewModel @Inject constructor(
    savedState: SavedStateHandle,
    ops: RepoOps,
    github: GitHubService,
    @ApplicationContext private val context: Context,
) : GitHubScopedViewModel(savedState, ops, github) {
    val number: Int = checkNotNull(savedState["number"])
    private val _state = MutableStateFlow(IssueState())
    val state: StateFlow<IssueState> = _state

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
                val issue = github.cached("issue:${slug.fullName}:$number", GhIssue.serializer()) { gh.issue(slug, number) }
                val comments = github.cached("comments:${slug.fullName}:$number", ListSerializer(GhComment.serializer())) { gh.comments(slug, number) }
                _state.update { it.copy(issue = issue, comments = comments) }
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

    fun setOpen(open: Boolean) {
        viewModelScope.launch {
            runCatching { client().setIssueOpen(slug(), number, open) }
                .onSuccess { refresh() }
                .onFailure { it.report() }
        }
    }
}

@Composable
fun IssueScreen(onBack: () -> Unit, viewModel: IssueViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    val issue = s.issue?.value
    val ext = GitPockitTheme.extendedColors

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            GhTopBar(
                "#${viewModel.number}",
                issue?.htmlUrl,
                onBack,
                menu = issue?.let {
                    listOf(stringResource(if (it.isOpen) R.string.gh_close_issue else R.string.gh_reopen_issue) to { viewModel.setOpen(!it.isOpen) })
                }.orEmpty(),
            )
        },
        bottomBar = { CommentBox(enabled = issue != null, onSend = viewModel::comment) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OfflineBanner(s.issue)
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            if (issue == null) return@Column
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(issue.title, style = MaterialTheme.typography.titleLarge)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        StateLabel(issue.isOpen)
                        Text("${issue.user.login} · ${ghTime(issue.createdAt)}", style = MaterialTheme.typography.bodySmall, color = ext.textSecondary)
                    }
                    if (issue.labels.isNotEmpty()) {
                        Text(issue.labels.joinToString("  ·  ") { it.name }, style = CodeTextStyle, color = ext.textSecondary, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                item { CommentCard(issue.user.login, issue.createdAt, issue.body) }
                items(s.comments?.value.orEmpty(), key = { it.id }) { c -> CommentCard(c.user.login, c.createdAt, c.body) }
                items(s.pending, key = { "p${it.id}" }) { p -> CommentCard("…", null, p.body, pending = true) }
            }
        }
    }
}

// ---------------------------------------------------------------- new issue

@HiltViewModel
class NewIssueViewModel @Inject constructor(savedState: SavedStateHandle, ops: RepoOps, github: GitHubService) :
    GitHubScopedViewModel(savedState, ops, github) {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _created = Channel<Int>(Channel.CONFLATED)
    val created = _created.receiveAsFlow()

    override fun refresh() = Unit

    fun create(title: String, body: String) {
        _busy.value = true
        viewModelScope.launch {
            runCatching { client().createIssue(slug(), title.trim(), body.trim()) }
                .onSuccess { _created.trySend(it.number) }
                .onFailure { it.report() }
            _busy.value = false
        }
    }
}

@Composable
fun NewIssueScreen(onBack: () -> Unit, onCreated: (Int) -> Unit, viewModel: NewIssueViewModel = hiltViewModel()) {
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { viewModel.created.collect(onCreated) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = { GhTopBar(stringResource(R.string.gh_new_issue), null, onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.gh_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(body, { body = it }, label = { Text(stringResource(R.string.gh_body)) }, minLines = 8, modifier = Modifier.fillMaxWidth())
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            GradientButton(stringResource(R.string.gh_create), onClick = { viewModel.create(title, body) }, enabled = title.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth())
        }
    }
}


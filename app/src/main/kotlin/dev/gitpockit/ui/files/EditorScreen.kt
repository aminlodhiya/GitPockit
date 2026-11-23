package dev.gitpockit.ui.files

import android.content.Context
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mikepenz.markdown.m3.Markdown
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gitpockit.R
import dev.gitpockit.core.files.FileKind
import dev.gitpockit.core.files.RepoFiles
import dev.gitpockit.sync.RepoOps
import dev.gitpockit.ui.repo.RepoScopedViewModel
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class EditorState(
    val loaded: Boolean = false,
    /** Text to show; the editor view owns edits until they are saved or the view is recreated. */
    val text: String = "",
    val error: String? = null,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    /** Bumped whenever [text] is replaced from outside, so the editor view reloads it. */
    val version: Int = 0,
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    ops: RepoOps,
    @ApplicationContext private val context: Context,
) : RepoScopedViewModel(savedState, ops) {
    val path: String = checkNotNull(savedState["path"])
    val fileName: String = path.substringAfterLast('/')
    val isMarkdown: Boolean = RepoFiles.kindOf(fileName) == FileKind.MARKDOWN

    /** `.git/info/exclude` is the only file inside .git the editor may open. */
    private val allowGitInternals = path == RepoFiles.EXCLUDE_FILE
    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state

    init { load() }

    // Don't reload over the user's edits when the repo changes (e.g. a background sync).
    override fun refresh() = Unit

    private fun load() {
        viewModelScope.launch {
            runCatching {
                val files = RepoFiles(ops.repo(repoId).directory)
                withContext(Dispatchers.IO) { files.readText(path, allowGitInternals = allowGitInternals) }
            }.onSuccess { text -> _state.update { it.copy(loaded = true, text = text, version = it.version + 1) } }
                .onFailure { e -> _state.update { it.copy(loaded = true, error = e.message) } }
        }
    }

    fun markDirty() = _state.update { if (it.dirty) it else it.copy(dirty = true) }

    /** Keeps unsaved text across editor view recreation (rotation, preview toggle). */
    fun keepDraft(text: String) = _state.update { it.copy(text = text) }

    fun save(text: String, then: () -> Unit = {}) {
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            runCatching {
                val files = RepoFiles(ops.repo(repoId).directory)
                withContext(Dispatchers.IO) { files.writeText(path, text, allowGitInternals = allowGitInternals) }
            }.onSuccess {
                _state.update { it.copy(text = text, dirty = false, saving = false) }
                ops.notifyChanged(repoId) // Changes tab, home and file-change sync pick it up
                message(context.getString(R.string.editor_saved))
                then()
            }.onFailure { e ->
                _state.update { it.copy(saving = false) }
                e.report()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(onBack: () -> Unit, viewModel: EditorViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        TextMateSetup.ensure(context)
        ready = true
    }
    var preview by rememberSaveable { mutableStateOf(viewModel.isMarkdown) }
    var wrap by rememberSaveable { mutableStateOf(viewModel.isMarkdown) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<CodeEditor?>(null) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    fun currentText(): String = editor?.text?.toString() ?: s.text
    fun leave() = if (s.dirty) confirmExit = true else onBack()

    BackHandler(enabled = s.dirty) { confirmExit = true }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(viewModel.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (s.dirty) Box(Modifier.padding(start = 8.dp).size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    }
                },
                navigationIcon = { IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    if (viewModel.isMarkdown) {
                        IconButton(onClick = {
                            if (!preview) viewModel.keepDraft(currentText())
                            preview = !preview
                        }) {
                            Icon(if (preview) Icons.Default.Edit else Icons.Default.Visibility, stringResource(if (preview) R.string.editor_edit else R.string.editor_preview))
                        }
                    }
                    if (!preview) {
                        IconButton(onClick = { editor?.undo() }) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.editor_undo)) }
                        IconButton(onClick = { editor?.redo() }) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.editor_redo)) }
                        IconToggleButton(checked = searching, onCheckedChange = { searching = it; if (!it) editor?.searcher?.stopSearch() }) {
                            Icon(Icons.Default.Search, stringResource(R.string.editor_find))
                        }
                        IconToggleButton(checked = wrap, onCheckedChange = { wrap = it }) { Icon(Icons.AutoMirrored.Filled.WrapText, stringResource(R.string.editor_wrap)) }
                    }
                    IconButton(onClick = { viewModel.save(currentText()) }, enabled = s.dirty && !s.saving) {
                        Icon(Icons.Default.Save, stringResource(R.string.action_save))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (s.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                s.error != null -> Text(s.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                !s.loaded || !ready -> LinearProgressIndicator(Modifier.fillMaxWidth())
                preview -> Markdown(
                    content = s.text,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                )
                else -> {
                    if (searching) editor?.let { SearchBar(it) }
                    CodeEditorView(
                        text = s.text,
                        version = s.version,
                        fileName = viewModel.fileName,
                        dark = dark,
                        wrap = wrap,
                        onReady = { editor = it },
                        onEdited = viewModel::markDirty,
                        onDispose = { text -> if (s.dirty) viewModel.keepDraft(text) },
                    )
                }
            }
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.editor_unsaved_title)) },
            text = { Text(stringResource(R.string.editor_unsaved_message, viewModel.fileName)) },
            confirmButton = {
                TextButton(onClick = { confirmExit = false; viewModel.save(currentText(), then = onBack) }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmExit = false; onBack() }) { Text(stringResource(R.string.editor_discard), color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { confirmExit = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }
}

@Composable
private fun CodeEditorView(
    text: String,
    version: Int,
    fileName: String,
    dark: Boolean,
    wrap: Boolean,
    onReady: (CodeEditor) -> Unit,
    onEdited: () -> Unit,
    onDispose: (String) -> Unit,
) {
    var view by remember { mutableStateOf<CodeEditor?>(null) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            CodeEditor(ctx).apply {
                typefaceText = Typeface.MONOSPACE
                setTextSize(14f)
                isLineNumberEnabled = true
                setPinLineNumber(true)
                TextMateSetup.useTheme(dark)
                colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                val scope = TextMateSetup.scopeFor(fileName)
                    ?: if (TextMateSetup.isIgnoreFile(fileName)) "source.ini" else null
                setEditorLanguage(if (scope != null) TextMateLanguage.create(scope, true) else EmptyLanguage())
                setText(text)
                subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
                    if (event.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) onEdited()
                }
                view = this
                onReady(this)
            }
        },
        update = { editor ->
            if (editor.isWordwrap != wrap) editor.isWordwrap = wrap
            // Reload only when the text was replaced from outside (initial load).
            if (editor.tag != version) {
                if (editor.tag != null) editor.setText(text)
                editor.tag = version
            }
        },
    )
    DisposableEffect(Unit) {
        onDispose {
            view?.let { onDispose(it.text.toString()); it.release() }
        }
    }
}

@Composable
private fun SearchBar(editor: CodeEditor) {
    var query by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) {
        if (query.isEmpty()) editor.searcher.stopSearch()
        else editor.searcher.search(query, EditorSearcher.SearchOptions(EditorSearcher.SearchOptions.TYPE_NORMAL, true))
    }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.editor_find)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, null) } },
            )
            IconButton(onClick = { if (editor.searcher.hasQuery()) editor.searcher.gotoPrevious() }) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.editor_previous)) }
            IconButton(onClick = { if (editor.searcher.hasQuery()) editor.searcher.gotoNext() }) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.editor_next)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = replacement,
                onValueChange = { replacement = it },
                placeholder = { Text(stringResource(R.string.editor_replace_with)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { if (editor.searcher.hasQuery()) runCatching { editor.searcher.replaceCurrentMatch(replacement) } }) { Text(stringResource(R.string.editor_replace)) }
            TextButton(onClick = { if (editor.searcher.hasQuery()) editor.searcher.replaceAll(replacement) }) { Text(stringResource(R.string.editor_replace_all)) }
        }
    }
}

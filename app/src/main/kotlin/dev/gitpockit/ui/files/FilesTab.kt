package dev.gitpockit.ui.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gitpockit.R
import dev.gitpockit.core.designsystem.theme.CodeTextStyle
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.files.FileEntry
import dev.gitpockit.core.files.FileKind
import dev.gitpockit.core.files.RepoFiles
import dev.gitpockit.core.git.RepoStatus
import dev.gitpockit.sync.RepoOps
import dev.gitpockit.ui.common.ConfirmDialog
import dev.gitpockit.ui.common.InputDialog
import dev.gitpockit.ui.repo.RepoScopedViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Git status letter for a path: M, A (new), D, U (untracked), ! (conflict). */
private fun RepoStatus.codes(): Map<String, Char> = buildMap {
    (modified + changed).forEach { put(it, 'M') }
    added.forEach { put(it, 'A') }
    untracked.forEach { put(it, 'U') }
    (removed + missing).forEach { put(it, 'D') }
    conflicting.forEach { put(it, '!') }
}

data class FilesState(
    val loaded: Boolean = false,
    val dir: String = "",
    val entries: List<FileEntry> = emptyList(),
    val status: Map<String, Char> = emptyMap(),
    val root: File? = null,
)

@HiltViewModel
class FilesViewModel @Inject constructor(savedState: SavedStateHandle, ops: RepoOps) : RepoScopedViewModel(savedState, ops) {
    private val _state = MutableStateFlow(FilesState())
    val state: StateFlow<FilesState> = _state

    init { refresh() }

    override fun refresh() = open(_state.value.dir)

    fun open(dir: String) {
        viewModelScope.launch {
            runCatching {
                ops.read(repoId) { repo, git ->
                    val files = RepoFiles(repo.directory)
                    val entries = withContext(Dispatchers.IO) { files.list(dir) }
                    Triple(entries, git.status().codes(), files.root)
                }
            }.onSuccess { (entries, status, root) ->
                _state.update { FilesState(true, dir, entries, status, root) }
            }.onFailure { e ->
                if (dir.isNotEmpty()) open("") else { _state.update { it.copy(loaded = true) }; e.report() }
            }
        }
    }

    fun up() = open(_state.value.dir.substringBeforeLast('/', ""))

    fun newFile(name: String, onCreated: (String) -> Unit) = fileOp { files ->
        val path = files.createFile(_state.value.dir, name)
        withContext(Dispatchers.Main) { onCreated(path) }
    }

    fun newFolder(name: String) = fileOp { it.createFolder(_state.value.dir, name) }
    fun rename(path: String, name: String) = fileOp { it.rename(path, name) }
    fun delete(path: String) = fileOp { it.delete(path) }
    fun ignore(path: String, deviceOnly: Boolean) = fileOp { it.addIgnoreRule("/$path", deviceOnly) }

    fun trackLfs(extension: String) {
        viewModelScope.launch {
            runCatching { ops.write(repoId) { _, git -> git.lfsTrack("*.$extension") } }
                .onFailure { it.report() }
        }
    }

    private fun fileOp(block: suspend (RepoFiles) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val files = RepoFiles(ops.repo(repoId).directory)
                withContext(Dispatchers.IO) { block(files) }
            }.onFailure { it.report() }
            ops.notifyChanged(repoId) // refreshes this list, the Changes tab and the file watcher's view
        }
    }
}

/** Opens a file in another app (PDFs, office files, media…) via the FileProvider. */
fun Context.openWithOtherApp(file: File) {
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

@Composable
fun FilesTab(
    active: Boolean,
    onOpenEditor: (String) -> Unit,
    onOpenImage: (String) -> Unit,
    onFileHistory: (String) -> Unit,
    showMessage: (String) -> Unit,
    viewModel: FilesViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.messages.collect(showMessage) }
    // System back goes up one folder while this tab is showing.
    BackHandler(enabled = active && s.dir.isNotEmpty()) { viewModel.up() }
    val context = LocalContext.current
    var fabOpen by remember { mutableStateOf(false) }
    var creatingFile by remember { mutableStateOf(false) }
    var creatingFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<FileEntry?>(null) }
    var deleting by remember { mutableStateOf<FileEntry?>(null) }
    val ext = GitPockitTheme.extendedColors
    if (!s.loaded) return

    fun open(e: FileEntry) {
        when {
            e.isDirectory -> viewModel.open(e.path)
            e.kind == FileKind.IMAGE -> onOpenImage(e.path)
            e.kind == FileKind.BINARY -> s.root?.let { context.openWithOtherApp(File(it, e.path)) }
            else -> onOpenEditor(e.path)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Breadcrumbs(s.dir, viewModel::open)
            HorizontalDivider(color = ext.hairline)
            if (s.entries.isEmpty()) {
                Text(stringResource(R.string.files_empty), color = ext.textSecondary, modifier = Modifier.padding(24.dp))
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(s.entries, key = { it.path }) { e ->
                    val code = if (e.isDirectory) {
                        if (s.status.keys.any { it.startsWith(e.path + "/") }) '•' else null
                    } else {
                        s.status[e.path]
                    }
                    FileRow(
                        entry = e,
                        status = code,
                        onClick = { open(e) },
                        menu = buildList {
                            if (!e.isDirectory && e.kind != FileKind.BINARY) add(stringResource(R.string.files_open_with) to { s.root?.let { context.openWithOtherApp(File(it, e.path)) } })
                            if (!e.isDirectory) add(stringResource(R.string.file_history) to { onFileHistory(e.path) })
                            val ext = e.name.substringAfterLast('.', "")
                            if (!e.isDirectory && ext.isNotEmpty() && ext.length <= 10) {
                                add(stringResource(R.string.files_track_lfs, ext) to { viewModel.trackLfs(ext) })
                            }
                            add(stringResource(R.string.action_rename) to { renaming = e })
                            add(stringResource(R.string.files_ignore) to { viewModel.ignore(e.path + if (e.isDirectory) "/" else "", deviceOnly = false) })
                            add(stringResource(R.string.files_ignore_device) to { viewModel.ignore(e.path + if (e.isDirectory) "/" else "", deviceOnly = true) })
                            add(stringResource(R.string.action_delete) to { deleting = e })
                        },
                    )
                }
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (fabOpen) {
                SmallFab(Icons.AutoMirrored.Filled.NoteAdd, stringResource(R.string.files_new_file)) { fabOpen = false; creatingFile = true }
                SmallFab(Icons.Default.CreateNewFolder, stringResource(R.string.files_new_folder)) { fabOpen = false; creatingFolder = true }
            }
            FloatingActionButton(
                onClick = { fabOpen = !fabOpen },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Icon(Icons.Default.Add, stringResource(R.string.files_new)) }
        }
    }

    if (creatingFile) {
        InputDialog(
            title = stringResource(R.string.files_new_file),
            labels = listOf(stringResource(R.string.name)),
            confirmText = stringResource(R.string.action_create),
            onConfirm = { viewModel.newFile(it[0]) { path -> if (RepoFiles.kindOf(path) != FileKind.IMAGE) onOpenEditor(path) } },
            onDismiss = { creatingFile = false },
        )
    }
    if (creatingFolder) {
        InputDialog(
            title = stringResource(R.string.files_new_folder),
            labels = listOf(stringResource(R.string.name)),
            confirmText = stringResource(R.string.action_create),
            onConfirm = { viewModel.newFolder(it[0]) },
            onDismiss = { creatingFolder = false },
        )
    }
    renaming?.let { e ->
        InputDialog(
            title = stringResource(R.string.action_rename),
            labels = listOf(stringResource(R.string.name)),
            initial = listOf(e.name),
            onConfirm = { viewModel.rename(e.path, it[0]) },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { e ->
        ConfirmDialog(
            title = stringResource(R.string.files_delete_title, e.name),
            message = stringResource(if (e.isDirectory) R.string.files_delete_folder_message else R.string.files_delete_message),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = { viewModel.delete(e.path) },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun SmallFab(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small).padding(horizontal = 10.dp, vertical = 6.dp),
        )
        SmallFloatingActionButton(onClick = onClick, containerColor = MaterialTheme.colorScheme.secondaryContainer) { Icon(icon, label) }
    }
}

@Composable
private fun Breadcrumbs(dir: String, onOpen: (String) -> Unit) {
    val parts = if (dir.isEmpty()) emptyList() else dir.split('/')
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onOpen("") }) { Icon(Icons.Default.Home, stringResource(R.string.files_root), tint = MaterialTheme.colorScheme.primary) }
        parts.forEachIndexed { i, name ->
            Icon(Icons.Default.ChevronRight, null, tint = GitPockitTheme.extendedColors.textSecondary, modifier = Modifier.size(18.dp))
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (i == parts.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onOpen(parts.take(i + 1).joinToString("/")) }.padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun FileRow(entry: FileEntry, status: Char?, onClick: () -> Unit, menu: List<Pair<String, () -> Unit>>) {
    val ext = GitPockitTheme.extendedColors
    var open by remember { mutableStateOf(false) }
    val icon = when {
        entry.isDirectory -> Icons.Default.Folder
        entry.kind == FileKind.MARKDOWN -> Icons.Default.Description
        entry.kind == FileKind.IMAGE -> Icons.Default.Image
        entry.kind == FileKind.TEXT -> Icons.Default.Code
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
    val statusColor: Color = when (status) {
        'M' -> ext.modified
        'A', 'U' -> ext.added
        'D', '!' -> ext.removed
        else -> ext.textSecondary
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else ext.textSecondary)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            if (!entry.isDirectory) {
                Text(humanSize(entry.size), style = MaterialTheme.typography.bodySmall, color = ext.textSecondary)
            }
        }
        if (status == '•') {
            Box(Modifier.size(8.dp).background(ext.modified, CircleShape))
        } else if (status != null) {
            Text(status.toString(), style = CodeTextStyle, color = statusColor)
        }
        Box {
            IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                menu.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() }) }
            }
        }
    }
}

fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024))
}

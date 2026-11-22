package dev.gitpockit.ui.common

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.gitpockit.R
import dev.gitpockit.core.data.storage.StorageLocations
import dev.gitpockit.core.data.storage.TreeDocumentPaths
import dev.gitpockit.core.designsystem.component.GpCard
import dev.gitpockit.core.designsystem.component.GradientButton
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import java.io.File

/** Tracks "All files access" and re-checks it whenever the screen resumes (e.g. back from Settings). */
class StorageAccessState(val granted: Boolean, val request: () -> Unit)

@Composable
fun rememberStorageAccess(): StorageAccessState {
    val context = LocalContext.current
    val locations = remember { StorageLocations(context.applicationContext) }
    var granted by remember { mutableStateOf(locations.hasAllFilesAccess()) }
    LifecycleResumeEffect(Unit) {
        granted = locations.hasAllFilesAccess()
        onPauseOrDispose { }
    }
    val legacyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = locations.hasAllFilesAccess()
    }
    return StorageAccessState(granted) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            runCatching { context.startActivity(intent) }
                .onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            legacyLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }
}

@Composable
fun StoragePermissionCard(state: StorageAccessState, modifier: Modifier = Modifier) {
    if (state.granted) return
    GpCard(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.storage_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.storage_message),
            style = MaterialTheme.typography.bodyMedium,
            color = GitPockitTheme.extendedColors.textSecondary,
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
        )
        GradientButton(text = stringResource(R.string.storage_grant), onClick = state.request, icon = Icons.Default.FolderOpen)
    }
}

/**
 * Opens the system folder picker and converts the result to a real path.
 * [onPicked] receives null when the chosen location isn't on local storage.
 */
@Composable
fun rememberFolderPicker(onPicked: (File?) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()?.let { TreeDocumentPaths.toPath(it) }
        onPicked(path?.let(::File))
    }
    return { launcher.launch(null) }
}

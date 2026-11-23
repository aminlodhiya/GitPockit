package dev.gitpockit.ui.files

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.svg.SvgDecoder
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gitpockit.R
import dev.gitpockit.core.data.repository.RepoRepository
import dev.gitpockit.core.files.RepoFiles
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ImageViewModel @Inject constructor(savedState: SavedStateHandle, private val repos: RepoRepository) : ViewModel() {
    private val repoId: Long = checkNotNull(savedState["repoId"])
    val path: String = checkNotNull(savedState["path"])

    suspend fun file(): File? = repos.get(repoId)?.let { runCatching { RepoFiles(it.directory).resolve(path) }.getOrNull() }
}

/** Image viewer with pinch-zoom, drag, and double-tap to reset. Supports SVG and animated GIF. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageScreen(onBack: () -> Unit, viewModel: ImageViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var file by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(Unit) { file = viewModel.file() }
    val loader = remember {
        ImageLoader.Builder(context).components {
            add(SvgDecoder.Factory())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
        }.build()
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewModel.path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    file?.let { f -> IconButton(onClick = { context.openWithOtherApp(f) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.files_open_with)) } }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) },
            contentAlignment = Alignment.Center,
        ) {
            file?.let { f ->
                AsyncImage(
                    model = f,
                    imageLoader = loader,
                    contentDescription = f.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                )
            }
        }
    }
}

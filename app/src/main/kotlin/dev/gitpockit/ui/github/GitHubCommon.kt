package dev.gitpockit.ui.github

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mikepenz.markdown.m3.Markdown
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gitpockit.R
import dev.gitpockit.core.data.model.Credential
import dev.gitpockit.core.designsystem.component.GpCard
import dev.gitpockit.core.designsystem.component.GradientButton
import dev.gitpockit.core.designsystem.theme.CodeTextStyle
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.github.DeviceCode
import dev.gitpockit.core.github.DeviceFlow
import dev.gitpockit.github.GitHubService
import dev.gitpockit.github.Loaded
import dev.gitpockit.ui.credentials.copyToClipboard
import dev.gitpockit.ui.repo.relativeTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

fun Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun ghTime(iso: String?): String = iso?.let { runCatching { relativeTime(Instant.parse(it)) }.getOrNull() }.orEmpty()

@Composable
fun OfflineBanner(loaded: Loaded<*>?) {
    if (loaded?.fromCache != true) return
    Row(
        Modifier.fillMaxWidth().background(GitPockitTheme.extendedColors.modified.copy(alpha = 0.15f)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.CloudOff, null, tint = GitPockitTheme.extendedColors.modified, modifier = Modifier.size(18.dp))
        Text(
            stringResource(R.string.gh_offline, loaded.fetchedAt?.let { relativeTime(Instant.ofEpochMilli(it)) }.orEmpty()),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
fun MarkdownBody(text: String?) {
    if (text.isNullOrBlank()) {
        Text(stringResource(R.string.gh_no_description), style = MaterialTheme.typography.bodyMedium, color = GitPockitTheme.extendedColors.textSecondary)
    } else {
        Markdown(content = text, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun CommentCard(author: String, time: String?, body: String?, pending: Boolean = false) {
    GpCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(author, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(
                if (pending) " · " + stringResource(R.string.gh_pending) else " · " + ghTime(time),
                style = MaterialTheme.typography.labelMedium,
                color = if (pending) GitPockitTheme.extendedColors.modified else GitPockitTheme.extendedColors.textSecondary,
            )
        }
        Column(Modifier.padding(top = 6.dp)) { MarkdownBody(body) }
    }
}

@Composable
fun CommentBox(enabled: Boolean, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(8.dp).imePadding(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.gh_comment_hint)) },
            maxLines = 6,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onSend(text.trim()); text = "" }, enabled = enabled && text.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.gh_comment_send), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Icon and colour for a GitHub Actions status/conclusion. */
@Composable
fun RunStatusIcon(status: String?, conclusion: String?, modifier: Modifier = Modifier) {
    val ext = GitPockitTheme.extendedColors
    val (icon, color) = when {
        status == "queued" || status == "waiting" || status == "pending" -> Icons.Default.Schedule to ext.textSecondary
        status != "completed" -> Icons.Default.Sync to ext.modified
        conclusion == "success" -> Icons.Default.CheckCircle to ext.added
        conclusion == "skipped" || conclusion == "neutral" -> Icons.Default.RemoveCircleOutline to ext.textSecondary
        conclusion == "cancelled" -> Icons.Default.RemoveCircleOutline to ext.textSecondary
        else -> Icons.Default.Cancel to ext.removed
    }
    Icon(icon, null, tint = color, modifier = modifier.size(20.dp))
}

@Composable
fun statusLabel(status: String?, conclusion: String?): String = stringResource(
    when {
        status == "queued" || status == "waiting" || status == "pending" -> R.string.gh_status_queued
        status != "completed" -> R.string.gh_status_running
        conclusion == "success" -> R.string.gh_status_success
        conclusion == "cancelled" -> R.string.gh_status_cancelled
        conclusion == "skipped" || conclusion == "neutral" -> R.string.gh_status_skipped
        else -> R.string.gh_status_failure
    },
)

/** Coloured open/closed/merged/draft label. */
@Composable
fun StateLabel(open: Boolean, merged: Boolean = false, draft: Boolean = false) {
    val ext = GitPockitTheme.extendedColors
    val (text, color) = when {
        merged -> stringResource(R.string.gh_merged) to MaterialTheme.colorScheme.tertiary
        draft -> stringResource(R.string.gh_draft) to ext.textSecondary
        open -> stringResource(R.string.gh_open) to ext.added
        else -> stringResource(R.string.gh_closed) to ext.removed
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = Modifier.background(color, MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

// ---------------------------------------------------------------- accounts & sign-in

data class SignInState(
    val code: DeviceCode? = null,
    val waiting: Boolean = false,
    val error: String? = null,
    val signedIn: String? = null,
)

@HiltViewModel
class GitHubAccountViewModel @javax.inject.Inject constructor(private val github: GitHubService) : ViewModel() {
    val accounts: StateFlow<List<Credential>?> = github.accounts.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val canUseDeviceFlow = github.canUseDeviceFlow
    private val _state = MutableStateFlow(SignInState())
    val state: StateFlow<SignInState> = _state
    private var job: Job? = null
    private var flow: DeviceFlow? = null

    fun startDeviceFlow() {
        job?.cancel()
        _state.value = SignInState(waiting = true)
        job = viewModelScope.launch {
            runCatching {
                val (f, code) = github.startSignIn()
                flow = f
                _state.update { it.copy(code = code) }
                github.finishSignIn(f, code)
            }.onSuccess { login -> _state.value = SignInState(signedIn = login) }
                .onFailure { e -> _state.update { it.copy(waiting = false, error = e.message) } }
        }
    }

    fun signInWithToken(token: String) {
        _state.value = SignInState(waiting = true)
        viewModelScope.launch {
            runCatching { github.signInWithToken(token) }
                .onSuccess { login -> _state.value = SignInState(signedIn = login) }
                .onFailure { e -> _state.value = SignInState(error = e.message) }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = SignInState()
    }

    fun remove(id: Long) = viewModelScope.launch { github.removeAccount(id) }
}

/** Accounts list with sign-in buttons; used in Settings and wherever an account is missing. */
@Composable
fun GitHubAccountCard(modifier: Modifier = Modifier, compact: Boolean = false, viewModel: GitHubAccountViewModel = hiltViewModel()) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ext = GitPockitTheme.extendedColors
    var tokenDialog by remember { mutableStateOf(false) }
    var deviceDialog by remember { mutableStateOf(false) }

    GpCard(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.gh_accounts), style = MaterialTheme.typography.titleMedium)
        if (!compact) Text(stringResource(R.string.gh_accounts_desc), style = MaterialTheme.typography.bodySmall, color = ext.textSecondary)
        accounts.orEmpty().forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.gh_signed_in, a.username), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { viewModel.remove(a.id) }) { Text(stringResource(R.string.gh_sign_out), color = MaterialTheme.colorScheme.error) }
            }
        }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (viewModel.canUseDeviceFlow) {
                GradientButton(
                    stringResource(R.string.gh_sign_in),
                    onClick = { deviceDialog = true; viewModel.startDeviceFlow() },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(stringResource(R.string.gh_no_client_id), style = MaterialTheme.typography.bodySmall, color = ext.textSecondary)
            }
            OutlinedButton(onClick = { tokenDialog = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.gh_sign_in_token)) }
        }
    }

    if (deviceDialog) {
        AlertDialog(
            onDismissRequest = { deviceDialog = false; viewModel.cancel() },
            title = { Text(stringResource(R.string.gh_device_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val code = s.code
                    if (s.signedIn != null) {
                        Text(stringResource(R.string.gh_signed_in, s.signedIn!!), color = ext.added)
                    } else if (code != null) {
                        Text(stringResource(R.string.gh_device_steps), style = MaterialTheme.typography.bodyMedium)
                        Text(code.userCode, style = CodeTextStyle.copy(fontSize = MaterialTheme.typography.headlineMedium.fontSize), color = MaterialTheme.colorScheme.primary)
                        GradientButton(
                            stringResource(R.string.gh_device_open),
                            onClick = { context.copyToClipboard("GitHub code", code.userCode); context.openUrl(code.verificationUri) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(R.string.gh_device_waiting), style = MaterialTheme.typography.bodySmall, color = ext.textSecondary)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else if (s.waiting) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { deviceDialog = false; if (s.signedIn == null) viewModel.cancel() }) {
                    Text(stringResource(if (s.signedIn != null) R.string.action_done else R.string.action_cancel))
                }
            },
        )
    }

    if (tokenDialog) {
        var token by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { tokenDialog = false },
            title = { Text(stringResource(R.string.gh_sign_in_token)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text(stringResource(R.string.gh_token_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.gh_token_help), style = MaterialTheme.typography.bodySmall, color = ext.textSecondary)
                    if (s.waiting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    s.signedIn?.let { Text(stringResource(R.string.gh_signed_in, it), color = ext.added) }
                }
            },
            confirmButton = {
                if (s.signedIn != null) {
                    TextButton(onClick = { tokenDialog = false; viewModel.cancel() }) { Text(stringResource(R.string.action_done)) }
                } else {
                    TextButton(onClick = { viewModel.signInWithToken(token) }, enabled = token.isNotBlank() && !s.waiting) { Text(stringResource(R.string.action_save)) }
                }
            },
            dismissButton = { TextButton(onClick = { tokenDialog = false; viewModel.cancel() }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

package dev.gitpockit.security

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gitpockit.R
import dev.gitpockit.core.designsystem.component.GpCard
import dev.gitpockit.core.designsystem.component.GradientButton
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.security.AppLockPolicy
import dev.gitpockit.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Full-screen cover shown while locked; asks for the fingerprint right away. */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val title = stringResource(R.string.lock_prompt_title)
    val subtitle = stringResource(R.string.lock_prompt_subtitle)
    val prompt: () -> Unit = {
        context.findFragmentActivity()?.let { Biometric.authenticate(it, title, subtitle, onSuccess = onUnlocked) }
    }
    LaunchedEffect(Unit) { prompt() }
    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Swallow every touch so nothing underneath can be used while locked.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(32.dp)) {
            Box(
                Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            }
            Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.titleLarge)
            GradientButton(stringResource(R.string.lock_unlock), onClick = prompt, icon = Icons.Default.Fingerprint)
        }
    }
}

@HiltViewModel
class AppLockSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val appLock: AppLock,
) : ViewModel() {
    fun beginVerification() = appLock.beginVerification()
    fun endVerification() = appLock.endVerification()

    val enabled: StateFlow<Boolean> = settings.appLockEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val timeout: StateFlow<Int> = settings.appLockTimeoutMinutes.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    fun setEnabled(on: Boolean) = viewModelScope.launch { settings.setAppLockEnabled(on) }
    fun setTimeout(minutes: Int) = viewModelScope.launch { settings.setAppLockTimeoutMinutes(minutes) }
}

/** Settings card: switch (confirmed with the fingerprint) and "lock after" choice. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLockCard(viewModel: AppLockSettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val timeout by viewModel.timeout.collectAsStateWithLifecycle()
    var available by remember { mutableStateOf(Biometric.isAvailable(context)) }
    LifecycleResumeEffect(Unit) {
        available = Biometric.isAvailable(context)
        onPauseOrDispose { }
    }
    val ext = GitPockitTheme.extendedColors
    val confirmTitle = stringResource(R.string.lock_confirm_title)
    val confirmSubtitle = stringResource(R.string.lock_prompt_subtitle)

    GpCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.lock_section), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.lock_enable), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.lock_enable_desc), style = MaterialTheme.typography.bodySmall, color = ext.textSecondary)
            }
            Switch(
                checked = enabled,
                enabled = available || enabled,
                onCheckedChange = { on ->
                    // Turning it on or off needs a successful check, so it can't lock you out or be removed by someone else.
                    context.findFragmentActivity()?.let { activity ->
                        viewModel.beginVerification()
                        Biometric.authenticate(
                            activity,
                            confirmTitle,
                            confirmSubtitle,
                            onSuccess = { viewModel.endVerification(); viewModel.setEnabled(on) },
                            onFailure = { viewModel.endVerification() },
                        )
                    }
                },
            )
        }
        if (!available && !enabled) {
            Text(stringResource(R.string.lock_unavailable), style = MaterialTheme.typography.bodySmall, color = ext.modified)
            TextButton(onClick = {
                val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Settings.ACTION_BIOMETRIC_ENROLL else Settings.ACTION_SECURITY_SETTINGS
                runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text(stringResource(R.string.lock_open_settings)) }
        }
        if (enabled) {
            var expanded by remember { mutableStateOf(false) }
            @Composable
            fun label(m: Int) = if (m == 0) stringResource(R.string.lock_immediately) else stringResource(R.string.lock_after_minutes, m)
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.padding(top = 8.dp)) {
                OutlinedTextField(
                    value = label(timeout),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.lock_after)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    AppLockPolicy.TIMEOUT_CHOICES_MINUTES.forEach { m ->
                        DropdownMenuItem(text = { Text(label(m)) }, onClick = { viewModel.setTimeout(m); expanded = false })
                    }
                }
            }
            Text(stringResource(R.string.lock_sync_note), style = MaterialTheme.typography.bodySmall, color = ext.textSecondary, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

package dev.gitpockit.ui.repo

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.gitpockit.R
import dev.gitpockit.background.SystemSettings
import dev.gitpockit.core.data.model.AppTrigger
import dev.gitpockit.core.data.model.ConflictStrategy
import dev.gitpockit.core.data.model.SyncMode
import dev.gitpockit.core.data.model.SyncSettings
import dev.gitpockit.core.designsystem.component.GpCard
import dev.gitpockit.core.designsystem.theme.CodeTextStyle
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.sync.SyncEngine
import kotlinx.coroutines.delay

private val INTERVALS = listOf(0, 15, 30, 60, 180, 360, 720, 1440)

/** Picker value meaning "daily at specific times" rather than an interval. */
private const val AT_TIMES = -1

@Composable
private fun intervalLabel(minutes: Int): String = when {
    minutes == AT_TIMES -> stringResource(R.string.sync_at_times)
    minutes == 0 -> stringResource(R.string.sync_schedule_off)
    minutes < 60 -> stringResource(R.string.sync_every_minutes, minutes)
    else -> stringResource(R.string.sync_every_hours, minutes / 60)
}

/** Asks for the notification permission (Android 13+) the first time an automatic trigger is enabled. */
@Composable
private fun rememberNotificationPrompt(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !SystemSettings.canPostNotifications(context)) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
fun SyncSettingsCard(settings: SyncSettings, onChange: (SyncSettings) -> Unit, onOpenLog: () -> Unit) {
    val ext = GitPockitTheme.extendedColors
    val askNotifications = rememberNotificationPrompt()
    val update: (SyncSettings) -> Unit = { new ->
        if (new.intervalMinutes > 0 || new.onFileChange || new.dailyTimes.isNotEmpty()) askNotifications()
        onChange(new)
    }
    GpCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sync_section), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenLog) { Text(stringResource(R.string.sync_log)) }
        }
        Picker(
            label = stringResource(R.string.sync_mode),
            options = SyncMode.entries,
            selected = settings.mode,
            text = { stringResource(if (it == SyncMode.FULL) R.string.sync_mode_full else R.string.sync_mode_pull) },
            onSelect = { update(settings.copy(mode = it)) },
        )
        Picker(
            label = stringResource(R.string.sync_schedule),
            options = INTERVALS + AT_TIMES,
            selected = if (settings.dailyTimes.isNotEmpty()) AT_TIMES else settings.intervalMinutes.takeIf { it in INTERVALS } ?: 0,
            text = { intervalLabel(it) },
            onSelect = { choice ->
                update(
                    if (choice == AT_TIMES) {
                        settings.copy(intervalMinutes = 0, dailyTimes = settings.dailyTimes.ifEmpty { listOf(DEFAULT_TIME) })
                    } else {
                        settings.copy(intervalMinutes = choice, dailyTimes = emptyList())
                    },
                )
            },
        )
        if (settings.dailyTimes.isNotEmpty()) {
            DailyTimesEditor(settings.dailyTimes) { times -> update(settings.copy(dailyTimes = times)) }
        }
        SwitchRow(stringResource(R.string.sync_on_change), stringResource(R.string.sync_on_change_desc), settings.onFileChange) {
            update(settings.copy(onFileChange = it))
        }
        SwitchRow(stringResource(R.string.sync_wifi_only), stringResource(R.string.sync_wifi_only_desc), settings.wifiOnly) {
            update(settings.copy(wifiOnly = it))
        }
        SwitchRow(stringResource(R.string.pull_rebase), stringResource(R.string.pull_rebase_desc), settings.pullRebase) {
            onChange(settings.copy(pullRebase = it))
        }
        if (settings.mode == SyncMode.FULL) {
            Picker(
                label = stringResource(R.string.conflict_strategy),
                options = ConflictStrategy.entries,
                selected = settings.conflictStrategy,
                text = {
                    stringResource(
                        when (it) {
                            ConflictStrategy.ASK -> R.string.strategy_ask
                            ConflictStrategy.KEEP_LOCAL -> R.string.strategy_local
                            ConflictStrategy.KEEP_REMOTE -> R.string.strategy_remote
                            ConflictStrategy.KEEP_BOTH -> R.string.strategy_both
                        },
                    )
                },
                onSelect = { update(settings.copy(conflictStrategy = it)) },
            )
            var template by remember { mutableStateOf(settings.commitTemplate ?: SyncEngine.DEFAULT_TEMPLATE) }
            LaunchedEffect(template) {
                delay(600)
                val value = template.takeIf { it.isNotBlank() && it != SyncEngine.DEFAULT_TEMPLATE }
                if (value != settings.commitTemplate) onChange(settings.copy(commitTemplate = value))
            }
            OutlinedTextField(
                value = template,
                onValueChange = { template = it },
                label = { Text(stringResource(R.string.commit_template)) },
                supportingText = { Text(stringResource(R.string.commit_template_help)) },
                textStyle = CodeTextStyle,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        Text(
            stringResource(R.string.settings_tile_widget),
            style = MaterialTheme.typography.bodySmall,
            color = ext.textSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private const val DEFAULT_TIME = 9 * 60

private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DailyTimesEditor(times: List<Int>, onChange: (List<Int>) -> Unit) {
    val context = LocalContext.current
    val ext = GitPockitTheme.extendedColors
    var adding by remember { mutableStateOf(false) }
    val alarms = remember { context.getSystemService(AlarmManager::class.java) }
    fun exactAllowed() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
    var exactOk by remember { mutableStateOf(exactAllowed()) }
    LifecycleResumeEffect(Unit) {
        exactOk = exactAllowed()
        onPauseOrDispose { }
    }

    Text(stringResource(R.string.sync_times_label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        times.forEach { minutes ->
            val label = LocalTime.of(minutes / 60, minutes % 60).format(timeFormat)
            InputChip(
                selected = false,
                onClick = {},
                label = { Text(label) },
                trailingIcon = {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.sync_time_remove, label),
                        modifier = Modifier.size(18.dp).clickable { onChange(times - minutes) },
                    )
                },
            )
        }
        AssistChip(
            onClick = { adding = true },
            label = { Text(stringResource(R.string.sync_time_add)) },
            leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) },
        )
    }
    if (!exactOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.exact_alarm_off), style = MaterialTheme.typography.bodySmall, color = ext.modified, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }) { Text(stringResource(R.string.exact_alarm_allow)) }
        }
    }

    if (adding) {
        val state = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.sync_time_pick)) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    adding = false
                    onChange((times + (state.hour * 60 + state.minute)).distinct().sorted())
                }) { Text(stringResource(R.string.action_add)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
fun AppTriggersCard(
    triggers: List<AppTrigger>,
    repoId: Long,
    onSet: (AppTrigger) -> Unit,
    onRemove: (String) -> Unit,
) {
    val context = LocalContext.current
    val ext = GitPockitTheme.extendedColors
    var picking by remember { mutableStateOf(false) }
    var serviceOn by remember { mutableStateOf(SystemSettings.isAppTriggerServiceEnabled(context)) }
    LifecycleResumeEffect(Unit) {
        serviceOn = SystemSettings.isAppTriggerServiceEnabled(context)
        onPauseOrDispose { }
    }
    GpCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.app_triggers), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.app_triggers_desc), style = MaterialTheme.typography.bodySmall, color = ext.textSecondary)
        if (triggers.isNotEmpty() && !serviceOn) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.accessibility_off), style = MaterialTheme.typography.bodySmall, color = ext.removed, modifier = Modifier.weight(1f))
                TextButton(onClick = { SystemSettings.openAccessibilitySettings(context) }) { Text(stringResource(R.string.accessibility_enable)) }
            }
        }
        triggers.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(SystemSettings.appLabel(context, t.packageName), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { onRemove(t.packageName) }) { Icon(Icons.Default.Close, stringResource(R.string.action_remove)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = t.onOpen, onCheckedChange = { onSet(t.copy(onOpen = it)) })
                Text(stringResource(R.string.app_trigger_open), modifier = Modifier.padding(end = 16.dp))
                Checkbox(checked = t.onClose, onCheckedChange = { onSet(t.copy(onClose = it)) })
                Text(stringResource(R.string.app_trigger_close))
            }
        }
        OutlinedButton(onClick = { picking = true }, modifier = Modifier.padding(top = 8.dp)) {
            Icon(Icons.Default.Add, null)
            Text(stringResource(R.string.app_trigger_add), modifier = Modifier.padding(start = 6.dp))
        }
    }
    if (picking) {
        val apps = remember { SystemSettings.launchableApps(context) }
        val existing = triggers.map { it.packageName }.toSet()
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.app_trigger_pick)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(apps.filter { it.first !in existing }, key = { it.first }) { (pkg, label) ->
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                picking = false
                                onSet(AppTrigger(pkg, repoId, onOpen = true, onClose = true))
                                if (!serviceOn) SystemSettings.openAccessibilitySettings(context)
                            }.padding(vertical = 10.dp),
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            Text(pkg, style = CodeTextStyle, color = ext.textSecondary)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = GitPockitTheme.extendedColors.textSecondary)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Picker(label: String, options: List<T>, selected: T, text: @Composable (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        OutlinedTextField(
            value = text(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(text(o)) }, onClick = { onSelect(o); expanded = false }) }
        }
    }
}

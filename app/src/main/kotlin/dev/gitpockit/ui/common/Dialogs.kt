package dev.gitpockit.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import dev.gitpockit.core.git.ResetMode
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.gitpockit.R

@Composable
fun ConfirmDialog(
    title: String,
    message: String?,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = message?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmText, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Lets the user pick soft / mixed / hard, with the consequence of each spelled out. */
@Composable
fun ResetDialog(branch: String, target: String, onReset: (ResetMode) -> Unit, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf(ResetMode.MIXED) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reset_title, branch, target)) },
        text = {
            Column {
                listOf(
                    ResetMode.SOFT to R.string.reset_soft,
                    ResetMode.MIXED to R.string.reset_mixed,
                    ResetMode.HARD to R.string.reset_hard,
                ).forEach { (m, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = mode == m, onClick = { mode = m }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == m, onClick = { mode = m })
                        Text(stringResource(label))
                    }
                }
                if (mode == ResetMode.HARD) {
                    Text(
                        stringResource(R.string.reset_hard_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onReset(mode); onDismiss() }) {
                Text(
                    stringResource(R.string.action_reset),
                    color = if (mode == ResetMode.HARD) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Dialog with one or more text fields; [onConfirm] gets the values in order. */
@Composable
fun InputDialog(
    title: String,
    labels: List<String>,
    initial: List<String> = labels.map { "" },
    confirmText: String = stringResource(R.string.action_save),
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
    /** Indexes of fields that may be left empty. */
    optional: Set<Int> = emptySet(),
) {
    var values by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                labels.forEachIndexed { i, label ->
                    OutlinedTextField(
                        value = values[i],
                        onValueChange = { v -> values = values.toMutableList().also { it[i] = v } },
                        label = { Text(label) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                extra?.invoke()
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(values.map { it.trim() }); onDismiss() },
                enabled = values.withIndex().all { (i, v) -> i in optional || v.isNotBlank() },
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

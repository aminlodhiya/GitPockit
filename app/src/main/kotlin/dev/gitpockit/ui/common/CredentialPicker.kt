package dev.gitpockit.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.gitpockit.R
import dev.gitpockit.core.data.model.Credential
import dev.gitpockit.core.data.model.CredentialType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialPicker(
    credentials: List<Credential>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onAddNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = credentials.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected?.let { describe(it) } ?: stringResource(R.string.credential_none),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.credential)) },
            leadingIcon = { Icon(if (selected?.type == CredentialType.SSH) Icons.Default.Key else Icons.Default.Lock, null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.credential_none)) }, onClick = { onSelect(null); expanded = false })
            credentials.forEach { c ->
                DropdownMenuItem(
                    text = { Text(describe(c)) },
                    leadingIcon = { Icon(if (c.type == CredentialType.SSH) Icons.Default.Key else Icons.Default.Lock, null) },
                    onClick = { onSelect(c.id); expanded = false },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.credential_add)) },
                leadingIcon = { Icon(Icons.Default.Add, null) },
                onClick = { expanded = false; onAddNew() },
            )
        }
    }
}

@Composable
fun describe(c: Credential): String = when (c.type) {
    CredentialType.HTTPS -> "${c.label} · ${stringResource(R.string.cred_type_https, c.username)}"
    CredentialType.SSH -> "${c.label} · ${stringResource(R.string.cred_type_ssh)}"
}

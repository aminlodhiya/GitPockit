package dev.gitpockit.ui.credentials

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gitpockit.R
import dev.gitpockit.core.data.model.Credential
import dev.gitpockit.core.data.model.CredentialType
import dev.gitpockit.core.data.repository.CredentialRepository
import dev.gitpockit.core.designsystem.component.EmptyState
import dev.gitpockit.core.designsystem.component.GpCard
import dev.gitpockit.core.designsystem.component.GradientButton
import dev.gitpockit.core.designsystem.theme.CodeTextStyle
import dev.gitpockit.core.designsystem.theme.GitPockitTheme
import dev.gitpockit.core.git.SshKeys
import dev.gitpockit.ui.common.ConfirmDialog
import dev.gitpockit.ui.common.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

fun Context.copyToClipboard(label: String, text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
}

fun Context.shareText(text: String) {
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
}

// ---------------------------------------------------------------- list

@HiltViewModel
class CredentialsViewModel @Inject constructor(private val repo: CredentialRepository) : ViewModel() {
    val credentials: StateFlow<List<Credential>?> = repo.credentials.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialsScreen(onBack: () -> Unit, onAdd: () -> Unit, viewModel: CredentialsViewModel = hiltViewModel()) {
    val list by viewModel.credentials.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<Credential?>(null) }
    val context = LocalContext.current
    val ext = GitPockitTheme.extendedColors

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.credentials_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.credential_add_title)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        val items = list ?: return@Scaffold
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Default.Key, stringResource(R.string.credentials_empty_title), stringResource(R.string.credentials_empty_message))
            }
            return@Scaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.id }) { c ->
                GpCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when (c.type) {
                                CredentialType.SSH -> Icons.Default.Key
                                CredentialType.GPG -> Icons.Default.VerifiedUser
                                CredentialType.HTTPS -> Icons.Default.Lock
                            },
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(c.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                when (c.type) {
                                    CredentialType.SSH -> c.fingerprint.orEmpty()
                                    CredentialType.GPG -> "${c.username}\n${c.fingerprint.orEmpty().chunked(4).joinToString(" ")}"
                                    CredentialType.HTTPS -> stringResource(R.string.cred_type_https, c.username)
                                },
                                style = CodeTextStyle,
                                color = ext.textSecondary,
                            )
                        }
                        IconButton(onClick = { toDelete = c }) { Icon(Icons.Default.Delete, stringResource(R.string.action_delete)) }
                    }
                    c.publicKey?.let { key ->
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { context.copyToClipboard("SSH public key", key) }) {
                                Icon(Icons.Default.ContentCopy, null)
                                Text(stringResource(R.string.ssh_public_key), Modifier.padding(start = 6.dp))
                            }
                            IconButton(onClick = { context.shareText(key) }) { Icon(Icons.Default.Share, stringResource(R.string.action_share)) }
                        }
                    }
                }
            }
        }
    }
    toDelete?.let { c ->
        ConfirmDialog(
            title = stringResource(R.string.credential_delete_title, c.label),
            message = stringResource(R.string.credential_delete_message),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = { viewModel.delete(c.id) },
            onDismiss = { toDelete = null },
        )
    }
}

// ---------------------------------------------------------------- add

data class AddCredentialState(
    val label: String = "",
    val username: String = "",
    val token: String = "",
    val sshType: SshKeys.Type = SshKeys.Type.ED25519,
    val privateKey: String = "",
    val passphrase: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    /** Set after saving; for generated keys we first show the public key. */
    val savedId: Long? = null,
    val generatedPublicKey: String? = null,
    val gpgName: String = "",
    val gpgEmail: String = "",
    /** Public key shown after generating: SSH or GPG. */
    val generatedIsGpg: Boolean = false,
)

@HiltViewModel
class AddCredentialViewModel @Inject constructor(
    private val repo: CredentialRepository,
    settings: dev.gitpockit.data.SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AddCredentialState())
    val state: StateFlow<AddCredentialState> = _state

    init {
        // Prefill the GPG identity with the default commit author.
        viewModelScope.launch {
            val author = settings.author.first()
            _state.update { it.copy(gpgName = it.gpgName.ifEmpty { author.name }, gpgEmail = it.gpgEmail.ifEmpty { author.email }) }
        }
    }

    fun generateGpg() = save { s ->
        val cred = repo.generateGpg(s.label, s.gpgName.trim(), s.gpgEmail.trim())
        _state.update { it.copy(generatedPublicKey = cred.publicKey, generatedIsGpg = true) }
        cred.id
    }

    fun importGpg() = save { s -> repo.importGpg(s.label, s.privateKey, s.passphrase).also { id -> _done.value = id } }
    private val _done = MutableStateFlow<Long?>(null)
    val done: StateFlow<Long?> = _done

    fun update(t: (AddCredentialState) -> AddCredentialState) = _state.update { t(it).copy(error = null) }

    fun saveHttps() = save { repo.addHttps(it.label, it.username, it.token).also { id -> _done.value = id } }

    fun importSsh() = save { repo.importSsh(it.label, it.privateKey, it.passphrase).also { id -> _done.value = id } }

    fun generateSsh() = save { s ->
        val cred = repo.generateSsh(s.label, s.sshType)
        _state.update { it.copy(generatedPublicKey = cred.publicKey) }
        cred.id
    }

    fun finish() { _done.value = _state.value.savedId }

    private fun save(block: suspend (AddCredentialState) -> Long) {
        val s = _state.value
        if (s.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching { block(s) }
                .onSuccess { id -> _state.update { it.copy(busy = false, savedId = id) } }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.userMessage()) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCredentialScreen(onBack: () -> Unit, onSaved: (Long) -> Unit, viewModel: AddCredentialViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val done by viewModel.done.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(done) { done?.let(onSaved) }
    var tab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val ext = GitPockitTheme.extendedColors

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.credential_add_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (s.generatedPublicKey == null) {
                PrimaryScrollableTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background, edgePadding = 8.dp) {
                    listOf(
                        R.string.cred_tab_https,
                        R.string.cred_tab_ssh_generate,
                        R.string.cred_tab_ssh_import,
                        R.string.cred_tab_gpg_generate,
                        R.string.cred_tab_gpg_import,
                    ).forEachIndexed { i, label ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(label), maxLines = 1) })
                    }
                }
            }
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val generated = s.generatedPublicKey
                if (generated != null) {
                    Text(stringResource(R.string.ssh_public_key), style = MaterialTheme.typography.titleMedium)
                    GpCard(Modifier.fillMaxWidth()) {
                        SelectionContainer { Text(generated, style = CodeTextStyle) }
                    }
                    Text(
                        stringResource(if (s.generatedIsGpg) R.string.gpg_public_key_help else R.string.ssh_public_key_help),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ext.textSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { context.copyToClipboard("SSH public key", generated) }) {
                            Icon(Icons.Default.ContentCopy, null)
                            Text(stringResource(R.string.action_copy), Modifier.padding(start = 6.dp))
                        }
                        OutlinedButton(onClick = { context.shareText(generated) }) {
                            Icon(Icons.Default.Share, null)
                            Text(stringResource(R.string.action_share), Modifier.padding(start = 6.dp))
                        }
                    }
                    GradientButton(stringResource(R.string.action_done), onClick = viewModel::finish, modifier = Modifier.fillMaxWidth())
                    return@Column
                }

                OutlinedTextField(
                    value = s.label,
                    onValueChange = { v -> viewModel.update { it.copy(label = v) } },
                    label = { Text(stringResource(R.string.cred_label)) },
                    placeholder = { Text(stringResource(R.string.cred_label_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when (tab) {
                    0 -> {
                        OutlinedTextField(
                            value = s.username,
                            onValueChange = { v -> viewModel.update { it.copy(username = v) } },
                            label = { Text(stringResource(R.string.auth_username)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = s.token,
                            onValueChange = { v -> viewModel.update { it.copy(token = v) } },
                            label = { Text(stringResource(R.string.auth_token)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(R.string.auth_token_help), style = MaterialTheme.typography.bodySmall, color = ext.textSecondary)
                    }
                    1 -> {
                        Text(stringResource(R.string.ssh_type), style = MaterialTheme.typography.titleSmall)
                        val types = listOf(SshKeys.Type.ED25519 to R.string.ssh_type_ed25519, SshKeys.Type.RSA to R.string.ssh_type_rsa)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            types.forEachIndexed { i, (type, label) ->
                                SegmentedButton(
                                    selected = s.sshType == type,
                                    onClick = { viewModel.update { it.copy(sshType = type) } },
                                    shape = SegmentedButtonDefaults.itemShape(i, types.size),
                                ) { Text(stringResource(label), maxLines = 1) }
                            }
                        }
                    }
                    3 -> {
                        OutlinedTextField(
                            value = s.gpgName,
                            onValueChange = { v -> viewModel.update { it.copy(gpgName = v) } },
                            label = { Text(stringResource(R.string.gpg_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = s.gpgEmail,
                            onValueChange = { v -> viewModel.update { it.copy(gpgEmail = v) } },
                            label = { Text(stringResource(R.string.gpg_email)) },
                            supportingText = { Text(stringResource(R.string.gpg_email_help)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> {
                        OutlinedTextField(
                            value = s.privateKey,
                            onValueChange = { v -> viewModel.update { it.copy(privateKey = v) } },
                            label = { Text(stringResource(if (tab == 4) R.string.gpg_secret_key else R.string.auth_ssh_key)) },
                            textStyle = CodeTextStyle,
                            minLines = 5,
                            maxLines = 10,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = s.passphrase,
                            onValueChange = { v -> viewModel.update { it.copy(passphrase = v) } },
                            label = { Text(stringResource(R.string.auth_ssh_passphrase)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                val enabled = !s.busy && when (tab) {
                    0 -> s.token.isNotBlank() && s.username.isNotBlank()
                    1 -> true
                    3 -> s.gpgName.isNotBlank() && s.gpgEmail.contains('@')
                    else -> s.privateKey.isNotBlank()
                }
                GradientButton(
                    text = stringResource(
                        when {
                            tab == 1 && s.busy -> R.string.ssh_generating
                            tab == 1 -> R.string.ssh_generate
                            tab == 3 && s.busy -> R.string.gpg_generating
                            tab == 3 -> R.string.gpg_generate
                            else -> R.string.action_save
                        },
                    ),
                    onClick = {
                        when (tab) {
                            0 -> viewModel.saveHttps()
                            1 -> viewModel.generateSsh()
                            2 -> viewModel.importSsh()
                            3 -> viewModel.generateGpg()
                            else -> viewModel.importGpg()
                        }
                    },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

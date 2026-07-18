package dev.injun.remotesync.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.injun.remotesync.sync.Protocol
import dev.injun.remotesync.sync.RemoteConfig
import dev.injun.remotesync.sync.SmbConfig
import dev.injun.remotesync.sync.SyncPair

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    protocol: Protocol,
    existing: SyncPair?,
    onSave: (SyncPair) -> Unit,
    onDelete: (() -> Unit)?,
    onBack: (() -> Unit)?,
) {
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var localRoot by rememberSaveable { mutableStateOf(existing?.localRoot ?: "/storage/emulated/0/") }
    // Protocol-specific half of the form: one exhaustive branch supplies the field
    // state, rendering, validity, and the built RemoteConfig, so the compiler points
    // here when a Protocol entry is added (see Protocol.kt).
    val remoteForm: RemoteFormState = when (protocol) {
        Protocol.SMB -> rememberSmbFormState(existing?.remote as? SmbConfig)
    }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val canSave = localRoot.isNotBlank() && remoteForm.complete

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete folder pair?") },
            text = {
                Text(
                    "This removes the pair's settings, saved credentials, and sync history. " +
                        "Files on both sides stay in place, but re-adding the pair later starts " +
                        "without history and can create conflict copies for files that changed.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${protocol.display} " + if (existing == null) "folder pair" else "· edit") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (onDelete != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding(),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            SectionLabel("Local folder")
            OutlinedTextField(
                value = localRoot,
                onValueChange = { localRoot = it },
                label = { Text("Local folder path") },
                supportingText = { Text("A real path in shared storage that other apps can read and write") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            SectionLabel("${protocol.display} server")
            remoteForm.Fields()

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    onSave(
                        SyncPair(
                            id = existing?.id ?: 0L,
                            name = name.trim().ifBlank { "Folder pair" },
                            localRoot = localRoot.trim(),
                            remote = remoteForm.build(),
                        ),
                    )
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }
    }
}

// Protocol-specific half of the setup form. Each protocol implements one; SetupScreen
// obtains it through an exhaustive when so new Protocol entries fail to compile here.
private interface RemoteFormState {
    /** True once every required field is filled in. */
    val complete: Boolean

    fun build(): RemoteConfig

    @Composable
    fun Fields()
}

@Composable
private fun rememberSmbFormState(existing: SmbConfig?): SmbFormState =
    rememberSaveable(saver = SmbFormState.saver(existing)) { SmbFormState(existing) }

private class SmbFormState(existing: SmbConfig?) : RemoteFormState {
    var host by mutableStateOf(existing?.host ?: "")
    var port by mutableStateOf((existing?.port ?: 445).toString())
    var share by mutableStateOf(existing?.shareName ?: "")
    var domain by mutableStateOf(existing?.domain ?: "")
    var user by mutableStateOf(existing?.username ?: "")
    // Deliberately not in [saver]: saved instance state can be written to disk
    // unencrypted, so a restore falls back to the seeded value instead.
    var pass by mutableStateOf(existing?.password ?: "")
    var rootPath by mutableStateOf(existing?.rootPath ?: "")

    override val complete: Boolean
        get() = host.isNotBlank() && share.isNotBlank() && user.isNotBlank()

    override fun build(): RemoteConfig = SmbConfig(
        host = host.trim(),
        port = port.toIntOrNull() ?: 445,
        shareName = share.trim(),
        domain = domain.trim(),
        username = user.trim(),
        password = pass,
        rootPath = rootPath.trim().trim('/'),
    )

    @Composable
    override fun Fields() {
        Row(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host / IP") },
                singleLine = true,
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit) },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
        }
        Field("Share name", share) { share = it }
        Field("Sub-path (optional)", rootPath) { rootPath = it }
        Field("Domain (optional)", domain) { domain = it }
        Field("Username", user) { user = it }
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }

    companion object {
        fun saver(existing: SmbConfig?) = listSaver<SmbFormState, String>(
            save = { listOf(it.host, it.port, it.share, it.domain, it.user, it.rootPath) },
            restore = { saved ->
                SmbFormState(existing).apply {
                    host = saved[0]
                    port = saved[1]
                    share = saved[2]
                    domain = saved[3]
                    user = saved[4]
                    rootPath = saved[5]
                }
            },
        )
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

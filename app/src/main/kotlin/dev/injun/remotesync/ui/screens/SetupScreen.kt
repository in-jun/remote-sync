package dev.injun.remotesync.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.injun.remotesync.sync.Protocol
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
    val existingSmb = existing?.remote as? SmbConfig
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var localRoot by remember { mutableStateOf(existing?.localRoot ?: "/storage/emulated/0/") }
    var host by remember { mutableStateOf(existingSmb?.host ?: "") }
    var port by remember { mutableStateOf((existingSmb?.port ?: 445).toString()) }
    var share by remember { mutableStateOf(existingSmb?.shareName ?: "") }
    var domain by remember { mutableStateOf(existingSmb?.domain ?: "") }
    var user by remember { mutableStateOf(existingSmb?.username ?: "") }
    var pass by remember { mutableStateOf(existingSmb?.password ?: "") }
    var rootPath by remember { mutableStateOf(existingSmb?.rootPath ?: "") }

    val canSave = localRoot.isNotBlank() && host.isNotBlank() &&
        share.isNotBlank() && user.isNotBlank()

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
                        IconButton(onClick = onDelete) {
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
                .verticalScroll(rememberScrollState()),
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

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    onSave(
                        SyncPair(
                            id = existing?.id ?: 0L,
                            name = name.trim().ifBlank { "Folder pair" },
                            localRoot = localRoot.trim(),
                            remote = SmbConfig(
                                host = host.trim(),
                                port = port.toIntOrNull() ?: 445,
                                shareName = share.trim(),
                                domain = domain.trim(),
                                username = user.trim(),
                                password = pass,
                                rootPath = rootPath.trim().trim('/'),
                            ),
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

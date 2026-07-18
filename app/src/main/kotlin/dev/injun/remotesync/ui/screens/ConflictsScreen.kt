package dev.injun.remotesync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.injun.remotesync.conflict.ConflictItem
import dev.injun.remotesync.conflict.ConflictResolution
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictsScreen(
    conflicts: List<ConflictItem>,
    resolving: Set<String>,
    onResolve: (ConflictItem, ConflictResolution) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Conflicts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (conflicts.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No conflicts", style = MaterialTheme.typography.titleMedium)
                Text(
                    "When the same file is changed differently on both sides, resolve it here.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(conflicts, key = { it.key }) { item ->
                    ConflictCard(item, item.key in resolving, onResolve)
                }
            }
        }
    }
}

@Composable
private fun ConflictCard(
    item: ConflictItem,
    resolving: Boolean,
    onResolve: (ConflictItem, ConflictResolution) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${item.pairName} · ${item.originalPath}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (resolving) {
                    CircularProgressIndicator(
                        Modifier.padding(start = 8.dp).size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            VersionBlock("Local (this device)", item.localSize, item.localMtime, item.localPreview)
            VersionBlock("Remote (server)", item.remoteSize, item.remoteMtime, item.remotePreview)

            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onResolve(item, ConflictResolution.KEEP_LOCAL) },
                    enabled = !resolving,
                    modifier = Modifier.weight(1f),
                ) { Text("Keep local") }
                OutlinedButton(
                    onClick = { onResolve(item, ConflictResolution.KEEP_REMOTE) },
                    enabled = !resolving,
                    modifier = Modifier.weight(1f),
                ) { Text("Keep remote") }
            }
            TextButton(
                onClick = { onResolve(item, ConflictResolution.KEEP_BOTH) },
                enabled = !resolving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Keep both") }
            Text(
                "Keep both: the remote version is kept alongside as '${item.keepBothName}'.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun VersionBlock(label: String, size: Long, mtime: Long, preview: String?) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(
            "$label · ${size}B · ${formatTime(mtime)}",
            style = MaterialTheme.typography.labelMedium,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Text(
                text = preview ?: "Binary file — no preview",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (preview != null) FontFamily.Monospace else null,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

private fun formatTime(millis: Long): String =
    if (millis <= 0) "-" else SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(millis))

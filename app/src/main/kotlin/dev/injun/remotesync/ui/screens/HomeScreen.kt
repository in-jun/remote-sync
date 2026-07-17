package dev.injun.remotesync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.injun.remotesync.data.config.LastSync
import dev.injun.remotesync.sync.SyncPair
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    pairs: List<SyncPair>,
    syncing: Set<Long>,
    lastSync: Map<Long, LastSync>,
    hasAllFilesAccess: Boolean,
    canPostNotifications: Boolean,
    conflictCount: Int,
    onOpenConflicts: () -> Unit,
    onRequestPermission: () -> Unit,
    onRequestNotifications: () -> Unit,
    onSyncPair: (Long) -> Unit,
    onSyncAll: () -> Unit,
    onAddPair: () -> Unit,
    onEditPair: (Long) -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Remote Sync") },
                actions = {
                    IconButton(onClick = onSyncAll) { Icon(Icons.Default.Sync, "Sync all") }
                    IconButton(onClick = onAddPair) { Icon(Icons.Default.Add, "Add pair") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            if (!hasAllFilesAccess) {
                item { PermissionBanner(onRequestPermission) }
            }
            if (!canPostNotifications) {
                item { NotificationBanner(onRequestNotifications) }
            }
            if (conflictCount > 0) {
                item { ConflictBanner(conflictCount, onOpenConflicts) }
            }
            items(pairs, key = { it.id }) { pair ->
                PairCard(
                    pair = pair,
                    syncing = pair.id in syncing,
                    last = lastSync[pair.id],
                    onSync = { onSyncPair(pair.id) },
                    onEdit = { onEditPair(pair.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairCard(
    pair: SyncPair,
    syncing: Boolean,
    last: LastSync?,
    onSync: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(pair.name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(pair.localRoot, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${pair.protocol.display} · ${pair.remote.displayPath}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    statusText(syncing, last),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onSync, enabled = !syncing) {
                    if (syncing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Sync")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConflictBanner(count: Int, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null)
            Column(Modifier.padding(start = 12.dp)) {
                Text("$count conflict(s)", style = MaterialTheme.typography.titleSmall)
                Text("Both versions preserved — tap to resolve", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PermissionBanner(onRequestPermission: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null)
                Text("  All-files access required", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "Reading and writing real files in shared storage needs the \"All files access\" permission.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            FilledTonalButton(onClick = onRequestPermission) { Text("Open permission settings") }
        }
    }
}

@Composable
private fun NotificationBanner(onRequestNotifications: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null)
                Text("  Notifications disabled", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "Sync-stopped and repeated-failure alerts cannot be shown. A stalled sync would go unnoticed until you open the app.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            FilledTonalButton(onClick = onRequestNotifications) { Text("Allow notifications") }
        }
    }
}

private fun statusText(syncing: Boolean, last: LastSync?): String = when {
    syncing -> "Syncing…"
    last == null -> "Never synced"
    else -> "Last: ${time(last.atMillis)} · ${last.outcome}"
}

private fun time(millis: Long): String =
    SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(millis))

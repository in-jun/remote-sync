package dev.injun.remotesync.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.injun.remotesync.sync.AppSettings
import dev.injun.remotesync.sync.SyncMode
import dev.injun.remotesync.ui.rememberBatteryExemption
import dev.injun.remotesync.ui.requestIgnoreBatteryOptimizations

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxWidth(),
        ) {
            Text("Sync mode", style = MaterialTheme.typography.titleSmall)
            ModeOption(
                "Periodic (recommended)",
                "Battery-friendly. Syncs on open, and periodically in the background.",
                selected = settings.mode == SyncMode.PERIODIC,
            ) { onChange(settings.copy(mode = SyncMode.PERIODIC)) }
            ModeOption(
                "Near real-time",
                "Syncs changes within seconds via a persistent notification. Uses a bit more battery.",
                selected = settings.mode == SyncMode.REALTIME,
            ) { onChange(settings.copy(mode = SyncMode.REALTIME)) }
            if (settings.mode == SyncMode.REALTIME && !rememberBatteryExemption()) {
                val context = LocalContext.current
                Text(
                    "Battery optimization is limiting real-time sync: when the device " +
                        "sleeps, the network is cut off and changes wait for the next " +
                        "periodic sync. Allow unrestricted battery use to sync in the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { requestIgnoreBatteryOptimizations(context) }) {
                    Text("Allow unrestricted battery use")
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Sync on Wi-Fi only", style = MaterialTheme.typography.bodyLarge)
                    Text("Never sync on metered networks", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = settings.wifiOnly,
                    onCheckedChange = { onChange(settings.copy(wifiOnly = it)) },
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Mass-deletion guard: abort if more than ${(settings.maxDeleteThreshold * 100).toInt()}% of a side would be deleted",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ModeOption(
    title: String,
    detail: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

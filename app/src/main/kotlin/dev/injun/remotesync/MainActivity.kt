package dev.injun.remotesync

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import dev.injun.remotesync.sync.Protocol
import dev.injun.remotesync.ui.MainViewModel
import dev.injun.remotesync.ui.rememberAllFilesAccess
import dev.injun.remotesync.ui.rememberNotificationAccess
import dev.injun.remotesync.ui.rememberNotificationAccessRequest
import dev.injun.remotesync.ui.rememberStorageAccessRequest
import dev.injun.remotesync.ui.screens.ConflictsScreen
import dev.injun.remotesync.ui.screens.HomeScreen
import dev.injun.remotesync.ui.screens.ProtocolPickerScreen
import dev.injun.remotesync.ui.screens.SettingsScreen
import dev.injun.remotesync.ui.screens.SetupScreen
import dev.injun.remotesync.ui.theme.RemoteSyncTheme

private enum class Screen { HOME, PROTOCOL, SETUP, SETTINGS, CONFLICTS }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RemoteSyncTheme { AppRoot(viewModel) }
        }
    }
}

@Composable
private fun AppRoot(viewModel: MainViewModel) {
    val pairs by viewModel.pairs.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val syncing by viewModel.syncing.collectAsState()
    val lastSync by viewModel.lastSync.collectAsState()
    val conflicts by viewModel.conflicts.collectAsState()
    val hasAccess = rememberAllFilesAccess()
    val requestStorageAccess = rememberStorageAccessRequest()
    val canNotify = rememberNotificationAccess()
    val requestNotificationAccess = rememberNotificationAccessRequest()

    // pairs starts empty until the encrypted store loads; picking a start screen
    // before then would drop a configured user on the protocol picker.
    val configLoaded by viewModel.configLoaded.collectAsState()
    if (!configLoaded) return

    var screen by remember { mutableStateOf(if (pairs.isEmpty()) Screen.PROTOCOL else Screen.HOME) }
    var editingId by remember { mutableStateOf<Long?>(null) }
    // Protocol chosen for a NEW pair (picked before the form).
    var newProtocol by remember { mutableStateOf(Protocol.SMB) }

    LaunchedEffect(pairs.isEmpty()) {
        if (pairs.isEmpty() && screen == Screen.HOME) screen = Screen.PROTOCOL
    }

    // Surface operation errors (conflict scan/resolve, pair lifecycle) instead of crashing.
    val context = LocalContext.current
    val error by viewModel.error.collectAsState()
    LaunchedEffect(error) {
        error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }
    LaunchedEffect(screen) {
        if (screen == Screen.HOME || screen == Screen.CONFLICTS) viewModel.refreshConflicts()
    }

    // Sync on foreground; in PERIODIC mode also watch changes live while app is open.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onForegrounded()
                Lifecycle.Event.ON_PAUSE -> viewModel.onBackgrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Per-screen back target; null = let the system handle it (exit the app).
    val goBack: (() -> Unit)? = when (screen) {
        Screen.HOME -> null
        Screen.PROTOCOL -> if (pairs.isNotEmpty()) ({ screen = Screen.HOME }) else null
        Screen.SETUP -> if (editingId != null) ({ screen = Screen.HOME }) else ({ screen = Screen.PROTOCOL })
        Screen.SETTINGS, Screen.CONFLICTS -> ({ screen = Screen.HOME })
    }
    BackHandler(enabled = goBack != null) { goBack?.invoke() }

    when (screen) {
        Screen.PROTOCOL -> ProtocolPickerScreen(
            onSelect = { proto ->
                newProtocol = proto
                editingId = null
                screen = Screen.SETUP
            },
            onBack = goBack ?: {},
        )

        Screen.SETUP -> {
            val existing = editingId?.let { id -> pairs.firstOrNull { it.id == id } }
            SetupScreen(
                protocol = existing?.protocol ?: newProtocol,
                existing = existing,
                onSave = { pair ->
                    val firstPair = pairs.isEmpty()
                    viewModel.upsertPair(pair)
                    screen = Screen.HOME
                    // First pair set up: ask for POST_NOTIFICATIONS (API 33+) so
                    // abort/failure alerts are not silently dropped. Below 33 the
                    // permission does not exist; the Home banner covers a manual
                    // opt-out there.
                    if (firstPair && !canNotify &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ) {
                        requestNotificationAccess()
                    }
                },
                onDelete = if (existing != null) {
                    { viewModel.deletePair(existing.id); screen = Screen.HOME }
                } else {
                    null
                },
                onBack = goBack,
            )
        }

        Screen.SETTINGS -> SettingsScreen(
            settings = settings,
            onChange = viewModel::saveSettings,
            onBack = { screen = Screen.HOME },
        )

        Screen.CONFLICTS -> ConflictsScreen(
            conflicts = conflicts,
            onResolve = viewModel::resolveConflict,
            onBack = { screen = Screen.HOME },
        )

        Screen.HOME -> HomeScreen(
            pairs = pairs,
            syncing = syncing,
            lastSync = lastSync,
            hasAllFilesAccess = hasAccess,
            canPostNotifications = canNotify,
            conflictCount = conflicts.size,
            onOpenConflicts = { screen = Screen.CONFLICTS },
            onRequestPermission = requestStorageAccess,
            onRequestNotifications = requestNotificationAccess,
            onSyncPair = viewModel::syncPair,
            onSyncAll = { viewModel.syncAll(manual = true) },
            onAddPair = { screen = Screen.PROTOCOL },
            onEditPair = { id -> editingId = id; screen = Screen.SETUP },
            onSettings = { screen = Screen.SETTINGS },
        )
    }
}

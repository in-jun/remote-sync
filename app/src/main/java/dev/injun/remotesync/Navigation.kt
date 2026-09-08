package dev.injun.remotesync

import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.injun.remotesync.ui.MainViewModel
import dev.injun.remotesync.ui.conflicts.ConflictsScreen
import dev.injun.remotesync.ui.home.HomeScreen
import dev.injun.remotesync.ui.rememberAllFilesAccess
import dev.injun.remotesync.ui.rememberNotificationAccess
import dev.injun.remotesync.ui.rememberNotificationAccessRequest
import dev.injun.remotesync.ui.rememberStorageAccessRequest
import dev.injun.remotesync.ui.settings.SettingsScreen
import dev.injun.remotesync.ui.setup.ProtocolPickerScreen
import dev.injun.remotesync.ui.setup.SetupScreen

@Composable
fun MainNavigation(viewModel: MainViewModel) {
    val pairs by viewModel.pairs.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val lastSync by viewModel.lastSync.collectAsStateWithLifecycle()
    val conflicts by viewModel.conflicts.collectAsStateWithLifecycle()
    val resolving by viewModel.resolving.collectAsStateWithLifecycle()
    val hasAccess = rememberAllFilesAccess()
    val requestStorageAccess = rememberStorageAccessRequest()
    val canNotify = rememberNotificationAccess()
    val requestNotificationAccess = rememberNotificationAccessRequest()

    // pairs starts empty until the encrypted store loads; picking a start screen
    // before then would drop a configured user on the protocol picker.
    val configLoaded by viewModel.configLoaded.collectAsStateWithLifecycle()
    if (!configLoaded) return

    val backStack = rememberNavBackStack(if (pairs.isEmpty()) ProtocolPicker else Home)
    val current: NavKey? = backStack.lastOrNull()

    fun navigateHome() {
        backStack.clear()
        backStack.add(Home)
    }

    LaunchedEffect(pairs.isEmpty()) {
        // Last pair deleted: there is nothing to show on Home, so restart at the picker.
        if (pairs.isEmpty() && backStack.lastOrNull() == Home) {
            backStack.clear()
            backStack.add(ProtocolPicker)
        }
    }

    // Surface operation errors (conflict scan/resolve, pair lifecycle) instead of crashing.
    val context = LocalContext.current
    val error by viewModel.error.collectAsStateWithLifecycle()
    LaunchedEffect(error) {
        error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }
    LaunchedEffect(current) {
        if (current == Home || current == Conflicts) viewModel.refreshConflicts()
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

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<Home> {
                HomeScreen(
                    pairs = pairs,
                    syncing = syncing,
                    lastSync = lastSync,
                    hasAllFilesAccess = hasAccess,
                    canPostNotifications = canNotify,
                    conflictCount = conflicts.size,
                    onOpenConflicts = { backStack.add(Conflicts) },
                    onRequestPermission = requestStorageAccess,
                    onRequestNotifications = requestNotificationAccess,
                    onSyncPair = viewModel::syncPair,
                    onSyncAll = { viewModel.syncAll(manual = true) },
                    onAddPair = { backStack.add(ProtocolPicker) },
                    onEditPair = { id ->
                        val protocol = pairs.firstOrNull { it.id == id }?.protocol ?: return@HomeScreen
                        backStack.add(Setup(protocol, editingId = id))
                    },
                    onSettings = { backStack.add(Settings) },
                )
            }

            entry<ProtocolPicker> {
                ProtocolPickerScreen(
                    onSelect = { protocol -> backStack.add(Setup(protocol)) },
                    // Root on first run: nothing to go back to until a pair exists.
                    onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                )
            }

            entry<Setup> { key ->
                val existing = key.editingId?.let { id -> pairs.firstOrNull { it.id == id } }
                SetupScreen(
                    protocol = existing?.protocol ?: key.protocol,
                    existing = existing,
                    onSave = { pair ->
                        val firstPair = pairs.isEmpty()
                        viewModel.upsertPair(pair)
                        navigateHome()
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
                        {
                            viewModel.deletePair(existing.id)
                            navigateHome()
                        }
                    } else {
                        null
                    },
                    onBack = { backStack.removeLastOrNull() },
                )
            }

            entry<Settings> {
                SettingsScreen(
                    settings = settings,
                    onChange = viewModel::saveSettings,
                    onBack = { backStack.removeLastOrNull() },
                )
            }

            entry<Conflicts> {
                ConflictsScreen(
                    conflicts = conflicts,
                    resolving = resolving,
                    onResolve = viewModel::resolveConflict,
                    onBack = { backStack.removeLastOrNull() },
                )
            }
        },
    )
}

package dev.injun.remotesync.sync

enum class SyncMode {
    /** WorkManager periodic (battery-friendly) + immediate trigger while app is open. */
    PERIODIC,

    /** Foreground service kept alive for near-real-time sync. */
    REALTIME,
}

/** Global sync behavior (non-secret). [intervalMinutes] is floored to WorkManager's 15-minute minimum. */
data class AppSettings(
    val mode: SyncMode = SyncMode.PERIODIC,
    val intervalMinutes: Long = 15,
    val maxDeleteThreshold: Double = 0.5,
    val wifiOnly: Boolean = false,
)

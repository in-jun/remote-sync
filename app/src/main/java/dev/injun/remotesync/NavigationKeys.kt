package dev.injun.remotesync

import androidx.navigation3.runtime.NavKey
import dev.injun.remotesync.sync.Protocol
import kotlinx.serialization.Serializable

@Serializable data object Home : NavKey

/** First step of adding a pair: pick the remote protocol. */
@Serializable data object ProtocolPicker : NavKey

/** Connection form for a new pair ([editingId] null) or an existing one. */
@Serializable data class Setup(val protocol: Protocol, val editingId: Long? = null) : NavKey

@Serializable data object Settings : NavKey

@Serializable data object Conflicts : NavKey

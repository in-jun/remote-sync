package dev.injun.remotesync.data.config

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.injun.remotesync.sync.AppSettings
import dev.injun.remotesync.sync.Protocol
import dev.injun.remotesync.sync.SmbConfig
import dev.injun.remotesync.sync.SyncMode
import dev.injun.remotesync.sync.SyncPair
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists folder pairs and global settings. Pairs are JSON in
 * [EncryptedSharedPreferences] (each holds an SMB password), exposed as [StateFlow]s.
 *
 * Opening [EncryptedSharedPreferences] hits the Keystore and disk, so the store is
 * loaded on [Dispatchers.IO]; construction is cheap. [pairs] and [settings] start at
 * their defaults — call [awaitLoaded] before trusting them.
 */
@Singleton
class ConfigRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "remote-sync-secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _pairs = MutableStateFlow<List<SyncPair>>(emptyList())
    val pairs: StateFlow<List<SyncPair>> = _pairs.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)

    /** Observable form of [awaitLoaded] for UI that cannot suspend before composing. */
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val writeMutex = Mutex()

    private val loaded: Deferred<Unit> = CoroutineScope(Dispatchers.IO).async {
        _pairs.value = loadPairs()
        _settings.value = loadSettings()
        _isLoaded.value = true
    }

    /** Suspends until the persisted pairs and settings have been read from disk. */
    suspend fun awaitLoaded() = loaded.await()

    val isConfigured: Boolean get() = _pairs.value.isNotEmpty()

    fun pair(id: Long): SyncPair? = _pairs.value.firstOrNull { it.id == id }

    /** Insert a new pair (id <= 0) or replace an existing one, returning its id. */
    suspend fun upsertPair(pair: SyncPair): Long {
        awaitLoaded()
        return withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val current = _pairs.value.toMutableList()
                val resolved = if (pair.id <= 0L) pair.copy(id = nextId(current)) else pair
                val idx = current.indexOfFirst { it.id == resolved.id }
                if (idx >= 0) current[idx] = resolved else current.add(resolved)
                persist(current)
                resolved.id
            }
        }
    }

    suspend fun deletePair(id: Long) {
        awaitLoaded()
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                persist(_pairs.value.filterNot { it.id == id })
            }
        }
    }

    suspend fun saveSettings(settings: AppSettings) {
        awaitLoaded()
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                prefs.edit().apply {
                    putString(KEY_MODE, settings.mode.name)
                    putLong(KEY_INTERVAL, settings.intervalMinutes)
                    putFloat(KEY_MAX_DELETE, settings.maxDeleteThreshold.toFloat())
                    putBoolean(KEY_WIFI_ONLY, settings.wifiOnly)
                    apply()
                }
                _settings.value = settings
            }
        }
    }

    private fun persist(pairs: List<SyncPair>) {
        prefs.edit().putString(KEY_PAIRS, toJson(pairs)).apply()
        _pairs.value = pairs
    }

    // Never reuse an id: a recreated pair with a recycled id would inherit the deleted
    // pair's persisted ancestor snapshot. The counter is persisted so it survives deletes.
    private fun nextId(pairs: List<SyncPair>): Long {
        val next = maxOf(prefs.getLong(KEY_NEXT_ID, 0L), pairs.maxOfOrNull { it.id } ?: 0L) + 1L
        prefs.edit().putLong(KEY_NEXT_ID, next).apply()
        return next
    }

    // ---- serialization ----

    private fun toJson(pairs: List<SyncPair>): String {
        val arr = JSONArray()
        for (p in pairs) {
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("protocol", p.protocol.name)
                    put("localRoot", p.localRoot)
                    when (val r = p.remote) {
                        is SmbConfig -> {
                            put("host", r.host)
                            put("port", r.port)
                            put("share", r.shareName)
                            put("domain", r.domain)
                            put("user", r.username)
                            put("pass", r.password)
                            put("rootPath", r.rootPath)
                        }
                    }
                },
            )
        }
        return arr.toString()
    }

    private fun loadPairs(): List<SyncPair> {
        val json = prefs.getString(KEY_PAIRS, null)
        if (json == null) return migrateLegacy()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val protocol = runCatching { Protocol.valueOf(o.optString("protocol")) }
                    .getOrDefault(Protocol.SMB)
                SyncPair(
                    id = o.getLong("id"),
                    name = o.optString("name", "Folder pair"),
                    localRoot = o.optString("localRoot"),
                    remote = when (protocol) {
                        Protocol.SMB -> SmbConfig(
                            host = o.optString("host"),
                            port = o.optInt("port", 445),
                            shareName = o.optString("share"),
                            domain = o.optString("domain"),
                            username = o.optString("user"),
                            password = o.optString("pass"),
                            rootPath = o.optString("rootPath"),
                        )
                    },
                )
            }
        }.getOrDefault(emptyList())
    }

    /** One-time migration from the original single-pair key layout. */
    private fun migrateLegacy(): List<SyncPair> {
        if (!prefs.getBoolean("configured", false)) return emptyList()
        val legacy = SyncPair(
            id = 1L,
            name = "Folder pair",
            localRoot = prefs.getString("local_root", "").orEmpty(),
            remote = SmbConfig(
                host = prefs.getString("smb_host", "").orEmpty(),
                port = prefs.getInt("smb_port", 445),
                shareName = prefs.getString("smb_share", "").orEmpty(),
                domain = prefs.getString("smb_domain", "").orEmpty(),
                username = prefs.getString("smb_user", "").orEmpty(),
                password = prefs.getString("smb_pass", "").orEmpty(),
                rootPath = prefs.getString("smb_root_path", "").orEmpty(),
            ),
        )
        prefs.edit().putString(KEY_PAIRS, toJson(listOf(legacy))).apply()
        return listOf(legacy)
    }

    private fun loadSettings(): AppSettings = AppSettings(
        mode = runCatching { SyncMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }
            .getOrDefault(SyncMode.PERIODIC),
        intervalMinutes = prefs.getLong(KEY_INTERVAL, 15),
        maxDeleteThreshold = prefs.getFloat(KEY_MAX_DELETE, 0.5f).toDouble(),
        wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, false),
    )

    private companion object {
        const val KEY_PAIRS = "pairs_json"
        const val KEY_NEXT_ID = "next_pair_id"
        const val KEY_MODE = "mode"
        const val KEY_INTERVAL = "interval"
        const val KEY_MAX_DELETE = "max_delete"
        const val KEY_WIFI_ONLY = "wifi_only"
    }
}

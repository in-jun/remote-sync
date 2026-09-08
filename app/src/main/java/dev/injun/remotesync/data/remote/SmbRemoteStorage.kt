package dev.injun.remotesync.data.remote

import android.util.Log
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CompletionFilter
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig as SmbjConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import dev.injun.remotesync.core.model.Snapshot
import dev.injun.remotesync.core.port.ContentHash
import dev.injun.remotesync.core.port.RawEntry
import dev.injun.remotesync.core.port.SnapshotBuilder
import dev.injun.remotesync.core.port.Storage
import dev.injun.remotesync.core.port.TempFiles
import dev.injun.remotesync.sync.RemoteStorage
import dev.injun.remotesync.sync.SmbConfig
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.Source
import okio.buffer
import okio.source

/**
 * [Storage] over an SMB2/3 share via smbj (v1 remote). Writes are atomic (temp name
 * then server-side rename). smbj calls are blocking and run on [Dispatchers.IO]; the
 * connection is opened lazily, reused for a whole sync, and torn down by [close].
 */
class SmbRemoteStorage(
    private val config: SmbConfig,
    private val client: SMBClient = secureClient(),
) : RemoteStorage {

    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    private fun share(): DiskShare {
        share?.let { return it }
        val conn = connectEncrypted(client)
        try {
            val sess = conn.authenticate(
                AuthenticationContext(config.username, config.password.toCharArray(), config.domain),
            )
            val disk = sess.connectShare(config.shareName) as DiskShare
            connection = conn
            session = sess
            share = disk
            return disk
        } catch (t: Throwable) {
            // authenticate/connectShare failed before the fields were assigned, so
            // close() would find nothing to release — close the live connection here
            // or its socket and packet-reader thread leak on every failed attempt.
            runCatching { conn.close() }
            throw t
        }
    }

    // smbj's withEncryptData is best-effort: it only takes effect when the negotiated
    // connection can encrypt, and otherwise the session silently sends plaintext.
    // [secureClient] already restricts dialects to SMB 3.x; this rejects the residual
    // case of a 3.x server that negotiated no encryption capability, so a connection
    // that cannot encrypt fails outright instead of downgrading.
    private fun connectEncrypted(client: SMBClient): Connection {
        val conn = client.connect(config.host, config.port)
        if (!conn.connectionContext.supportsEncryption()) {
            runCatching { conn.close() }
            throw IOException("SMB server ${config.host} negotiated no encryption; refusing to sync in plaintext")
        }
        return conn
    }

    override suspend fun scan(hint: Snapshot): Snapshot = withContext(Dispatchers.IO) {
        val disk = share()
        val entries = ArrayList<RawEntry>()
        val rootSmb = smbPath("")
        if (rootSmb.isEmpty() || disk.folderExists(rootSmb)) {
            walk(disk, "", entries)
        } else if (hint.files.isNotEmpty()) {
            // The root has been synced before (the hint carries its files), so its
            // absence now means a renamed/removed remote folder or a transient share
            // failure. Scanning it as empty would make the engine see every synced
            // file as remotely deleted and delete them all locally — fail the pass
            // instead, mirroring the local-side guard in DirectFileLocalStorage.
            throw IOException("remote sync root disappeared: ${config.rootPath} on ${config.shareName}")
        }
        // A missing root with an empty hint is a first sync to a fresh remote folder:
        // empty is correct, and the first write creates it.
        // Server time, since the entries' mtimes are server-stamped. When the offset is
        // unknown the reuse shortcut is disabled (every file re-hashed) rather than
        // risked against a device clock that may run ahead of the server.
        SnapshotBuilder.build(entries, hint, serverNowMillis()) { path -> hashRemote(disk, path) }
    }

    private fun walk(disk: DiskShare, dirRel: String, out: MutableList<RawEntry>) {
        for (info in disk.list(smbPath(dirRel))) {
            val name = info.fileName
            if (name == "." || name == "..") continue
            val childRel = if (dirRel.isEmpty()) name else "$dirRel/$name"
            val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            if (!isDir && TempFiles.isTempName(name)) {
                // In-flight or orphaned writeAtomic temp (ours or another device's) —
                // never a sync entry. Remove it only once it is old enough that no
                // writer can still be streaming into it.
                val ageMs = serverNowMillis()?.let { it - info.lastWriteTime.toEpochMillis() }
                if (ageMs != null && ageMs > TempFiles.STALE_AGE_MS) runCatching { disk.rm(smbPath(childRel)) }
                continue
            }
            if (isDir) {
                walk(disk, childRel, out)
            } else {
                out.add(RawEntry(childRel, info.endOfFile, info.lastWriteTime.toEpochMillis()))
            }
        }
    }

    // Temp age must be measured on the server's clock: mtimes are server-stamped, and
    // against the device clock a NAS running behind would make another device's
    // seconds-old temp look stale (deleting it mid-write), while one running ahead
    // would keep orphans forever. The offset (device minus server, captured at
    // negotiate) converts device "now" into server "now"; without one, skip reaping.
    private fun serverNowMillis(): Long? {
        val offset = connection?.connectionContext?.timeOffsetMillis ?: return null
        return System.currentTimeMillis() - offset
    }

    override suspend fun read(path: String): Source = withContext(Dispatchers.IO) {
        // Read fully and close the handle immediately, returning an in-memory source.
        // Wrapping the open smbj File in a stream leaks the handle (closing the
        // InputStream does NOT close the File), and a lingering handle blocks a later
        // delete/rename of the same path. Fine for the small files this app targets.
        share().openFile(
            smbPath(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        ).use { file ->
            val bytes = file.getInputStream().use { it.readBytes() }
            Buffer().write(bytes)
        }
    }

    override suspend fun writeAtomic(path: String, content: Source): RawEntry? = withContext(Dispatchers.IO) {
        // The outer try owns [content]: connect/validate/mkdir can all fail, and
        // without it each failed action would leak the caller's open source.
        try {
            val disk = share()
            val finalPath = smbPath(path)
            val tmpPath = tempSibling(finalPath)
            ensureParentDirs(disk, path)
            try {
                // Open the temp file ONCE with DELETE access, stream into it, then rename
                // over the target on the SAME handle. Renaming (SET_INFO FileRename) needs
                // DELETE on the open handle; doing it on the write handle guarantees that.
                disk.openFile(
                    tmpPath,
                    EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ, AccessMask.DELETE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    null,
                ).use { tmp ->
                    val bytes = content.buffer().use { it.readByteArray() }
                    tmp.write(bytes, 0)
                    // Stat via the still-open handle, which follows the file object across
                    // the rename below — never re-open [finalPath] by name afterwards: a
                    // second writer could replace it in the gap, and pairing ITS stat with
                    // our content hash would freeze the scan hint on the wrong bytes.
                    val info = tmp.fileInformation
                    renameReplacing(disk, tmp, finalPath)
                    RawEntry(
                        path,
                        info.standardInformation.endOfFile,
                        info.basicInformation.lastWriteTime.toEpochMillis(),
                    )
                }
            } finally {
                runCatching { if (disk.fileExists(tmpPath)) disk.rm(tmpPath) }
            }
        } finally {
            content.close()
        }
    }

    override suspend fun delete(path: String): Unit = withContext(Dispatchers.IO) {
        val disk = share()
        val p = smbPath(path)
        if (disk.fileExists(p)) disk.rm(p)
    }

    override suspend fun move(from: String, to: String): Unit = withContext(Dispatchers.IO) {
        val disk = share()
        ensureParentDirs(disk, to)
        disk.openFile(
            smbPath(from),
            EnumSet.of(AccessMask.GENERIC_READ, AccessMask.DELETE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        ).use { renameReplacing(disk, it, smbPath(to)) }
    }

    override suspend fun probe(path: String): RawEntry? = withContext(Dispatchers.IO) {
        val disk = share()
        val p = smbPath(path)
        if (!disk.fileExists(p)) {
            null
        } else {
            val info = disk.getFileInformation(p)
            RawEntry(
                path,
                info.standardInformation.endOfFile,
                info.basicInformation.lastWriteTime.toEpochMillis(),
            )
        }
    }

    /**
     * Rename [handle] onto [dest], replacing any existing file. Tries a true atomic
     * replace (SET_INFO rename with ReplaceIfExists, supported by Windows and most
     * NAS). Samba rejects that with ACCESS_DENIED, so we fall back to removing the
     * target then renaming — the temp already holds the complete file, so no partial
     * content is ever exposed; only a sub-millisecond gap where the name is absent.
     */
    private fun renameReplacing(disk: DiskShare, handle: com.hierynomus.smbj.share.File, dest: String) {
        try {
            handle.rename(dest, true)
        } catch (e: SMBApiException) {
            if (e.status != NtStatus.STATUS_ACCESS_DENIED) throw e
            // Remove the target if present (ignore "not found"), then rename without
            // replace. This is the path taken on Samba.
            runCatching { disk.rm(dest) }
            handle.rename(dest, false)
        }
    }

    private fun ensureParentDirs(disk: DiskShare, path: String) {
        requireSafeRel(path)
        // Full parent path within the share, INCLUDING the configured rootPath — the
        // root sub-directory may not exist yet on a fresh remote, and every level up
        // to the file's parent must be created before the file itself.
        val fullParent = listOf(config.rootPath, path.substringBeforeLast('/', ""))
            .filter { it.isNotEmpty() }
            .joinToString("/")
            .trim('/')
        if (fullParent.isEmpty()) return
        var acc = ""
        for (seg in fullParent.split('/')) {
            acc = if (acc.isEmpty()) seg else "$acc/$seg"
            val smb = acc.replace('/', '\\')
            if (!disk.folderExists(smb)) disk.mkdir(smb)
        }
    }

    private fun hashRemote(disk: DiskShare, path: String): String =
        disk.openFile(
            smbPath(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        ).use { file ->
            // Close the InputStream (via the okio Source) before the handle: unlike
            // read(), which must keep the handle to return a live stream, hashing
            // consumes the whole file here, so both can be released.
            ContentHash.sha256Hex(file.getInputStream().source())
        }

    /** Convert a '/'-relative sync path to a share-relative, '\'-separated SMB path. */
    private fun smbPath(rel: String): String {
        requireSafeRel(rel)
        return listOf(config.rootPath, rel)
            .filter { it.isNotEmpty() }
            .joinToString("/")
            .trim('/')
            .replace('/', '\\')
    }

    private fun requireSafeRel(rel: String) {
        require(SmbRelPath.isSafe(rel)) { "path escapes sync root: $rel" }
    }

    private fun tempSibling(finalPath: String): String {
        val dir = finalPath.substringBeforeLast('\\', "")
        val name = TempFiles.nameFor(finalPath.substringAfterLast('\\'))
        return if (dir.isEmpty()) name else "$dir\\$name"
    }

    /**
     * Remote push via SMB2 CHANGE_NOTIFY on a dedicated watch connection. Transient
     * failures (Wi-Fi blip, NAS reboot, session timeout) reconnect with backoff, so
     * one drop doesn't silently disable push for the rest of the subscription. Only
     * when the server genuinely doesn't support CHANGE_NOTIFY does the flow complete
     * — the orchestrator's baseline poll covers remote changes.
     */
    override fun changes(): Flow<Unit> = callbackFlow {
        var watchClient: SMBClient? = null
        var watchConn: Connection? = null
        val job = launch(Dispatchers.IO) {
            var backoffMs = WATCH_RETRY_MIN_MS
            while (isActive) {
                val currentClient = secureClient()
                watchClient = currentClient
                try {
                    val conn = connectEncrypted(currentClient)
                    watchConn = conn
                    val session = conn.authenticate(
                        AuthenticationContext(config.username, config.password.toCharArray(), config.domain),
                    )
                    val disk = session.connectShare(config.shareName) as DiskShare
                    val rootSmb = smbPath("")
                    // A missing sync root (first sync pending) just retries below.
                    if (rootSmb.isEmpty() || disk.folderExists(rootSmb)) {
                        val dir = disk.openDirectory(
                            rootSmb,
                            EnumSet.of(AccessMask.GENERIC_READ),
                            null,
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OPEN,
                            null,
                        )
                        while (isActive) {
                            // Blocks until the server reports a change (recursively via
                            // watchTree). The long poll legitimately never completes while
                            // nothing changes, so a raw get() would also never return on a
                            // dead transport (half-open TCP after NAS power loss): no
                            // exception, no reconnect, push silently dead. Bound the wait
                            // and, on each timeout, query the watched handle — that round
                            // trip uses smbj's bounded transaction timeout, so a dead
                            // connection throws and re-enters the backoff path.
                            // smbj's PromiseBackedFuture wraps the wait timeout before
                            // rethrowing, so it surfaces as ExecutionException(cause:
                            // SMBRuntimeException(cause: TimeoutException)) rather than a
                            // bare TimeoutException — match by cause chain, like
                            // isChangeNotifyUnsupported. Anything else is a real failure
                            // and must reach the reconnect path.
                            val pending = dir.watchAsync(WATCH_FILTERS, true)
                            while (isActive) {
                                try {
                                    pending.get(WATCH_PROBE_INTERVAL_MS, TimeUnit.MILLISECONDS)
                                    break
                                } catch (e: TimeoutException) {
                                    dir.fileInformation
                                    backoffMs = WATCH_RETRY_MIN_MS
                                } catch (e: ExecutionException) {
                                    if (!isWaitTimeout(e)) throw e
                                    dir.fileInformation
                                    backoffMs = WATCH_RETRY_MIN_MS
                                }
                            }
                            if (!isActive) break
                            backoffMs = WATCH_RETRY_MIN_MS
                            trySend(Unit)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isChangeNotifyUnsupported(e)) {
                        Log.i(TAG, "CHANGE_NOTIFY unsupported by ${config.host}; relying on baseline polling", e)
                        this@callbackFlow.close()
                        return@launch
                    }
                    Log.w(TAG, "SMB watch on ${config.host} failed; retrying in ${backoffMs}ms", e)
                } finally {
                    runCatching { watchConn?.close() }
                    runCatching { currentClient.close() }
                    watchConn = null
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(WATCH_RETRY_MAX_MS)
            }
        }
        awaitClose {
            job.cancel()
            // The loop blocks inside smbj calls that never observe cancellation;
            // closing the connection from here is what unblocks them. flowOn keeps
            // this socket I/O off the collector's dispatcher (the UI watches from
            // Main, where it would throw NetworkOnMainThreadException and leak the
            // connection).
            runCatching { watchConn?.close() }
            runCatching { watchClient?.close() }
        }
    }.flowOn(Dispatchers.IO)

    /** True when the exception chain bottoms out in the probe wait expiring (no change yet). */
    private fun isWaitTimeout(e: Exception): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is TimeoutException) return true
            cause = cause.cause
        }
        return false
    }

    private fun isChangeNotifyUnsupported(e: Exception): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is SMBApiException && cause.status == NtStatus.STATUS_NOT_SUPPORTED) return true
            cause = cause.cause
        }
        return false
    }

    override fun close() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        // Backstop: the client tracks every connection it opened, so closing it
        // releases anything a partial connect left behind.
        runCatching { client.close() }
        share = null
        session = null
        connection = null
    }

    private companion object {
        const val TAG = "SmbRemoteStorage"

        /** Reconnect backoff for the CHANGE_NOTIFY watch loop. */
        const val WATCH_RETRY_MIN_MS = 5_000L
        const val WATCH_RETRY_MAX_MS = 5 * 60_000L

        /**
         * How long one CHANGE_NOTIFY wait may sit before the watch connection is
         * probed for liveness. Long enough that an idle watch stays a cheap long
         * poll, short enough that a stranded socket is noticed well before the
         * periodic safety poll would paper over it.
         */
        const val WATCH_PROBE_INTERVAL_MS = 2 * 60_000L

        /**
         * smbj defaults leave message signing and encryption OFF, so an on-network
         * attacker could read or tamper with synced content in transit. Require both,
         * and offer only SMB 3.x dialects: `withEncryptData` is honored solely on a
         * 3.x session, so the default dialect list would let a 2.x-only server — or
         * an active MITM downgrading the unauthenticated pre-3.1.1 NEGOTIATE — read
         * everything in plaintext. With 3.x only, such a connection fails instead;
         * `connectEncrypted` covers the remaining 3.x-without-encryption case.
         */
        fun secureClient(): SMBClient = SMBClient(
            SmbjConfig.builder()
                .withDialects(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0)
                .withSigningRequired(true)
                .withEncryptData(true)
                .build(),
        )

        val WATCH_FILTERS: Set<SMB2CompletionFilter> = EnumSet.of(
            SMB2CompletionFilter.FILE_NOTIFY_CHANGE_FILE_NAME,
            SMB2CompletionFilter.FILE_NOTIFY_CHANGE_DIR_NAME,
            SMB2CompletionFilter.FILE_NOTIFY_CHANGE_LAST_WRITE,
            SMB2CompletionFilter.FILE_NOTIFY_CHANGE_SIZE,
            SMB2CompletionFilter.FILE_NOTIFY_CHANGE_CREATION,
        )
    }
}

/**
 * Guard for '/'-relative sync paths headed to an SMB share. A segment that is empty,
 * '.', '..', or contains '\' would resolve outside the configured rootPath once the
 * server treats it as a separator ('\' is legal in local filenames but IS the
 * separator on the wire). Mirrors the local resolve() guard; kept as a pure object
 * so it is unit-testable without a connection.
 */
internal object SmbRelPath {
    fun isSafe(rel: String): Boolean =
        rel.isEmpty() || rel.split('/').none { it.isEmpty() || it == "." || it == ".." || '\\' in it }
}

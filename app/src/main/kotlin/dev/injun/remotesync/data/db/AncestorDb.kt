package dev.injun.remotesync.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.injun.remotesync.core.model.FileMeta
import dev.injun.remotesync.core.port.AncestorRecord
import dev.injun.remotesync.core.port.AncestorStore

// One row of the persisted ancestor snapshot, scoped to a folder pair. The content
// hash is shared; size/mtime are recorded per side because each side assigns its own.
@Entity(tableName = "ancestor", primaryKeys = ["pairId", "path"])
data class AncestorEntity(
    val pairId: Long,
    val path: String,
    val hash: String,
    val localSize: Long,
    val localMtime: Long,
    val remoteSize: Long,
    val remoteMtime: Long,
)

@Dao
interface AncestorDao {
    @Query("SELECT * FROM ancestor WHERE pairId = :pairId")
    suspend fun all(pairId: Long): List<AncestorEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AncestorEntity)

    @Query("DELETE FROM ancestor WHERE pairId = :pairId AND path = :path")
    suspend fun delete(pairId: Long, path: String)

    @Query("DELETE FROM ancestor WHERE pairId = :pairId")
    suspend fun clear(pairId: Long)
}

@Database(entities = [AncestorEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ancestorDao(): AncestorDao

    companion object {
        /**
         * v1 stored one size/mtime per path (the transfer's source side). Seed both
         * sides from it: whichever side it doesn't match is re-hashed once on the next
         * scan and then repaired by the executor's hint refresh. Never drop rows — an
         * empty ancestor would stop deletions from propagating.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE ancestor_v2 (" +
                        "pairId INTEGER NOT NULL, path TEXT NOT NULL, hash TEXT NOT NULL, " +
                        "localSize INTEGER NOT NULL, localMtime INTEGER NOT NULL, " +
                        "remoteSize INTEGER NOT NULL, remoteMtime INTEGER NOT NULL, " +
                        "PRIMARY KEY(pairId, path))",
                )
                db.execSQL(
                    "INSERT INTO ancestor_v2 " +
                        "SELECT pairId, path, hash, size, mtime, size, mtime FROM ancestor",
                )
                db.execSQL("DROP TABLE ancestor")
                db.execSQL("ALTER TABLE ancestor_v2 RENAME TO ancestor")
            }
        }
    }
}

/** [AncestorStore] backed by Room, scoped to one folder pair. Each [put] is a durable write. */
class RoomAncestorStore(
    private val dao: AncestorDao,
    private val pairId: Long,
) : AncestorStore {

    override suspend fun load(): Map<String, AncestorRecord> =
        dao.all(pairId).associate {
            it.path to AncestorRecord(
                local = FileMeta(it.localSize, it.localMtime, it.hash),
                remote = FileMeta(it.remoteSize, it.remoteMtime, it.hash),
            )
        }

    override suspend fun put(path: String, record: AncestorRecord?) {
        if (record == null) {
            dao.delete(pairId, path)
        } else {
            dao.upsert(
                AncestorEntity(
                    pairId = pairId,
                    path = path,
                    hash = record.local.contentHash,
                    localSize = record.local.size,
                    localMtime = record.local.mtimeMillis,
                    remoteSize = record.remote.size,
                    remoteMtime = record.remote.mtimeMillis,
                ),
            )
        }
    }
}

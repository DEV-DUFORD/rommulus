package com.romm.androidtv.romm.save

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Persists [SaveSyncStatus] as its plain enum name; Room has no native enum column type. */
class SaveSyncStatusConverters {
    @TypeConverter
    fun fromStatus(status: SaveSyncStatus): String = status.name

    @TypeConverter
    fun toStatus(raw: String): SaveSyncStatus = SaveSyncStatus.valueOf(raw)
}

/** Persists [PendingOperationType]/[PendingOperationStatus] as their plain enum names. */
class PendingOperationConverters {
    @TypeConverter
    fun fromType(type: PendingOperationType): String = type.name

    @TypeConverter
    fun toType(raw: String): PendingOperationType = PendingOperationType.valueOf(raw)

    @TypeConverter
    fun fromStatus(status: PendingOperationStatus): String = status.name

    @TypeConverter
    fun toStatus(raw: String): PendingOperationStatus = PendingOperationStatus.valueOf(raw)
}

/**
 * Local save-replica + upload-queue database (LIBRETRO_REFACTOR.md sections
 * 11.1 and 11.4). Separate from [com.romm.androidtv.cache.CacheDatabase] on
 * purpose: that one is an evictable, non-authoritative index over cached
 * ROM/firmware bytes; this one is durable, never-evicted save metadata and
 * queued-upload state. [SaveReplicaEntity] and [PendingOperationEntity] live
 * in the same database (not split across two) because the sync coordinator
 * (Milestone 6, `p5-coordinator`) will need to read/write both within a
 * single transaction (e.g. "mark this save SYNCED and its upload operation
 * SUCCEEDED" must be atomic).
 */
@Database(
    entities = [SaveReplicaEntity::class, PendingOperationEntity::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(SaveSyncStatusConverters::class, PendingOperationConverters::class)
abstract class SaveDatabase : RoomDatabase() {
    abstract fun saveReplicaDao(): SaveReplicaDao
    abstract fun pendingOperationDao(): PendingOperationDao

    companion object {
        const val DB_NAME = "romm_saves.db"

        /**
         * Non-destructive migration v1 → v2: adds three nullable columns to
         * `pending_operations` (origin, uploadFileName, sessionId). Existing rows
         * retain their original data; new columns default to NULL. Legacy rows
         * whose new nullable metadata cannot be reconstructed fail explicitly
         * at executor time rather than being guessed here.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE pending_operations ADD COLUMN origin TEXT DEFAULT NULL",
                )
                database.execSQL(
                    "ALTER TABLE pending_operations ADD COLUMN uploadFileName TEXT DEFAULT NULL",
                )
                database.execSQL(
                    "ALTER TABLE pending_operations ADD COLUMN sessionId INTEGER DEFAULT NULL",
                )
            }
        }
    }
}

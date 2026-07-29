package com.romm.androidtv.romm.save

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/** Persists [SaveSyncStatus] as its plain enum name; Room has no native enum column type. */
class SaveSyncStatusConverters {
    @TypeConverter
    fun fromStatus(status: SaveSyncStatus): String = status.name

    @TypeConverter
    fun toStatus(raw: String): SaveSyncStatus = SaveSyncStatus.valueOf(raw)
}

/**
 * Local save-replica database (LIBRETRO_REFACTOR.md section 11.1). Separate
 * from [com.romm.androidtv.cache.CacheDatabase] on purpose: that one is an
 * evictable, non-authoritative index over cached ROM/firmware bytes; this one
 * is durable, never-evicted metadata for the SRAM files under `files/saves/`.
 *
 * `exportSchema` is off for now: this is the first Room database in the app
 * (added this phase) and there is no released build depending on schema
 * history yet. Turn it on and start committing schema JSON files under
 * `app/schemas/` the first time a migration actually matters — i.e. before
 * this ships to anyone with existing local data.
 */
@Database(entities = [SaveReplicaEntity::class], version = 1, exportSchema = false)
@TypeConverters(SaveSyncStatusConverters::class)
abstract class SaveDatabase : RoomDatabase() {
    abstract fun saveReplicaDao(): SaveReplicaDao

    companion object {
        const val DB_NAME = "romm_saves.db"
    }
}

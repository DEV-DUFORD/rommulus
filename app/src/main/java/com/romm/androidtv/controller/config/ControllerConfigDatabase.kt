package com.romm.androidtv.controller.config

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Dedicated Room database for per-core controller configuration overrides
 * (CONTROLLER_SETTINGS.md Architecture section 2). Kept separate from
 * [com.romm.androidtv.romm.save.SaveDatabase] on purpose: that database is durable
 * save/sync metadata, while this is unrelated UI configuration that must be readable by
 * both the main process and the separate `:emulation` process.
 *
 * Multi-instance invalidation is **required** here: [android.app.Activity]
 * `MainActivity` and `EmulationActivity` run in different processes and must observe the
 * same saved configuration. Room's [RoomDatabase.Builder.setMultiInstanceInvalidationEnabled]
 * invalidates each open instance when another writes, so the [Flow] queries in
 * [ControllerBindingDao] re-emit across processes.
 */
@Database(
    entities = [ControllerBindingEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class ControllerConfigDatabase : RoomDatabase() {
    abstract fun controllerBindingDao(): ControllerBindingDao

    companion object {
        const val DB_NAME = "controller_config.db"

        @Volatile
        private var instance: ControllerConfigDatabase? = null

        /**
         * Thread-safe singleton. Uses [Context.getApplicationContext] so the database is never
         * tied to an activity lifecycle. Enables multi-instance invalidation so the main and
         * `:emulation` processes observe the same rows.
         */
        fun database(context: Context): ControllerConfigDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ControllerConfigDatabase::class.java,
                    DB_NAME,
                ).addMigrations(MIGRATION_1_2)
                    .enableMultiInstanceInvalidation()
                    .build().also { instance = it }
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                        CREATE TABLE controller_bindings_v2 (
                            coreId TEXT NOT NULL,
                            playerIndex INTEGER NOT NULL,
                            controlId TEXT NOT NULL,
                            bindingSlot INTEGER NOT NULL,
                            bindingType TEXT NOT NULL,
                            inputCode INTEGER NOT NULL,
                            polarity INTEGER,
                            schemaVersion INTEGER NOT NULL,
                            PRIMARY KEY(coreId, playerIndex, controlId, bindingSlot)
                        )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                        INSERT INTO controller_bindings_v2 (
                            coreId, playerIndex, controlId, bindingSlot,
                            bindingType, inputCode, polarity, schemaVersion
                        )
                        SELECT coreId, playerIndex, controlId, 0,
                               bindingType, inputCode, polarity, schemaVersion
                        FROM controller_bindings
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE controller_bindings")
                db.execSQL("ALTER TABLE controller_bindings_v2 RENAME TO controller_bindings")
            }
        }
    }
}

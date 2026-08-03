package com.romm.androidtv.controller.config

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    version = 1,
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
                ).enableMultiInstanceInvalidation()
                    .build().also { instance = it }
            }
        }
    }
}

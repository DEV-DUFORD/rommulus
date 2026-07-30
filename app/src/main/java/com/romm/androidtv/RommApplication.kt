package com.romm.androidtv

import android.app.Application
import androidx.room.Room
import androidx.work.Configuration
import com.romm.androidtv.sync.RommWorkerFactory
import com.romm.androidtv.romm.save.SaveDatabase

/**
 * Main-process Application class (LIBRETRO_REFACTOR.md section 11.4).
 * Provides WorkManager's [Configuration.Provider] so the custom [RommWorkerFactory]
 * is used for all worker instantiation. Also holds the singleton [SaveDatabase].
 */
class RommApplication : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration = Configuration.Builder()
        .setWorkerFactory(RommWorkerFactory(this) { RommWorkerFactory.buildProductionExecutor(this) })
        .build()

    companion object {
        @Volatile
        private var instance: SaveDatabase? = null

        fun database(context: android.content.Context): SaveDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SaveDatabase::class.java,
                    SaveDatabase.DB_NAME,
                ).addMigrations(SaveDatabase.MIGRATION_1_2, SaveDatabase.MIGRATION_2_3, SaveDatabase.MIGRATION_3_4)
                 .build().also { instance = it }
            }
        }
    }
}

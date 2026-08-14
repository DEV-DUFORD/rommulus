package com.romm.androidtv

import android.app.Application
import androidx.room.Room
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import com.romm.androidtv.network.AndroidLogSink
import com.romm.androidtv.network.RommLog
import com.romm.androidtv.sync.RommWorkerFactory
import com.romm.androidtv.romm.save.SaveDatabase

/**
 * Main-process Application class (LIBRETRO_REFACTOR.md section 11.4).
 * Provides WorkManager's [Configuration.Provider] so the custom [RommWorkerFactory]
 * is used for all worker instantiation. Also holds the singleton [SaveDatabase].
 * Implements Coil's [ImageLoaderFactory] to register [SvgDecoder] app-wide, since
 * RomM's bundled platform icons are served as SVGs.
 */
class RommApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        RommLog.sink = AndroidLogSink
    }

    override val workManagerConfiguration: Configuration = Configuration.Builder()
        .setWorkerFactory(RommWorkerFactory(this) { RommWorkerFactory.buildProductionExecutor(this) })
        .build()

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(SvgDecoder.Factory()) }
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

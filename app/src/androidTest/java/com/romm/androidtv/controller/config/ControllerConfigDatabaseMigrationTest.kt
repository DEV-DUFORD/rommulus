package com.romm.androidtv.controller.config

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControllerConfigDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "controller-config-migration-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun version1RowsMigrateIntoPrimarySlot() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE controller_bindings (
                                    coreId TEXT NOT NULL,
                                    playerIndex INTEGER NOT NULL,
                                    controlId TEXT NOT NULL,
                                    bindingType TEXT NOT NULL,
                                    inputCode INTEGER NOT NULL,
                                    polarity INTEGER,
                                    schemaVersion INTEGER NOT NULL,
                                    PRIMARY KEY(coreId, playerIndex, controlId)
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                INSERT INTO controller_bindings VALUES
                                    ('snes9x', 0, 'button_a', 'KEY', 96, NULL, 1)
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        helper.writableDatabase
        helper.close()

        val database = Room.databaseBuilder(
            context,
            ControllerConfigDatabase::class.java,
            databaseName,
        ).addMigrations(ControllerConfigDatabase.MIGRATION_1_2)
            .build()

        val rows = runBlocking {
            database.controllerBindingDao().loadForCore("snes9x")
        }
        database.close()

        assertEquals(1, rows.size)
        assertEquals(BindingSlot.PRIMARY.index, rows.single().bindingSlot)
        assertEquals(PhysicalBinding.Key(96), ControllerBindingCodec.decode(rows.single()))
    }
}

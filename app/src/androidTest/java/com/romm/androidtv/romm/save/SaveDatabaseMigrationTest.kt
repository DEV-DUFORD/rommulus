package com.romm.androidtv.romm.save

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Instrumented migration test: verifies Room's auto-migration v1 → v2 adds the three new nullable
 * columns to pending_operations without destroying existing data. Runs on real device/emulator
 * (no Robolectric in this repo — see HANDOFF.md Session 8).
 */
class SaveDatabaseMigrationTest {

    private lateinit var db: SaveDatabase

    @After
    fun closeDb() {
        try { db.close() } catch (_: Exception) { /* no-op */ }
    }

    @Test
    fun migration_1_to_2_adds_nullable_columns() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()

            // Step 1: Create v1 schema manually via raw SQLite
            val rawDb = context.openOrCreateDatabase("migration_test.db", android.content.Context.MODE_PRIVATE, null)

            // Room requires android_metadata to determine DB version and run migrations.
            // Without it, Room cannot identify the database as v1 and will throw an
            // invalid-schema error instead of executing MIGRATION_1_2.
            rawDb.execSQL(
                """CREATE TABLE IF NOT EXISTS `android_metadata` (`locale` TEXT)""",
            )
            rawDb.execSQL("INSERT INTO `android_metadata` VALUES ('en')")
            rawDb.execSQL(
                """PRAGMA user_version = 1""",
            )

            rawDb.execSQL(
                """CREATE TABLE IF NOT EXISTS `save_replicas` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `serverKey` TEXT NOT NULL,
                    `userKey` TEXT NOT NULL,
                    `romId` INTEGER NOT NULL,
                    `romHash` TEXT NOT NULL,
                    `slot` TEXT NOT NULL,
                    `coreId` TEXT NOT NULL,
                    `coreBuildRevision` TEXT NOT NULL,
                    `expectedSramSizeBytes` INTEGER NOT NULL,
                    `localHash` TEXT,
                    `localSizeBytes` INTEGER,
                    `localWrittenAtEpochMs` INTEGER,
                    `rommSaveId` INTEGER,
                    `serverHash` TEXT,
                    `serverSizeBytes` INTEGER,
                    `serverUpdatedAtEpochMs` INTEGER,
                    `syncStatus` TEXT NOT NULL,
                    `lastError` TEXT
                )"""
            )
            rawDb.execSQL(
                """CREATE TABLE IF NOT EXISTS `pending_operations` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `serverKey` TEXT NOT NULL,
                    `userKey` TEXT NOT NULL,
                    `romId` INTEGER NOT NULL,
                    `romHash` TEXT NOT NULL,
                    `slot` TEXT NOT NULL,
                    `operationType` TEXT NOT NULL,
                    `localGenerationEpochMs` INTEGER NOT NULL,
                    `status` TEXT NOT NULL,
                    `attemptCount` INTEGER NOT NULL,
                    `lastError` TEXT,
                    `lastHttpCode` INTEGER,
                    `createdAtEpochMs` INTEGER NOT NULL,
                    `updatedAtEpochMs` INTEGER NOT NULL
                )"""
            )

            // Insert a test row at v1 (no origin/uploadFileName/sessionId)
            rawDb.execSQL(
                """INSERT INTO pending_operations
                   (serverKey, userKey, romId, romHash, slot, operationType,
                    localGenerationEpochMs, status, attemptCount, createdAtEpochMs, updatedAtEpochMs)
                   VALUES ('srv', 'usr', 42, 'hash1', 'autosave', 'UPLOAD', 1000, 'PENDING', 0, 1000, 1000)"""
            )
            rawDb.close()

            // Step 2: Open with Room v2 + manual migration
            db = Room.databaseBuilder(
                context,
                SaveDatabase::class.java,
                "migration_test.db",
            ).addMigrations(SaveDatabase.MIGRATION_1_2)
             .allowMainThreadQueries()
             .build()

            // Verify the migrated row has null new columns but preserved original data
            val ops = db.pendingOperationDao().findByStatus(PendingOperationStatus.PENDING)
            assertEquals(1, ops.size)
            val op = ops[0]
            assertEquals("srv", op.serverKey)
            assertEquals("usr", op.userKey)
            assertEquals(42L, op.romId)
            assertNull(op.origin)
            assertNull(op.uploadFileName)
            assertNull(op.sessionId)

            // Cleanup
            context.deleteDatabase("migration_test.db")
        }
    }
}

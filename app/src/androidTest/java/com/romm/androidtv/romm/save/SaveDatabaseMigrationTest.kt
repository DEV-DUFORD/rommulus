package com.romm.androidtv.romm.save

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            // The Entity declares this index; Room's compiled v4 schema expects it.
            // MIGRATION_1_2 does not create it (production migration predates the index),
            // so we seed it here so the final migrated schema matches Room's expectation.
            rawDb.execSQL(
                """CREATE INDEX IF NOT EXISTS
                    `index_pending_operations_serverKey_userKey_romId_romHash_slot_operationType`
                    ON `pending_operations` (`serverKey`, `userKey`, `romId`, `romHash`, `slot`, `operationType`)""",
            )

            // Insert a test row at v1 (no origin/uploadFileName/sessionId)
            rawDb.execSQL(
                """INSERT INTO pending_operations
                   (serverKey, userKey, romId, romHash, slot, operationType,
                    localGenerationEpochMs, status, attemptCount, createdAtEpochMs, updatedAtEpochMs)
                   VALUES ('srv', 'usr', 42, 'hash1', 'autosave', 'UPLOAD', 1000, 'PENDING', 0, 1000, 1000)"""
            )
            rawDb.close()

            // Step 2: Open with Room v4 + all registered migrations (Room requires a complete
            // migration chain up to its declared schema version; it runs only the subset needed).
            db = Room.databaseBuilder(
                context,
                SaveDatabase::class.java,
                "migration_test.db",
            ).addMigrations(
                SaveDatabase.MIGRATION_1_2,
                SaveDatabase.MIGRATION_2_3,
                SaveDatabase.MIGRATION_3_4,
            )
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

    @Test
    fun migration_2_to_3_makes_expectedSramSizeBytes_nullable() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val dbName = "migration_2_3_test.db"

            // Step 1: Create v2 schema manually via raw SQLite
            val rawDb = context.openOrCreateDatabase(dbName, android.content.Context.MODE_PRIVATE, null)

            rawDb.execSQL(
                """CREATE TABLE IF NOT EXISTS `android_metadata` (`locale` TEXT)""",
            )
            rawDb.execSQL("INSERT INTO `android_metadata` VALUES ('en')")
            rawDb.execSQL("PRAGMA user_version = 2")

            // save_replicas v2: expectedSramSizeBytes INTEGER NOT NULL
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
                )""",
            )
            rawDb.execSQL(
                """CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_save_replicas_serverKey_userKey_romId_romHash_slot`
                    ON `save_replicas` (`serverKey`, `userKey`, `romId`, `romHash`, `slot`)""",
            )

            // pending_operations v2 (includes origin, uploadFileName, sessionId from v1->v2)
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
                    `updatedAtEpochMs` INTEGER NOT NULL,
                    `origin` TEXT DEFAULT NULL,
                    `uploadFileName` TEXT DEFAULT NULL,
                    `sessionId` INTEGER DEFAULT NULL
                )""",
            )
            rawDb.execSQL(
                """CREATE INDEX IF NOT EXISTS
                    `index_pending_operations_serverKey_userKey_romId_romHash_slot_operationType`
                    ON `pending_operations` (`serverKey`, `userKey`, `romId`, `romHash`, `slot`, `operationType`)""",
            )

            // Insert a test save_replica row at v2 (expectedSramSizeBytes = 4096)
            rawDb.execSQL(
                """INSERT INTO save_replicas
                    (serverKey, userKey, romId, romHash, slot,
                     coreId, coreBuildRevision, expectedSramSizeBytes,
                     syncStatus)
                    VALUES ('srv', 'usr', 99, 'sha256abc', 'autosave',
                            'libretro-nestopia', 'v1.80', 4096,
                            'SYNCED')""",
            )

            // Insert a test pending_operations row at v2
            rawDb.execSQL(
                """INSERT INTO pending_operations
                    (serverKey, userKey, romId, romHash, slot, operationType,
                     localGenerationEpochMs, status, attemptCount,
                     createdAtEpochMs, updatedAtEpochMs, origin)
                    VALUES ('srv', 'usr', 99, 'sha256abc', 'autosave', 'UPLOAD',
                            2000, 'PENDING', 0, 2000, 2000, 'https://romm.test')""",
            )

            rawDb.close()

            // Step 2: Open with Room v4 + all registered migrations (Room requires a complete
            // migration chain up to its declared schema version; it runs only the subset needed).
            db = Room.databaseBuilder(
                context,
                SaveDatabase::class.java,
                dbName,
            ).addMigrations(
                SaveDatabase.MIGRATION_1_2,
                SaveDatabase.MIGRATION_2_3,
                SaveDatabase.MIGRATION_3_4,
            )
                .allowMainThreadQueries()
                .build()

            // Verify save_replica: expectedSramSizeBytes preserved as 4096
            val replicas = db.saveReplicaDao().findByStatus("srv", "usr", SaveSyncStatus.SYNCED)
            assertEquals(1, replicas.size)
            val replica = replicas[0]
            assertEquals("srv", replica.serverKey)
            assertEquals("usr", replica.userKey)
            assertEquals(99L, replica.romId)
            assertEquals("sha256abc", replica.romHash)
            assertEquals("autosave", replica.slot)
            assertEquals("libretro-nestopia", replica.coreId)
            assertEquals("v1.80", replica.coreBuildRevision)
            assertEquals(4096L, replica.expectedSramSizeBytes)
            assertEquals(SaveSyncStatus.SYNCED, replica.syncStatus)

            // Verify pending_operations preserved
            val ops = db.pendingOperationDao().findByStatus(PendingOperationStatus.PENDING)
            assertEquals(1, ops.size)
            assertEquals("https://romm.test", ops[0].origin)

            // Cleanup
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun migration_3_to_4_adds_negotiate_columns() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val dbName = "migration_3_4_test.db"

            // Step 1: Create v3 schema manually via raw SQLite
            val rawDb = context.openOrCreateDatabase(dbName, android.content.Context.MODE_PRIVATE, null)

            rawDb.execSQL(
                """CREATE TABLE IF NOT EXISTS `android_metadata` (`locale` TEXT)""",
            )
            rawDb.execSQL("INSERT INTO `android_metadata` VALUES ('en')")
            rawDb.execSQL("PRAGMA user_version = 3")

            // save_replicas v3: expectedSramSizeBytes nullable
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
                    `expectedSramSizeBytes` INTEGER,
                    `localHash` TEXT,
                    `localSizeBytes` INTEGER,
                    `localWrittenAtEpochMs` INTEGER,
                    `rommSaveId` INTEGER,
                    `serverHash` TEXT,
                    `serverSizeBytes` INTEGER,
                    `serverUpdatedAtEpochMs` INTEGER,
                    `syncStatus` TEXT NOT NULL,
                    `lastError` TEXT
                )""",
            )
            rawDb.execSQL(
                """CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_save_replicas_serverKey_userKey_romId_romHash_slot`
                    ON `save_replicas` (`serverKey`, `userKey`, `romId`, `romHash`, `slot`)""",
            )

            // pending_operations v3 (includes origin, uploadFileName, sessionId from v1->v2)
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
                    `updatedAtEpochMs` INTEGER NOT NULL,
                    `origin` TEXT DEFAULT NULL,
                    `uploadFileName` TEXT DEFAULT NULL,
                    `sessionId` INTEGER DEFAULT NULL
                )""",
            )
            rawDb.execSQL(
                """CREATE INDEX IF NOT EXISTS
                    `index_pending_operations_serverKey_userKey_romId_romHash_slot_operationType`
                    ON `pending_operations` (`serverKey`, `userKey`, `romId`, `romHash`, `slot`, `operationType`)""",
            )

            // Insert a UPLOAD operation at v3
            rawDb.execSQL(
                """INSERT INTO pending_operations
                    (serverKey, userKey, romId, romHash, slot, operationType,
                     localGenerationEpochMs, status, attemptCount,
                     createdAtEpochMs, updatedAtEpochMs, origin, uploadFileName, sessionId)
                    VALUES ('srv', 'usr', 77, 'sha256def', 'autosave', 'UPLOAD',
                            3000, 'PENDING', 0, 3000, 3000, 'https://romm.test', 'save.srm', 99)""",
            )

            rawDb.close()

            // Step 2: Open with Room v4 + all migrations
            db = Room.databaseBuilder(
                context,
                SaveDatabase::class.java,
                dbName,
            ).addMigrations(SaveDatabase.MIGRATION_1_2, SaveDatabase.MIGRATION_2_3, SaveDatabase.MIGRATION_3_4)
                .allowMainThreadQueries()
                .build()

            // Verify pending_operations: original data preserved, new negotiate columns are null
            val ops = db.pendingOperationDao().findByStatus(PendingOperationStatus.PENDING)
            assertEquals(1, ops.size)
            val op = ops[0]
            assertEquals("srv", op.serverKey)
            assertEquals("usr", op.userKey)
            assertEquals(77L, op.romId)
            assertEquals("sha256def", op.romHash)
            assertEquals("autosave", op.slot)
            assertEquals(PendingOperationType.UPLOAD, op.operationType)
            assertEquals(3000L, op.localGenerationEpochMs)
            assertEquals(PendingOperationStatus.PENDING, op.status)
            assertEquals(0, op.attemptCount)
            assertEquals("https://romm.test", op.origin)
            assertEquals("save.srm", op.uploadFileName)
            assertEquals(99L, op.sessionId)
            // New v4 columns are null for existing rows.
            assertNull(op.negotiateFileName)
            assertNull(op.negotiateCoreId)
            assertNull(op.negotiateCoreBuildRevision)

            // Cleanup
            context.deleteDatabase(dbName)
        }
    }
}

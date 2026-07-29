package com.romm.androidtv.romm.save

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * Verifies [SaveReplicaDao] against a real, in-memory SQLite database (Room
 * requires an actual Android SQLite implementation, which only an
 * instrumented test provides in this project — there is no Robolectric
 * dependency here; see HANDOFF.md's Phase 5 notes).
 *
 * This is a plain JUnit4 `@RunWith(AndroidJUnit4::class)` instrumented test,
 * matching every other file under `app/src/androidTest`, not the JUnit5
 * convention used by the app's local unit tests.
 */
@RunWith(AndroidJUnit4::class)
class SaveReplicaDaoInstrumentedTest {

    private lateinit var db: SaveDatabase
    private lateinit var dao: SaveReplicaDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SaveDatabase::class.java).build()
        dao = db.saveReplicaDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sample(
        romHash: String = "abc123",
        syncStatus: SaveSyncStatus = SaveSyncStatus.UNSYNCED,
    ) = SaveReplicaEntity(
        serverKey = "romm.example.com",
        userKey = "alice",
        romId = 42L,
        romHash = romHash,
        slot = "autosave",
        coreId = "sameboy",
        coreBuildRevision = "sameboy-v0.16.2",
        expectedSramSizeBytes = 8192L,
        syncStatus = syncStatus,
    )

    @Test
    fun findByScopeReturnsNullWhenNoRecordExists() {
        runBlocking {
            val found = dao.findByScope("romm.example.com", "alice", 42L, "abc123", "autosave")
            assertNull(found)
        }
    }

    @Test
    fun upsertThenFindByScopeRoundTripsAllFields() {
        runBlocking {
            val entity = sample().copy(
                localHash = "deadbeef",
                localSizeBytes = 8192L,
                localWrittenAtEpochMs = 1_000L,
                rommSaveId = 7L,
                serverHash = "deadbeef",
                serverSizeBytes = 8192L,
                serverUpdatedAtEpochMs = 1_000L,
                syncStatus = SaveSyncStatus.SYNCED,
            )
            dao.upsert(entity)

            val found = dao.findByScope("romm.example.com", "alice", 42L, "abc123", "autosave")
            assertTrue(found != null)
            assertEquals("deadbeef", found?.localHash)
            assertEquals(8192L, found?.localSizeBytes)
            assertEquals(7L, found?.rommSaveId)
            assertEquals(SaveSyncStatus.SYNCED, found?.syncStatus)
        }
    }

    @Test
    fun upsertOnSameScopeReplacesRatherThanDuplicates() {
        runBlocking {
            dao.upsert(sample(syncStatus = SaveSyncStatus.UNSYNCED))
            dao.upsert(sample(syncStatus = SaveSyncStatus.CONFLICT))

            val bySyncedStatus = dao.findByStatus("romm.example.com", "alice", SaveSyncStatus.UNSYNCED)
            val byConflictStatus = dao.findByStatus("romm.example.com", "alice", SaveSyncStatus.CONFLICT)

            assertTrue(bySyncedStatus.isEmpty())
            assertEquals(1, byConflictStatus.size)
        }
    }

    @Test
    fun differentRomHashIsAStructurallyDifferentRecord() {
        runBlocking {
            dao.upsert(sample(romHash = "hash-a"))
            dao.upsert(sample(romHash = "hash-b"))

            val a = dao.findByScope("romm.example.com", "alice", 42L, "hash-a", "autosave")
            val b = dao.findByScope("romm.example.com", "alice", 42L, "hash-b", "autosave")

            assertTrue(a != null && b != null)
            assertTrue(a?.id != b?.id)
        }
    }

    @Test
    fun deleteByScopeRemovesOnlyThatRecord() {
        runBlocking {
            dao.upsert(sample(romHash = "hash-a"))
            dao.upsert(sample(romHash = "hash-b"))

            dao.deleteByScope("romm.example.com", "alice", 42L, "hash-a", "autosave")

            assertNull(dao.findByScope("romm.example.com", "alice", 42L, "hash-a", "autosave"))
            assertTrue(dao.findByScope("romm.example.com", "alice", 42L, "hash-b", "autosave") != null)
        }
    }

    @Test
    fun findByStatusIsScopedPerServerAndUser() {
        runBlocking {
            dao.upsert(sample().copy(userKey = "alice", syncStatus = SaveSyncStatus.PENDING_UPLOAD))
            dao.upsert(sample().copy(userKey = "bob", syncStatus = SaveSyncStatus.PENDING_UPLOAD))

            val aliceResults = dao.findByStatus("romm.example.com", "alice", SaveSyncStatus.PENDING_UPLOAD)
            assertEquals(1, aliceResults.size)
            assertEquals("alice", aliceResults.first().userKey)
        }
    }
}

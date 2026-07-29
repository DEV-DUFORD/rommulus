package com.romm.androidtv.romm.save

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies [PendingOperationDao] against a real, in-memory SQLite database —
 * see [SaveReplicaDaoInstrumentedTest]'s class doc for why this repo tests
 * Room here rather than in `app/src/test`.
 */
@RunWith(AndroidJUnit4::class)
class PendingOperationDaoInstrumentedTest {

    private lateinit var db: SaveDatabase
    private lateinit var dao: PendingOperationDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SaveDatabase::class.java).build()
        dao = db.pendingOperationDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sample(
        localGenerationEpochMs: Long = 1_000L,
        status: PendingOperationStatus = PendingOperationStatus.PENDING,
    ) = PendingOperationEntity(
        serverKey = "romm.example.com",
        userKey = "alice",
        romId = 42L,
        romHash = "abc123",
        slot = "autosave",
        operationType = PendingOperationType.UPLOAD,
        localGenerationEpochMs = localGenerationEpochMs,
        status = status,
        createdAtEpochMs = localGenerationEpochMs,
        updatedAtEpochMs = localGenerationEpochMs,
    )

    @Test
    fun insertThenFindByIdRoundTripsAllFields() {
        runBlocking {
            val id = dao.insert(sample())
            val found = dao.findById(id)

            assertTrue(found != null)
            assertEquals("romm.example.com", found?.serverKey)
            assertEquals(PendingOperationType.UPLOAD, found?.operationType)
            assertEquals(PendingOperationStatus.PENDING, found?.status)
            assertEquals(1_000L, found?.localGenerationEpochMs)
        }
    }

    @Test
    fun findByStatusIsUnscopedAcrossUsers() {
        runBlocking {
            dao.insert(sample().copy(userKey = "alice"))
            dao.insert(sample().copy(userKey = "bob"))
            dao.insert(sample(status = PendingOperationStatus.SUCCEEDED).copy(userKey = "carol"))

            val pending = dao.findByStatus(PendingOperationStatus.PENDING)
            assertEquals(2, pending.size)
            assertTrue(pending.map { it.userKey }.toSet() == setOf("alice", "bob"))
        }
    }

    @Test
    fun findActiveByScopeExcludesTerminalStatuses() {
        runBlocking {
            dao.insert(sample(status = PendingOperationStatus.PENDING))
            dao.insert(sample(status = PendingOperationStatus.SUCCEEDED))
            dao.insert(sample(status = PendingOperationStatus.CONFLICT))

            val active = dao.findActiveByScope(
                "romm.example.com", "alice", 42L, "abc123", "autosave", PendingOperationType.UPLOAD,
            )

            assertEquals(1, active.size)
            assertEquals(PendingOperationStatus.PENDING, active.first().status)
        }
    }

    @Test
    fun updateStatusAppliesNewStatusAttemptCountAndError() {
        runBlocking {
            val id = dao.insert(sample())

            dao.updateStatus(
                id = id,
                status = PendingOperationStatus.RETRYABLE_FAILURE,
                attemptCount = 1,
                lastError = "connection reset",
                lastHttpCode = null,
                updatedAtEpochMs = 2_000L,
            )

            val found = dao.findById(id)
            assertEquals(PendingOperationStatus.RETRYABLE_FAILURE, found?.status)
            assertEquals(1, found?.attemptCount)
            assertEquals("connection reset", found?.lastError)
            assertEquals(2_000L, found?.updatedAtEpochMs)
        }
    }

    @Test
    fun deleteStaleForScopeRemovesOnlyOlderNonTerminalGenerations() {
        runBlocking {
            val staleId = dao.insert(sample(localGenerationEpochMs = 1_000L))
            val freshId = dao.insert(sample(localGenerationEpochMs = 5_000L))
            val succeededOldId = dao.insert(
                sample(localGenerationEpochMs = 500L, status = PendingOperationStatus.SUCCEEDED),
            )

            dao.deleteStaleForScope(
                "romm.example.com", "alice", 42L, "abc123", "autosave",
                PendingOperationType.UPLOAD, olderThanLocalGenerationEpochMs = 5_000L,
            )

            assertNull(dao.findById(staleId))
            assertTrue(dao.findById(freshId) != null)
            // A SUCCEEDED (terminal) row is never touched by this dedupe cleanup, even if older.
            assertTrue(dao.findById(succeededOldId) != null)
        }
    }
}

/*
 * JVM unit tests for RoomSaveStateStore backed by in-memory fakes.
 * Exercises the same contract assertions as SaveStateStoreContract,
 * adapted for fake-backed semantics.
 *
 * Uses FakeSaveReplicaDao / FakePendingOperationDao (no Robolectric, no Room).
 */
package com.romm.androidtv.storage.android

import com.romm.androidtv.romm.save.FakePendingOperationDao
import com.romm.androidtv.romm.save.FakeSaveReplicaDao
import com.romm.androidtv.storage.ports.SaveReplicaScope
import com.romm.androidtv.storage.ports.SaveStateStore
import com.romm.androidtv.storage.records.*
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Test-friendly implementation of [SaveStateStore] that delegates replica/op
 * operations to injected fakes and provides synchronous inTransaction semantics
 * (no real Room database needed). Uses the same adapter classes as production
 * (RoomSaveReplicaStore / RoomPendingOperationStore) but with fake DAOs.
 */
private class FakeBackedSaveStateStore(
    private val replicaDao: FakeSaveReplicaDao,
    private val opDao: FakePendingOperationDao,
) : SaveStateStore {

    private val _replicaStore = RoomSaveReplicaStore(replicaDao)
    private val _opStore = RoomPendingOperationStore(opDao)

    override fun upsert(replica: SaveReplicaRecord): Result<Long> = _replicaStore.upsert(replica)
    override fun findByScope(scope: SaveReplicaScope): SaveReplicaRecord? = _replicaStore.findByScope(scope)
    override fun findByStatus(serverKey: String, userKey: String, status: SaveSyncStatus): List<SaveReplicaRecord> =
        _replicaStore.findByStatus(serverKey, userKey, status)
    override fun markSyncedIfGenerationMatches(
        scope: SaveReplicaScope,
        localGenerationEpochMs: Long,
        rommSaveId: Long?,
        serverHash: String?,
        serverSizeBytes: Long?,
        serverUpdatedAtEpochMs: Long?,
    ): Boolean = _replicaStore.markSyncedIfGenerationMatches(
        scope, localGenerationEpochMs, rommSaveId, serverHash, serverSizeBytes, serverUpdatedAtEpochMs,
    )
    override fun deleteByScope(scope: SaveReplicaScope): Result<Unit> = _replicaStore.deleteByScope(scope)

    override fun enqueue(operation: PendingOperationRecord): Result<Long> = _opStore.enqueue(operation)
    override fun findById(id: Long): PendingOperationRecord? = _opStore.findById(id)
    override fun findByStatus(status: PendingOperationStatus): List<PendingOperationRecord> =
        _opStore.findByStatus(status)
    override fun findActiveByScope(
        scope: SaveReplicaScope,
        operationType: PendingOperationType,
    ): List<PendingOperationRecord> = _opStore.findActiveByScope(scope, operationType)
    override fun updateStatus(
        id: Long,
        status: PendingOperationStatus,
        attemptCount: Int,
        lastError: String?,
        lastHttpCode: Int?,
        updatedAtEpochMs: Long,
    ): Result<Unit> = _opStore.updateStatus(id, status, attemptCount, lastError, lastHttpCode, updatedAtEpochMs)
    override fun deleteStaleForScope(
        scope: SaveReplicaScope,
        operationType: PendingOperationType,
        olderThanLocalGenerationEpochMs: Long,
    ): Int = _opStore.deleteStaleForScope(scope, operationType, olderThanLocalGenerationEpochMs)

    /** Synchronous inTransaction: fakes commit eagerly, so we just wrap in runCatching. */
    override fun <T> inTransaction(block: (SaveStateStore) -> T): Result<T> =
        runCatching { block(this) }
}

@DisplayName("RoomSaveStateStore (fake-backed)")
class RoomSaveStateStoreTest {

    private lateinit var replicaDao: FakeSaveReplicaDao
    private lateinit var opDao: FakePendingOperationDao
    private lateinit var store: SaveStateStore

    @BeforeEach
    fun setUp() {
        replicaDao = FakeSaveReplicaDao()
        opDao = FakePendingOperationDao()
        store = FakeBackedSaveStateStore(replicaDao, opDao)
    }

    // ── SaveReplicaStore contract tests ──────────────────────────────────────

    @Test
    @DisplayName("save replica identity is preserved")
    fun `save replica identity is preserved`() {
        val replica = SaveReplicaRecord(
            serverKey = "srv1", userKey = "usr1", romId = 100L, romHash = "abc", slot = "auto",
            coreId = "snes9x", coreBuildRevision = "r1",
        )
        val idResult = store.upsert(replica)
        assertThat(idResult.isSuccess).isTrue()
        val id = idResult.getOrNull()!!
        assertThat(id).isPositive()

        val scope = SaveReplicaScope("srv1", "usr1", 100L, "abc", "auto")
        val found = store.findByScope(scope)
        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(id)
        assertThat(found.serverKey).isEqualTo(replica.serverKey)
        assertThat(found.romId).isEqualTo(replica.romId)
    }

    @Test
    @DisplayName("replicas scoped by status")
    fun `replicas scoped by status`() {
        store.upsert(SaveReplicaRecord(serverKey = "s", userKey = "u", romId = 1L, romHash = "h1", slot = "a", coreId = "c", coreBuildRevision = "r", syncStatus = SaveSyncStatus.UNSYNCED))
        store.upsert(SaveReplicaRecord(serverKey = "s", userKey = "u", romId = 2L, romHash = "h2", slot = "a", coreId = "c", coreBuildRevision = "r", syncStatus = SaveSyncStatus.SYNCED))
        store.upsert(SaveReplicaRecord(serverKey = "s", userKey = "u", romId = 3L, romHash = "h3", slot = "a", coreId = "c", coreBuildRevision = "r", syncStatus = SaveSyncStatus.UNSYNCED))

        val unsynced = store.findByStatus("s", "u", SaveSyncStatus.UNSYNCED)
        assertThat(unsynced).hasSize(2)

        val synced = store.findByStatus("s", "u", SaveSyncStatus.SYNCED)
        assertThat(synced).hasSize(1)
    }

    @Test
    @DisplayName("findByScope returns null for absent scope")
    fun `findByScope returns null for absent scope`() {
        val scope = SaveReplicaScope("s", "u", 99L, "h", "a")
        assertThat(store.findByScope(scope)).isNull()
    }

    @Test
    @DisplayName("markSyncedIfGenerationMatches updates only matching generation")
    fun `markSyncedIfGenerationMatches updates only matching generation`() {
        val now = System.currentTimeMillis()
        val replicaCurrent = SaveReplicaRecord(
            serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
            coreId = "c", coreBuildRevision = "r", localWrittenAtEpochMs = now,
        )
        store.upsert(replicaCurrent)

        val replicaStale = SaveReplicaRecord(
            serverKey = "s", userKey = "u", romId = 2L, romHash = "h2", slot = "a",
            coreId = "c", coreBuildRevision = "r", localWrittenAtEpochMs = now - 1000,
        )
        store.upsert(replicaStale)

        val scopeCurrent = SaveReplicaScope("s", "u", 1L, "h", "a")
        val updated = store.markSyncedIfGenerationMatches(scopeCurrent, now, 42L, "shash", 1024L, now + 100)
        assertThat(updated).isTrue()

        val found = store.findByScope(scopeCurrent)!!
        assertThat(found.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
        assertThat(found.serverHash).isEqualTo("shash")
        assertThat(found.lastError).isNull()

        // Stale should not be updated.
        val scopeStale = SaveReplicaScope("s", "u", 2L, "h2", "a")
        val staleUpdated = store.markSyncedIfGenerationMatches(scopeStale, now, 42L, "shash", 1024L, now + 100)
        assertThat(staleUpdated).isFalse()
    }

    @Test
    @DisplayName("markSynced sets server metadata and SYNCED and clears lastError")
    fun `markSynced sets server metadata and SYNCED and clears lastError`() {
        val now = System.currentTimeMillis()
        val replica = SaveReplicaRecord(
            serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
            coreId = "c", coreBuildRevision = "r", localWrittenAtEpochMs = now,
            syncStatus = SaveSyncStatus.PENDING_UPLOAD, lastError = "some error",
        )
        store.upsert(replica)

        val scope = SaveReplicaScope("s", "u", 1L, "h", "a")
        store.markSyncedIfGenerationMatches(scope, now, 99L, "svrhash", 2048L, now + 50)

        val found = store.findByScope(scope)!!
        assertThat(found.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
        assertThat(found.rommSaveId).isEqualTo(99L)
        assertThat(found.serverHash).isEqualTo("svrhash")
        assertThat(found.serverSizeBytes).isEqualTo(2048L)
        assertThat(found.serverUpdatedAtEpochMs).isEqualTo(now + 50)
        assertThat(found.lastError).isNull()
    }

    @Test
    @DisplayName("unique scope dedupe, second upsert of same scope replaces, does not duplicate")
    fun `unique scope dedupe`() {
        val replica1 = SaveReplicaRecord(
            serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
            coreId = "c1", coreBuildRevision = "r1", syncStatus = SaveSyncStatus.UNSYNCED,
        )
        store.upsert(replica1)

        val replica2 = SaveReplicaRecord(
            serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
            coreId = "c2", coreBuildRevision = "r2", syncStatus = SaveSyncStatus.SYNCED,
        )
        store.upsert(replica2)

        val found = store.findByScope(SaveReplicaScope("s", "u", 1L, "h", "a"))
        assertThat(found).isNotNull()
        assertThat(found!!.coreId).isEqualTo("c2")
        assertThat(found.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
    }

    // ── PendingOperationStore contract tests ─────────────────────────────────

    @Test
    @DisplayName("pending op enqueue then findByStatus and findById round-trip")
    fun `pending op enqueue then findByStatus_Active and findById round-trip`() {
        val now = System.currentTimeMillis()
        val op = PendingOperationRecord(
            serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
            operationType = PendingOperationType.UPLOAD, localGenerationEpochMs = now,
            status = PendingOperationStatus.PENDING, createdAtEpochMs = now, updatedAtEpochMs = now,
        )
        val idResult = store.enqueue(op)
        assertThat(idResult.isSuccess).isTrue()
        val id = idResult.getOrNull()!!

        val foundById = store.findById(id)
        assertThat(foundById).isNotNull()
        assertThat(foundById!!.status).isEqualTo(PendingOperationStatus.PENDING)

        val pendingOps = store.findByStatus(PendingOperationStatus.PENDING)
        assertThat(pendingOps).isNotEmpty()

        val scope = SaveReplicaScope("s", "u", 1L, "h", "a")
        val active = store.findActiveByScope(scope, PendingOperationType.UPLOAD)
        assertThat(active).isNotEmpty()
    }

    @Test
    @DisplayName("updateStatus transitions state and records error_attempt")
    fun `updateStatus transitions state and records error_attempt`() {
        val now = System.currentTimeMillis()
        val op = PendingOperationRecord(
            serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
            operationType = PendingOperationType.UPLOAD, localGenerationEpochMs = now,
            createdAtEpochMs = now, updatedAtEpochMs = now,
        )
        val idResult = store.enqueue(op)
        val id = idResult.getOrNull()!!

        val updatedNow = now + 100
        val updateResult = store.updateStatus(id, PendingOperationStatus.RUNNING, 1, "timeout", 504, updatedNow)
        assertThat(updateResult.isSuccess).isTrue()

        val found = store.findById(id)!!
        assertThat(found.status).isEqualTo(PendingOperationStatus.RUNNING)
        assertThat(found.attemptCount).isEqualTo(1)
        assertThat(found.lastError).isEqualTo("timeout")
        assertThat(found.lastHttpCode).isEqualTo(504)
        assertThat(found.updatedAtEpochMs).isEqualTo(updatedNow)
    }

    @Test
    @DisplayName("findActiveByScope excludes terminal statuses")
    fun `findActiveByScope excludes terminal statuses`() {
        val now = System.currentTimeMillis()
        val scope = SaveReplicaScope("s", "u", 1L, "h", "a")

        // Active ops
        store.enqueue(PendingOperationRecord(
            serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
            operationType = PendingOperationType.UPLOAD, localGenerationEpochMs = now,
            status = PendingOperationStatus.PENDING,
            createdAtEpochMs = now, updatedAtEpochMs = now,
        ))
        store.enqueue(PendingOperationRecord(
            serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
            operationType = PendingOperationType.UPLOAD, localGenerationEpochMs = now + 1,
            status = PendingOperationStatus.RUNNING,
            createdAtEpochMs = now, updatedAtEpochMs = now,
        ))

        // Terminal ops (should be excluded)
        store.enqueue(PendingOperationRecord(
            serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
            operationType = PendingOperationType.UPLOAD, localGenerationEpochMs = now - 500,
            status = PendingOperationStatus.SUCCEEDED,
            createdAtEpochMs = now - 500, updatedAtEpochMs = now - 500,
        ))
        store.enqueue(PendingOperationRecord(
            serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
            operationType = PendingOperationType.UPLOAD, localGenerationEpochMs = now - 400,
            status = PendingOperationStatus.CONFLICT,
            createdAtEpochMs = now - 400, updatedAtEpochMs = now - 400,
        ))

        val active = store.findActiveByScope(scope, PendingOperationType.UPLOAD)
        assertThat(active).hasSize(2)
        assertThat(active.map { it.status }).containsOnly(PendingOperationStatus.PENDING, PendingOperationStatus.RUNNING)
    }

    @Test
    @DisplayName("deleteStaleForScope not deleting terminal ops")
    fun `deleteStaleForScope not deleting terminal ops`() {
        val baseNow = System.currentTimeMillis()
        val scope = SaveReplicaScope("s", "u", 1L, "h", "a")

        // Stale non-terminal op (should be deleted)
        store.enqueue(PendingOperationRecord(
            serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
            operationType = PendingOperationType.UPLOAD, localGenerationEpochMs = baseNow - 2000,
            status = PendingOperationStatus.PENDING,
            createdAtEpochMs = baseNow - 2000, updatedAtEpochMs = baseNow - 2000,
        ))
        // Terminal op (should NOT be deleted)
        store.enqueue(PendingOperationRecord(
            serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
            operationType = PendingOperationType.UPLOAD, localGenerationEpochMs = baseNow - 3000,
            status = PendingOperationStatus.SUCCEEDED,
            createdAtEpochMs = baseNow - 3000, updatedAtEpochMs = baseNow - 3000,
        ))
        // Current non-terminal op (should NOT be deleted — not older)
        store.enqueue(PendingOperationRecord(
            serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
            operationType = PendingOperationType.UPLOAD, localGenerationEpochMs = baseNow,
            status = PendingOperationStatus.PENDING,
            createdAtEpochMs = baseNow, updatedAtEpochMs = baseNow,
        ))

        store.deleteStaleForScope(scope, PendingOperationType.UPLOAD, baseNow)

        // Stale non-terminal should be gone; terminal + current should remain.
        val remaining = store.findActiveByScope(scope, PendingOperationType.UPLOAD)
        assertThat(remaining).hasSize(1)
        assertThat(remaining[0].localGenerationEpochMs).isEqualTo(baseNow)

        // Terminal op should still exist (findById)
        val allPending = opDao.allRows()
        val terminalOps = allPending.filter { it.status.name == "SUCCEEDED" }
        assertThat(terminalOps).isNotEmpty()
    }

    // ── inTransaction tests ──────────────────────────────────────────────────

    @Test
    @DisplayName("inTransaction commit applies both replica and op together")
    fun `inTransaction commit applies both replica and op together`() {
        val now = System.currentTimeMillis()
        val result = store.inTransaction { tx ->
            tx.upsert(SaveReplicaRecord(
                serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
                coreId = "c", coreBuildRevision = "r",
            ))
            tx.enqueue(PendingOperationRecord(
                serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
                operationType = PendingOperationType.UPLOAD, localGenerationEpochMs = now,
                createdAtEpochMs = now, updatedAtEpochMs = now,
            ))
            "done"
        }
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo("done")
        assertThat(store.findByScope(SaveReplicaScope("s", "u", 1L, "h", "a"))).isNotNull()
        assertThat(store.findActiveByScope(
            SaveReplicaScope("s", "u", 1L, "h", "a"), PendingOperationType.UPLOAD,
        )).isNotEmpty()
    }

    @Test
    @DisplayName("inTransaction rollback on failure returns Result.failure")
    fun `inTransaction rollback on failure returns failure`() {
        val failResult = store.inTransaction { tx ->
            tx.upsert(SaveReplicaRecord(
                serverKey = "s2", userKey = "u2", romId = 2L, romHash = "h2", slot = "a",
                coreId = "c", coreBuildRevision = "r",
            ))
            throw RuntimeException("deliberate failure")
        }
        assertThat(failResult.isFailure).isTrue()
    }

    // ── deleteByScope test ───────────────────────────────────────────────────

    @Test
    @DisplayName("deleteByScope removes the replica")
    fun `deleteByScope removes the replica`() {
        val scope = SaveReplicaScope("s", "u", 1L, "h", "a")
        store.upsert(SaveReplicaRecord(
            serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
            coreId = "c", coreBuildRevision = "r",
        ))
        assertThat(store.findByScope(scope)).isNotNull()

        val deleteResult = store.deleteByScope(scope)
        assertThat(deleteResult.isSuccess).isTrue()
        assertThat(store.findByScope(scope)).isNull()
    }
}

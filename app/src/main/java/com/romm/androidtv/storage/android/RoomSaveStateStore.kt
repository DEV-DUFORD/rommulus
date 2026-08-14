/*
 * Thin Android adapter: implements [SaveStateStore] over [SaveDatabase].
 * Delegates replica and pending-op operations to the two child adapters,
 * and provides atomic transactions via Room's runInTransaction.
 */
package com.romm.androidtv.storage.android

import com.romm.androidtv.romm.save.SaveDatabase
import com.romm.androidtv.storage.ports.SaveReplicaScope
import com.romm.androidtv.storage.ports.SaveStateStore
import com.romm.androidtv.storage.records.*
import kotlinx.coroutines.runBlocking

/**
 * Android adapter that bridges the synchronous [SaveStateStore] port to the
 * suspend-based Room [SaveDatabase].
 *
 * Delegates replica operations to [RoomSaveReplicaStore] and pending-op
 * operations to [RoomPendingOperationStore]. The [inTransaction] method uses
 * Room's [runInTransaction] so that replica + pending-op updates commit
 * atomically (or roll back together on exception).
 *
 * All DAO calls are wrapped in [runBlocking] because the port API is synchronous
 * while Room DAOs are suspend.
 */
open class RoomSaveStateStore(
    private val db: SaveDatabase,
) : SaveStateStore {

    private val replicaStore: RoomSaveReplicaStore by lazy {
        RoomSaveReplicaStore(db.saveReplicaDao())
    }

    private val opStore: RoomPendingOperationStore by lazy {
        RoomPendingOperationStore(db.pendingOperationDao())
    }

    // ── SaveReplicaStore delegation ──────────────────────────────────────────

    override fun upsert(replica: SaveReplicaRecord): Result<Long> =
        replicaStore.upsert(replica)

    override fun findByScope(scope: SaveReplicaScope): SaveReplicaRecord? =
        replicaStore.findByScope(scope)

    override fun findByStatus(
        serverKey: String,
        userKey: String,
        status: SaveSyncStatus,
    ): List<SaveReplicaRecord> =
        replicaStore.findByStatus(serverKey, userKey, status)

    override fun markSyncedIfGenerationMatches(
        scope: SaveReplicaScope,
        localGenerationEpochMs: Long,
        rommSaveId: Long?,
        serverHash: String?,
        serverSizeBytes: Long?,
        serverUpdatedAtEpochMs: Long?,
    ): Boolean =
        replicaStore.markSyncedIfGenerationMatches(
            scope, localGenerationEpochMs, rommSaveId, serverHash,
            serverSizeBytes, serverUpdatedAtEpochMs,
        )

    override fun deleteByScope(scope: SaveReplicaScope): Result<Unit> =
        replicaStore.deleteByScope(scope)

    // ── PendingOperationStore delegation ─────────────────────────────────────

    override fun enqueue(operation: PendingOperationRecord): Result<Long> =
        opStore.enqueue(operation)

    override fun findById(id: Long): PendingOperationRecord? =
        opStore.findById(id)

    override fun findByStatus(status: PendingOperationStatus): List<PendingOperationRecord> =
        opStore.findByStatus(status)

    override fun findActiveByScope(
        scope: SaveReplicaScope,
        operationType: PendingOperationType,
    ): List<PendingOperationRecord> =
        opStore.findActiveByScope(scope, operationType)

    override fun updateStatus(
        id: Long,
        status: PendingOperationStatus,
        attemptCount: Int,
        lastError: String?,
        lastHttpCode: Int?,
        updatedAtEpochMs: Long,
    ): Result<Unit> =
        opStore.updateStatus(id, status, attemptCount, lastError, lastHttpCode, updatedAtEpochMs)

    override fun deleteStaleForScope(
        scope: SaveReplicaScope,
        operationType: PendingOperationType,
        olderThanLocalGenerationEpochMs: Long,
    ): Int =
        opStore.deleteStaleForScope(scope, operationType, olderThanLocalGenerationEpochMs)

    // ── Transaction ──────────────────────────────────────────────────────────

    /**
     * Executes [block] within a Room atomic transaction. On success the changes
     * are committed; on failure (exception) Room rolls back and we return
     * [Result.failure].
     *
     * Open for test subclasses to override with fake-friendly semantics.
     */
    override fun <T> inTransaction(block: (SaveStateStore) -> T): Result<T> =
        runCatching {
            // ports are synchronous; Room DAO is suspend
            runBlocking {
                db.runInTransaction<T> {
                    block(this@RoomSaveStateStore)
                }
            }
        }
}

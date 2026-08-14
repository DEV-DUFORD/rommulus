package com.romm.androidtv.storage.fakes

import com.romm.androidtv.storage.ports.SaveReplicaScope
import com.romm.androidtv.storage.ports.SaveStateStore
import com.romm.androidtv.storage.records.*
import java.time.Clock

/**
 * Thread-safe in-memory SaveStateStore for tests and desktop dev-loop use.
 * Replicas keyed by [SaveReplicaScope], operations keyed by auto-assigned id.
 * Supports atomic transactions via snapshot/restore semantics.
 */
class InMemorySaveStateStore(
    private val clock: Clock = Clock.systemUTC(),
) : SaveStateStore {

    private val lock = Any()
    private val replicas: MutableMap<SaveReplicaScope, SaveReplicaRecord> = mutableMapOf()
    private val operations: MutableMap<Long, PendingOperationRecord> = mutableMapOf()
    @Volatile private var nextOpId: Long = 1

    // ---- SaveReplicaStore ----

    override fun upsert(replica: SaveReplicaRecord): Result<Long> = runCatching {
        synchronized(lock) {
            val scope = SaveReplicaScope(replica.serverKey, replica.userKey, replica.romId, replica.romHash, replica.slot)
            val id = replica.id ?: nextIdForReplica()
            replicas[scope] = replica.copy(id = id)
            id
        }
    }

    override fun findByScope(scope: SaveReplicaScope): SaveReplicaRecord? {
        return synchronized(lock) { replicas[scope] }
    }

    override fun findByStatus(serverKey: String, userKey: String, status: SaveSyncStatus): List<SaveReplicaRecord> {
        return synchronized(lock) {
            replicas.values.filter { it.serverKey == serverKey && it.userKey == userKey && it.syncStatus == status }
        }
    }

    override fun markSyncedIfGenerationMatches(
        scope: SaveReplicaScope,
        localGenerationEpochMs: Long,
        rommSaveId: Long?,
        serverHash: String?,
        serverSizeBytes: Long?,
        serverUpdatedAtEpochMs: Long?,
    ): Boolean {
        return synchronized(lock) {
            val existing = replicas[scope] ?: return@synchronized false
            if (existing.localWrittenAtEpochMs != localGenerationEpochMs) return@synchronized false
            replicas[scope] = existing.copy(
                syncStatus = SaveSyncStatus.SYNCED,
                lastError = null,
                rommSaveId = rommSaveId,
                serverHash = serverHash,
                serverSizeBytes = serverSizeBytes,
                serverUpdatedAtEpochMs = serverUpdatedAtEpochMs,
            )
            true
        }
    }

    override fun deleteByScope(scope: SaveReplicaScope): Result<Unit> = runCatching {
        synchronized(lock) { replicas.remove(scope) }
    }

    // ---- PendingOperationStore ----

    override fun enqueue(operation: PendingOperationRecord): Result<Long> = runCatching {
        synchronized(lock) {
            val id = operation.id ?: nextOpId++
            val now = clock.millis()
            operations[id] = operation.copy(
                id = id,
                createdAtEpochMs = operation.createdAtEpochMs.takeIf { it > 0 } ?: now,
                updatedAtEpochMs = operation.updatedAtEpochMs.takeIf { it > 0 } ?: now,
            )
            id
        }
    }

    override fun findById(id: Long): PendingOperationRecord? {
        return synchronized(lock) { operations[id] }
    }

    override fun findByStatus(status: PendingOperationStatus): List<PendingOperationRecord> {
        return synchronized(lock) { operations.values.filter { it.status == status }.toList() }
    }

    override fun findActiveByScope(scope: SaveReplicaScope, operationType: PendingOperationType): List<PendingOperationRecord> {
        return synchronized(lock) {
            operations.values.filter { op ->
                op.serverKey == scope.serverKey &&
                    op.userKey == scope.userKey &&
                    op.romId == scope.romId &&
                    op.romHash == scope.romHash &&
                    op.slot == scope.slot &&
                    op.operationType == operationType &&
                    op.status != PendingOperationStatus.SUCCEEDED &&
                    op.status != PendingOperationStatus.PERMANENT_FAILURE
            }.toList()
        }
    }

    override fun updateStatus(
        id: Long,
        status: PendingOperationStatus,
        attemptCount: Int,
        lastError: String?,
        lastHttpCode: Int?,
        updatedAtEpochMs: Long,
    ): Result<Unit> = runCatching {
        synchronized(lock) {
            val existing = operations[id] ?: return@synchronized
            operations[id] = existing.copy(
                status = status,
                attemptCount = attemptCount,
                lastError = lastError,
                lastHttpCode = lastHttpCode,
                updatedAtEpochMs = updatedAtEpochMs,
            )
        }
    }

    override fun deleteStaleForScope(
        scope: SaveReplicaScope,
        operationType: PendingOperationType,
        olderThanLocalGenerationEpochMs: Long,
    ): Int {
        return synchronized(lock) {
            val staleIds = operations.entries.filter { (_, op) ->
                op.serverKey == scope.serverKey &&
                    op.userKey == scope.userKey &&
                    op.romId == scope.romId &&
                    op.romHash == scope.romHash &&
                    op.slot == scope.slot &&
                    op.operationType == operationType &&
                    op.localGenerationEpochMs < olderThanLocalGenerationEpochMs
            }.map { it.key }
            staleIds.forEach { operations.remove(it) }
            staleIds.size
        }
    }

    // ---- SaveStateStore transaction ----

    override fun <T> inTransaction(block: (SaveStateStore) -> T): Result<T> = runCatching {
        // Snapshot current state.
        val snapReplicas: Map<SaveReplicaScope, SaveReplicaRecord>
        val snapOperations: Map<Long, PendingOperationRecord>
        val snapNextOpId: Long
        synchronized(lock) {
            snapReplicas = replicas.toMap()
            snapOperations = operations.toMap()
            snapNextOpId = nextOpId
        }

        // Create working copy.
        val working = InMemorySaveStateStore(clock)
        synchronized(working.lock) {
            working.replicas.putAll(snapReplicas)
            working.operations.putAll(snapOperations)
            working.nextOpId = snapNextOpId
        }

        // Execute block on working copy.
        val result = block(working)

        // Commit: apply working state to this store.
        synchronized(lock) {
            synchronized(working.lock) {
                replicas.clear()
                replicas.putAll(working.replicas)
                operations.clear()
                operations.putAll(working.operations)
                nextOpId = working.nextOpId
            }
        }

        result
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    private fun nextIdForReplica(): Long = clock.millis()

    /** Expose replica count for contract tests. */
    internal fun replicaCount(): Int = synchronized(lock) { replicas.size }

    /** Expose operation count for contract tests. */
    internal fun opCount(): Int = synchronized(lock) { operations.size }
}

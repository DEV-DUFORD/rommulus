package com.romm.androidtv.storage.ports

import com.romm.androidtv.storage.records.PendingOperationRecord
import com.romm.androidtv.storage.records.PendingOperationStatus
import com.romm.androidtv.storage.records.PendingOperationType

/** Persistence-neutral store for pending operations (upload queue). */
interface PendingOperationStore {
    fun enqueue(operation: PendingOperationRecord): Result<Long>
    fun findById(id: Long): PendingOperationRecord?
    fun findByStatus(status: PendingOperationStatus): List<PendingOperationRecord>
    fun findActiveByScope(scope: SaveReplicaScope, operationType: PendingOperationType): List<PendingOperationRecord>
    fun updateStatus(
        id: Long,
        status: PendingOperationStatus,
        attemptCount: Int,
        lastError: String?,
        lastHttpCode: Int?,
        updatedAtEpochMs: Long,
    ): Result<Unit>
    fun deleteStaleForScope(
        scope: SaveReplicaScope,
        operationType: PendingOperationType,
        olderThanLocalGenerationEpochMs: Long,
    ): Int
}

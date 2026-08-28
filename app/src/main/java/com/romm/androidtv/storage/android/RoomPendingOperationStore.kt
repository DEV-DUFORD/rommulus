/*
 * Thin Android adapter: implements [PendingOperationStore] over [PendingOperationDao].
 * Delegates every call; never reimplements persistence logic.
 */
package com.romm.androidtv.storage.android

import com.romm.androidtv.romm.save.PendingOperationDao
import com.romm.androidtv.storage.ports.PendingOperationStore
import com.romm.androidtv.storage.ports.SaveReplicaScope
import com.romm.androidtv.storage.records.PendingOperationStatus
import com.romm.androidtv.storage.records.PendingOperationType
import kotlinx.coroutines.runBlocking

/**
 * Android adapter that bridges the synchronous [PendingOperationStore] port to the
 * suspend-based Room [PendingOperationDao].
 *
 * All DAO calls are wrapped in [runBlocking] because the port API is synchronous
 * while Room DAOs are suspend.
 */
class RoomPendingOperationStore(
    private val dao: PendingOperationDao,
) : PendingOperationStore {

    override fun enqueue(operation: com.romm.androidtv.storage.records.PendingOperationRecord): Result<Long> =
        runCatching {
            // ports are synchronous; Room DAO is suspend
            runBlocking { dao.insert(operation.toEntity()) }
        }

    override fun findById(id: Long): com.romm.androidtv.storage.records.PendingOperationRecord? =
        // ports are synchronous; Room DAO is suspend
        runBlocking { dao.findById(id) }?.toRecord()

    override fun findByStatus(status: PendingOperationStatus): List<com.romm.androidtv.storage.records.PendingOperationRecord> =
        // ports are synchronous; Room DAO is suspend
        runBlocking { dao.findByStatus(status.androidValue) }
            .map { it.toRecord() }

    override fun findActiveByScope(
        scope: SaveReplicaScope,
        operationType: PendingOperationType,
    ): List<com.romm.androidtv.storage.records.PendingOperationRecord> =
        // ports are synchronous; Room DAO is suspend
        runBlocking {
            dao.findActiveByScope(
                serverKey = scope.serverKey,
                userKey = scope.userKey,
                romId = scope.romId,
                romHash = scope.romHash,
                slot = scope.slot,
                operationType = operationType.androidValue,
            )
        }.map { it.toRecord() }

    override fun updateStatus(
        id: Long,
        status: PendingOperationStatus,
        attemptCount: Int,
        lastError: String?,
        lastHttpCode: Int?,
        updatedAtEpochMs: Long,
    ): Result<Unit> =
        runCatching {
            // ports are synchronous; Room DAO is suspend
            runBlocking {
                dao.updateStatus(
                    id = id,
                    status = status.androidValue,
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
    ): Int =
        // ports are synchronous; Room DAO is suspend
        runBlocking {
            dao.deleteStaleForScope(
                serverKey = scope.serverKey,
                userKey = scope.userKey,
                romId = scope.romId,
                romHash = scope.romHash,
                slot = scope.slot,
                operationType = operationType.androidValue,
                olderThanLocalGenerationEpochMs = olderThanLocalGenerationEpochMs,
            )
        }
}

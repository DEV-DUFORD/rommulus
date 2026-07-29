package com.romm.androidtv.romm.save

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Room DAO for [PendingOperationEntity] (LIBRETRO_REFACTOR.md section
 * 11.4). Unlike [SaveReplicaDao], this DAO has one deliberately *unscoped*
 * query ([findByStatus]): the WorkManager worker that drains this queue
 * (Milestone 7, `p5-workmanager`) runs once per app, not once per
 * server/user, so it genuinely needs "give me everything in state X" rather
 * than a caller-known scope.
 */
@Dao
interface PendingOperationDao {

    @Insert
    suspend fun insert(entity: PendingOperationEntity): Long

    @Query("SELECT * FROM pending_operations WHERE id = :id")
    suspend fun findById(id: Long): PendingOperationEntity?

    /** All operations still in [status], across every server/user/ROM — for the upload worker to drain. */
    @Query("SELECT * FROM pending_operations WHERE status = :status")
    suspend fun findByStatus(status: PendingOperationStatus): List<PendingOperationEntity>

    /**
     * Non-terminal operations (per [PendingOperationTransitions.isTerminal])
     * for one scope + [operationType] — used to detect and supersede a
     * now-stale queued operation before enqueuing a fresh one for the same
     * scope (section 11.4: "preserving the newest durable local generation").
     */
    @Query(
        "SELECT * FROM pending_operations " +
            "WHERE serverKey = :serverKey AND userKey = :userKey " +
            "AND romId = :romId AND romHash = :romHash AND slot = :slot " +
            "AND operationType = :operationType " +
            "AND status NOT IN ('SUCCEEDED', 'AUTH_REQUIRED', 'CONFLICT', 'PERMANENT_FAILURE')",
    )
    suspend fun findActiveByScope(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
        operationType: PendingOperationType,
    ): List<PendingOperationEntity>

    /**
     * Applies a validated status transition. Callers must check
     * [PendingOperationTransitions.isValidTransition] themselves first — this
     * DAO layer does not re-validate, so an invalid transition here is a
     * caller bug, not a silently-ignored write.
     */
    @Query(
        "UPDATE pending_operations SET status = :status, attemptCount = :attemptCount, " +
            "lastError = :lastError, lastHttpCode = :lastHttpCode, updatedAtEpochMs = :updatedAtEpochMs " +
            "WHERE id = :id",
    )
    suspend fun updateStatus(
        id: Long,
        status: PendingOperationStatus,
        attemptCount: Int,
        lastError: String?,
        lastHttpCode: Int?,
        updatedAtEpochMs: Long,
    )

    /**
     * Deletes non-terminal operations for [scope] older than
     * [olderThanLocalGenerationEpochMs] — the "supersede a stale queued
     * operation" half of the dedupe rule in section 11.4.
     */
    @Query(
        "DELETE FROM pending_operations " +
            "WHERE serverKey = :serverKey AND userKey = :userKey " +
            "AND romId = :romId AND romHash = :romHash AND slot = :slot " +
            "AND operationType = :operationType " +
            "AND status NOT IN ('SUCCEEDED', 'AUTH_REQUIRED', 'CONFLICT', 'PERMANENT_FAILURE') " +
            "AND localGenerationEpochMs < :olderThanLocalGenerationEpochMs",
    )
    suspend fun deleteStaleForScope(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
        operationType: PendingOperationType,
        olderThanLocalGenerationEpochMs: Long,
    )
}

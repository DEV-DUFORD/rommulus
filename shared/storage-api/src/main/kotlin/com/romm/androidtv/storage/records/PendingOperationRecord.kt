package com.romm.androidtv.storage.records

/**
 * One durable, resumable unit of queued work, persistence-neutral mirror of the
 * Android Room schema (PendingOperationEntity). 20 fields matching the entity exactly.
 */
data class PendingOperationRecord(
    val id: Long? = null,
    val serverKey: String,
    val userKey: String,
    val romId: Long,
    val romHash: String,
    val slot: String,
    val operationType: PendingOperationType,
    val localGenerationEpochMs: Long,
    val status: PendingOperationStatus = PendingOperationStatus.PENDING,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val lastHttpCode: Int? = null,
    val origin: String? = null,
    val uploadFileName: String? = null,
    val sessionId: Long? = null,
    val negotiateFileName: String? = null,
    val negotiateCoreId: String? = null,
    val negotiateCoreBuildRevision: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

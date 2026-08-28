/*
 * Persistence-neutral ↔ Android Room entity/enum mappers.
 * All mappings are 1:1 by field name; enums map by name (valueOf / .name).
 */
package com.romm.androidtv.storage.android

import com.romm.androidtv.romm.save.PendingOperationEntity
import com.romm.androidtv.romm.save.PendingOperationStatus as AndroidPendingOperationStatus
import com.romm.androidtv.romm.save.PendingOperationType as AndroidPendingOperationType
import com.romm.androidtv.romm.save.SaveReplicaEntity
import com.romm.androidtv.romm.save.SaveSyncStatus as AndroidSaveSyncStatus
import com.romm.androidtv.storage.records.PendingOperationRecord
import com.romm.androidtv.storage.records.PendingOperationStatus as RecordPendingOperationStatus
import com.romm.androidtv.storage.records.PendingOperationType as RecordPendingOperationType
import com.romm.androidtv.storage.records.SaveReplicaRecord
import com.romm.androidtv.storage.records.SaveSyncStatus as RecordSaveSyncStatus

// ─── SaveSyncStatus ───────────────────────────────────────────────────────────

internal val AndroidSaveSyncStatus.recordValue: RecordSaveSyncStatus
    get() = RecordSaveSyncStatus.valueOf(name)

internal val RecordSaveSyncStatus.androidValue: AndroidSaveSyncStatus
    get() = AndroidSaveSyncStatus.valueOf(name)

// ─── PendingOperationStatus ───────────────────────────────────────────────────

internal val AndroidPendingOperationStatus.recordValue: RecordPendingOperationStatus
    get() = RecordPendingOperationStatus.valueOf(name)

internal val RecordPendingOperationStatus.androidValue: AndroidPendingOperationStatus
    get() = AndroidPendingOperationStatus.valueOf(name)

// ─── PendingOperationType ─────────────────────────────────────────────────────

internal val AndroidPendingOperationType.recordValue: RecordPendingOperationType
    get() = RecordPendingOperationType.valueOf(name)

internal val RecordPendingOperationType.androidValue: AndroidPendingOperationType
    get() = AndroidPendingOperationType.valueOf(name)

// ─── SaveReplicaRecord ↔ SaveReplicaEntity ────────────────────────────────────

internal fun SaveReplicaRecord.toEntity(): SaveReplicaEntity = SaveReplicaEntity(
    id = id ?: 0L,
    serverKey = serverKey,
    userKey = userKey,
    romId = romId,
    romHash = romHash,
    slot = slot,
    coreId = coreId,
    coreBuildRevision = coreBuildRevision,
    expectedSramSizeBytes = expectedSramSizeBytes,
    localHash = localHash,
    localSizeBytes = localSizeBytes,
    localWrittenAtEpochMs = localWrittenAtEpochMs,
    rommSaveId = rommSaveId,
    serverHash = serverHash,
    serverSizeBytes = serverSizeBytes,
    serverUpdatedAtEpochMs = serverUpdatedAtEpochMs,
    syncStatus = syncStatus.androidValue,
    lastError = lastError,
)

internal fun SaveReplicaEntity.toRecord(): SaveReplicaRecord = SaveReplicaRecord(
    id = if (id > 0) id else null,
    serverKey = serverKey,
    userKey = userKey,
    romId = romId,
    romHash = romHash,
    slot = slot,
    coreId = coreId,
    coreBuildRevision = coreBuildRevision,
    expectedSramSizeBytes = expectedSramSizeBytes,
    localHash = localHash,
    localSizeBytes = localSizeBytes,
    localWrittenAtEpochMs = localWrittenAtEpochMs,
    rommSaveId = rommSaveId,
    serverHash = serverHash,
    serverSizeBytes = serverSizeBytes,
    serverUpdatedAtEpochMs = serverUpdatedAtEpochMs,
    syncStatus = syncStatus.recordValue,
    lastError = lastError,
)

// ─── PendingOperationRecord ↔ PendingOperationEntity ─────────────────────────

internal fun PendingOperationRecord.toEntity(): PendingOperationEntity = PendingOperationEntity(
    id = id ?: 0L,
    serverKey = serverKey,
    userKey = userKey,
    romId = romId,
    romHash = romHash,
    slot = slot,
    operationType = operationType.androidValue,
    localGenerationEpochMs = localGenerationEpochMs,
    status = status.androidValue,
    attemptCount = attemptCount,
    lastError = lastError,
    lastHttpCode = lastHttpCode,
    origin = origin,
    uploadFileName = uploadFileName,
    sessionId = sessionId,
    negotiateFileName = negotiateFileName,
    negotiateCoreId = negotiateCoreId,
    negotiateCoreBuildRevision = negotiateCoreBuildRevision,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
)

internal fun PendingOperationEntity.toRecord(): PendingOperationRecord = PendingOperationRecord(
    id = if (id > 0) id else null,
    serverKey = serverKey,
    userKey = userKey,
    romId = romId,
    romHash = romHash,
    slot = slot,
    operationType = operationType.recordValue,
    localGenerationEpochMs = localGenerationEpochMs,
    status = status.recordValue,
    attemptCount = attemptCount,
    lastError = lastError,
    lastHttpCode = lastHttpCode,
    origin = origin,
    uploadFileName = uploadFileName,
    sessionId = sessionId,
    negotiateFileName = negotiateFileName,
    negotiateCoreId = negotiateCoreId,
    negotiateCoreBuildRevision = negotiateCoreBuildRevision,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
)

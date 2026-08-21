package com.romm.desktop.sync

import com.romm.androidtv.emulation.model.SavePathPolicy
import com.romm.androidtv.emulation.model.sha256Hex
import com.romm.androidtv.romm.ClientSaveState
import com.romm.androidtv.romm.DeviceRegistrationResult
import com.romm.androidtv.romm.RommApiError
import com.romm.androidtv.romm.SaveConfirmResult
import com.romm.androidtv.romm.SaveDownloadResult
import com.romm.androidtv.romm.SyncAction
import com.romm.androidtv.romm.SyncCompleteRequest
import com.romm.androidtv.romm.SyncCompleteResult
import com.romm.androidtv.romm.SyncNegotiateRequest
import com.romm.androidtv.romm.SyncNegotiateResult
import com.romm.androidtv.romm.SyncOperation
import com.romm.androidtv.romm.save.SaveSyncOutcome
import com.romm.androidtv.romm.save.SaveSyncRequest
import com.romm.androidtv.storage.ports.SaveReplicaScope
import com.romm.androidtv.storage.ports.SaveStateStore
import com.romm.androidtv.storage.records.PendingOperationRecord
import com.romm.androidtv.storage.records.PendingOperationStatus
import com.romm.androidtv.storage.records.PendingOperationType
import com.romm.androidtv.storage.records.SaveReplicaRecord
import com.romm.androidtv.storage.records.SaveSyncStatus
import java.time.Instant
import java.util.logging.Logger

/** Blocking desktop seam for Android-equivalent save negotiation immediately before launch. */
fun interface PreLaunchSaveSynchronizer {
    fun syncBeforeLaunch(request: SaveSyncRequest): SaveSyncOutcome
}

/** Typed device-registration seam so auth and transient offline failures remain distinct. */
fun interface PreLaunchDeviceIdentityLoader {
    fun load(origin: String, username: String): DeviceRegistrationResult
}

/**
 * Desktop port of Android's `SaveSyncCoordinator.syncBeforeLaunch`.
 *
 * Downloads are applied synchronously before the player is spawned, uploads are durably queued
 * for [SaveSyncDrainExecutor], and conflicts/permanent quarantines stop the launch without
 * replacing local bytes. A trusted server save with an unknown SRAM size is retained in
 * quarantine and staged for the player's core-side validation. A transient negotiate failure may
 * fall back to a durable local save.
 */
class DesktopSaveLaunchSynchronizer(
    private val saveState: SaveStateStore,
    private val content: SaveContentGateway,
    private val sessionReader: SaveSyncSessionReader,
    private val deviceIdentityLoader: PreLaunchDeviceIdentityLoader,
    private val sync: RommSyncGateway,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onOperationQueued: () -> Unit = {},
) : PreLaunchSaveSynchronizer {

    override fun syncBeforeLaunch(request: SaveSyncRequest): SaveSyncOutcome {
        val session = sessionReader.current()
            ?: return SaveSyncOutcome.Failure(RommApiError.AUTH_EXPIRED)
        if (session.kioskMode) return SaveSyncOutcome.NoOpSynced(0L)
        val username = session.username
            ?: return SaveSyncOutcome.Failure(RommApiError.AUTH_EXPIRED)
        val origin = session.origin
        val serverKey = SavePathPolicy.sanitizeSegment(origin)
        val userKey = SavePathPolicy.sanitizeSegment(username)
        val scope = SaveReplicaScope(serverKey, userKey, request.romId, request.romHash, request.slot)
        val existingReplica = saveState.findByScope(scope)
        val localBytes = content.readLocal(serverKey, userKey, request.romId, request.romHash, request.slot)

        fun outcomeForFailure(error: RommApiError, httpCode: Int?): SaveSyncOutcome =
            if (
                isTransientServerOutage(error, httpCode) &&
                existingReplica?.localWrittenAtEpochMs != null &&
                localBytes != null
            ) {
                SaveSyncOutcome.PlayOfflineLocal(error, httpCode)
            } else {
                SaveSyncOutcome.Failure(error, httpCode)
            }

        val deviceId = when (val registration = deviceIdentityLoader.load(origin, username)) {
            is DeviceRegistrationResult.Success -> registration.identity.rommDeviceId
            is DeviceRegistrationResult.Failure ->
                return outcomeForFailure(registration.error, registration.httpCode)
        }

        val localGeneration = existingReplica?.localWrittenAtEpochMs
        val clientSaves = if (localGeneration != null && localBytes != null) {
            listOf(
                ClientSaveState(
                    romId = request.romId,
                    fileName = request.fileName,
                    slot = request.slot,
                    emulator = request.coreId,
                    contentHash = existingReplica.localHash,
                    updatedAt = Instant.ofEpochMilli(localGeneration),
                    fileSizeBytes = localBytes.size.toLong(),
                ),
            )
        } else {
            emptyList()
        }

        val negotiation = when (
            val result = sync.negotiateSync(origin, SyncNegotiateRequest(deviceId, clientSaves))
        ) {
            is SyncNegotiateResult.Success -> result.negotiation
            is SyncNegotiateResult.Failure -> return outcomeForFailure(result.error, result.httpCode)
        }

        val operation = negotiation.operations.firstOrNull {
            it.romId == request.romId && (it.slot == null || it.slot == request.slot)
        } ?: run {
            completeSession(origin, negotiation.sessionId, completed = 0, failed = 0)
            return SaveSyncOutcome.NoOpSynced(negotiation.sessionId)
        }

        return when (operation.action) {
            SyncAction.NO_OP -> {
                saveState.upsert(mergeAgreedMetadata(existingReplica, request, scope, operation, localBytes))
                    .getOrThrow()
                completeSession(origin, negotiation.sessionId, completed = 1, failed = 0)
                SaveSyncOutcome.NoOpSynced(negotiation.sessionId)
            }

            SyncAction.DOWNLOAD -> handleDownload(
                request,
                scope,
                origin,
                deviceId,
                negotiation.sessionId,
                operation,
                existingReplica,
            )

            SyncAction.UPLOAD -> handleUploadQueue(
                request,
                scope,
                origin,
                deviceId,
                negotiation.sessionId,
                operation,
                existingReplica,
                localBytes,
            )

            SyncAction.CONFLICT -> {
                saveState.upsert(
                    (existingReplica ?: newReplica(request, scope)).copy(
                        coreId = request.coreId,
                        coreBuildRevision = request.coreBuildRevision,
                        expectedSramSizeBytes =
                            request.expectedSramSizeBytes ?: existingReplica?.expectedSramSizeBytes,
                        rommSaveId = operation.saveId ?: existingReplica?.rommSaveId,
                        serverHash = operation.serverContentHash ?: existingReplica?.serverHash,
                        serverUpdatedAtEpochMs =
                            operation.serverUpdatedAt?.toEpochMilli() ?: existingReplica?.serverUpdatedAtEpochMs,
                        syncStatus = SaveSyncStatus.CONFLICT,
                        lastError = operation.reason,
                    ),
                ).getOrThrow()
                completeSession(origin, negotiation.sessionId, completed = 0, failed = 1)
                SaveSyncOutcome.ConflictRequiresResolution(negotiation.sessionId, operation)
            }
        }
    }

    private fun handleDownload(
        request: SaveSyncRequest,
        scope: SaveReplicaScope,
        origin: String,
        deviceId: String,
        sessionId: Long,
        operation: SyncOperation,
        existingReplica: SaveReplicaRecord?,
    ): SaveSyncOutcome {
        val saveId = operation.saveId ?: return SaveSyncOutcome.Failure(RommApiError.PARSE_ERROR)
        val bytes = when (val result = sync.downloadSaveContent(origin, saveId, deviceId, sessionId)) {
            is SaveDownloadResult.Success -> result.bytes
            is SaveDownloadResult.Failure -> return SaveSyncOutcome.Failure(result.error, result.httpCode)
        }

        if (operation.emulator == null || operation.emulator != request.coreId) {
            return quarantineDownload(
                request,
                scope,
                origin,
                sessionId,
                operation,
                existingReplica,
                bytes,
                reason = "unknown-provenance",
            )
        }

        val expectedSize = request.expectedSramSizeBytes ?: existingReplica?.expectedSramSizeBytes
        if (expectedSize == null) {
            // The player restores this canonical file only after the core reports its actual SRAM
            // size. A mismatched candidate is rejected by restoreSaveRam() and the game starts
            // with fresh SRAM; a matching candidate is checkpointed at exit. Retain a quarantine
            // copy until that core-side validation has completed.
            val quarantinedPath = content.quarantine(
                scope.serverKey,
                scope.userKey,
                scope.romId,
                scope.romHash,
                scope.slot,
                bytes,
                "awaiting-core-validation",
                clock(),
            )
            content.writeLocalAtomically(
                scope.serverKey,
                scope.userKey,
                scope.romId,
                scope.romHash,
                scope.slot,
                bytes,
            )
            saveState.upsert(
                (existingReplica ?: newReplica(request, scope)).copy(
                    coreId = request.coreId,
                    coreBuildRevision = request.coreBuildRevision,
                    expectedSramSizeBytes = null,
                    rommSaveId = saveId,
                    serverHash = operation.serverContentHash,
                    serverSizeBytes = bytes.size.toLong(),
                    serverUpdatedAtEpochMs = operation.serverUpdatedAt?.toEpochMilli(),
                    syncStatus = SaveSyncStatus.AWAITING_CORE_VALIDATION,
                    lastError = null,
                ),
            ).getOrThrow()
            return SaveSyncOutcome.AwaitingCoreValidation(
                sessionId = sessionId,
                rommSaveId = saveId,
                quarantinedPath = quarantinedPath,
                downloadedSizeBytes = bytes.size.toLong(),
                serverContentHash = operation.serverContentHash,
                emulator = operation.emulator,
            )
        }
        if (bytes.size.toLong() != expectedSize) {
            return quarantineDownload(
                request,
                scope,
                origin,
                sessionId,
                operation,
                existingReplica,
                bytes,
                reason = "size-mismatch",
            )
        }

        content.writeLocalAtomically(
            scope.serverKey,
            scope.userKey,
            scope.romId,
            scope.romHash,
            scope.slot,
            bytes,
        )
        val now = clock()
        saveState.upsert(
            (existingReplica ?: newReplica(request, scope)).copy(
                coreId = request.coreId,
                coreBuildRevision = request.coreBuildRevision,
                expectedSramSizeBytes = expectedSize,
                localHash = sha256Hex(bytes),
                localSizeBytes = bytes.size.toLong(),
                localWrittenAtEpochMs = now,
                rommSaveId = saveId,
                serverHash = operation.serverContentHash,
                serverSizeBytes = bytes.size.toLong(),
                serverUpdatedAtEpochMs = operation.serverUpdatedAt?.toEpochMilli(),
                syncStatus = SaveSyncStatus.SYNCED,
                lastError = null,
            ),
        ).getOrThrow()

        val confirmed = sync.confirmDownload(origin, saveId, deviceId) is SaveConfirmResult.Success
        completeSession(origin, sessionId, completed = 1, failed = 0)
        return SaveSyncOutcome.Downloaded(sessionId, saveId, bytes.size.toLong(), confirmed)
    }

    private fun quarantineDownload(
        request: SaveSyncRequest,
        scope: SaveReplicaScope,
        origin: String,
        sessionId: Long,
        operation: SyncOperation,
        existingReplica: SaveReplicaRecord?,
        bytes: ByteArray,
        reason: String,
    ): SaveSyncOutcome {
        val quarantinedPath = content.quarantine(
            scope.serverKey,
            scope.userKey,
            scope.romId,
            scope.romHash,
            scope.slot,
            bytes,
            reason,
            clock(),
        )
        saveState.upsert(
            (existingReplica ?: newReplica(request, scope)).copy(
                coreId = request.coreId,
                coreBuildRevision = request.coreBuildRevision,
                expectedSramSizeBytes =
                    request.expectedSramSizeBytes ?: existingReplica?.expectedSramSizeBytes,
                rommSaveId = operation.saveId ?: existingReplica?.rommSaveId,
                serverHash = operation.serverContentHash ?: existingReplica?.serverHash,
                serverSizeBytes = bytes.size.toLong(),
                serverUpdatedAtEpochMs =
                    operation.serverUpdatedAt?.toEpochMilli() ?: existingReplica?.serverUpdatedAtEpochMs,
                syncStatus = SaveSyncStatus.QUARANTINED,
                lastError = "quarantined: $reason",
            ),
        ).getOrThrow()
        completeSession(origin, sessionId, completed = 0, failed = 1)
        return SaveSyncOutcome.Quarantined(reason, quarantinedPath)
    }

    private fun handleUploadQueue(
        request: SaveSyncRequest,
        scope: SaveReplicaScope,
        origin: String,
        deviceId: String,
        sessionId: Long,
        operation: SyncOperation,
        existingReplica: SaveReplicaRecord?,
        localBytes: ByteArray?,
    ): SaveSyncOutcome {
        val generation = existingReplica?.localWrittenAtEpochMs
        if (localBytes == null || generation == null) {
            val saveId = operation.saveId
            if (saveId == null) {
                completeSession(origin, sessionId, completed = 0, failed = 0)
                return SaveSyncOutcome.NoOpSynced(sessionId)
            }
            return handleDownload(
                request,
                scope,
                origin,
                deviceId,
                sessionId,
                operation.copy(action = SyncAction.DOWNLOAD),
                existingReplica,
            )
        }

        saveState.deleteStaleForScope(
            scope,
            PendingOperationType.UPLOAD,
            olderThanLocalGenerationEpochMs = generation,
        )
        val active = saveState.findActiveByScope(scope, PendingOperationType.UPLOAD)
        val pendingId = active.firstOrNull { it.localGenerationEpochMs == generation }?.id ?: run {
            val now = clock()
            saveState.enqueue(
                PendingOperationRecord(
                    serverKey = scope.serverKey,
                    userKey = scope.userKey,
                    romId = scope.romId,
                    romHash = scope.romHash,
                    slot = scope.slot,
                    operationType = PendingOperationType.UPLOAD,
                    localGenerationEpochMs = generation,
                    status = PendingOperationStatus.PENDING,
                    origin = origin,
                    uploadFileName = request.fileName,
                    sessionId = sessionId,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                ),
            ).getOrThrow()
        }

        saveState.upsert(
            existingReplica.copy(
                coreId = request.coreId,
                coreBuildRevision = request.coreBuildRevision,
                expectedSramSizeBytes =
                    request.expectedSramSizeBytes ?: existingReplica.expectedSramSizeBytes,
                syncStatus = SaveSyncStatus.PENDING_UPLOAD,
                lastError = null,
            ),
        ).getOrThrow()
        completeSession(origin, sessionId, completed = 1, failed = 0)
        onOperationQueued()
        return SaveSyncOutcome.UploadQueued(sessionId, pendingId)
    }

    private fun mergeAgreedMetadata(
        existingReplica: SaveReplicaRecord?,
        request: SaveSyncRequest,
        scope: SaveReplicaScope,
        operation: SyncOperation,
        localBytes: ByteArray?,
    ): SaveReplicaRecord {
        val base = existingReplica ?: newReplica(request, scope)
        return base.copy(
            coreId = request.coreId,
            coreBuildRevision = request.coreBuildRevision,
            expectedSramSizeBytes =
                request.expectedSramSizeBytes ?: existingReplica?.expectedSramSizeBytes,
            localHash = existingReplica?.localHash ?: localBytes?.let(::sha256Hex),
            localSizeBytes = existingReplica?.localSizeBytes ?: localBytes?.size?.toLong(),
            rommSaveId = operation.saveId ?: base.rommSaveId,
            serverHash = operation.serverContentHash ?: base.serverHash,
            serverUpdatedAtEpochMs =
                operation.serverUpdatedAt?.toEpochMilli() ?: base.serverUpdatedAtEpochMs,
            syncStatus = SaveSyncStatus.SYNCED,
            lastError = null,
        )
    }

    private fun newReplica(request: SaveSyncRequest, scope: SaveReplicaScope) = SaveReplicaRecord(
        serverKey = scope.serverKey,
        userKey = scope.userKey,
        romId = scope.romId,
        romHash = scope.romHash,
        slot = scope.slot,
        coreId = request.coreId,
        coreBuildRevision = request.coreBuildRevision,
        expectedSramSizeBytes = request.expectedSramSizeBytes,
    )

    private fun completeSession(origin: String, sessionId: Long, completed: Int, failed: Int) {
        when (
            val result = sync.completeSyncSession(
                origin,
                sessionId,
                SyncCompleteRequest(completed, failed),
            )
        ) {
            is SyncCompleteResult.Success -> Unit
            is SyncCompleteResult.Failure ->
                log.warning(
                    "pre-launch completeSyncSession failed (local data remains authoritative): " +
                        "session=$sessionId error=${result.error} httpCode=${result.httpCode}",
                )
        }
    }

    private fun isTransientServerOutage(error: RommApiError, httpCode: Int?): Boolean = when (error) {
        RommApiError.NETWORK_ERROR, RommApiError.TLS_ERROR -> true
        RommApiError.SERVER_ERROR -> httpCode == null || httpCode in 500..599
        else -> false
    }

    private companion object {
        val log: Logger = Logger.getLogger(DesktopSaveLaunchSynchronizer::class.java.name)
    }
}

package com.romm.androidtv.romm.save

import com.romm.androidtv.romm.RommApiError
import com.romm.androidtv.romm.SaveUploadRequest
import com.romm.androidtv.romm.SaveUploadResult

class SaveUploadExecutorImpl(
    private val pendingOperationDao: PendingOperationDao,
    private val saveReplicaDao: SaveReplicaDao,
    private val saveContentStore: SaveContentStore,
    private val sessionReader: SessionReader,
    private val deviceIdentityLoader: DeviceIdentityLoader,
    private val uploadCaller: SaveUploadCaller,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SaveUploadExecutor {

    override suspend fun drainBatch(): SaveUploadExecutor.DrainResult {
        val pending = pendingOperationDao.findByStatus(PendingOperationStatus.PENDING)
        if (pending.isEmpty()) return SaveUploadExecutor.DrainResult.Complete

        var anyRetryable = false
        for (op in pending) {
            val result = processOne(op)
            if (result == OperationOutcome.RETRYABLE) anyRetryable = true
        }
        return if (anyRetryable) SaveUploadExecutor.DrainResult.Retry else SaveUploadExecutor.DrainResult.Complete
    }

    private suspend fun processOne(op: PendingOperationEntity): OperationOutcome {
        val now = clock()
        val currentAttempt = op.attemptCount + 1
        pendingOperationDao.updateStatus(op.id, PendingOperationStatus.RUNNING, currentAttempt, null, null, now)

        // --- Legacy null metadata fails explicitly, never guessed ---
        val origin = op.origin
            ?: return transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.PERMANENT_FAILURE,
                "legacy operation missing origin metadata", null, now, currentAttempt)

        val uploadFileName = op.uploadFileName
            ?: return transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.PERMANENT_FAILURE,
                "legacy operation missing upload filename", null, now, currentAttempt)

        // --- Validate session and device identity ---
        val session = sessionReader.current()
            ?: return transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.AUTH_REQUIRED,
                "no active session", null, now, currentAttempt)
        val username = session.username
            ?: return transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.AUTH_REQUIRED,
                "no username in session", null, now, currentAttempt)

        val deviceIdentity = deviceIdentityLoader.load(origin, username)
            ?: return transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.AUTH_REQUIRED,
                "device not registered", null, now, currentAttempt)

        // --- Validate current SaveReplica generation before upload ---
        val replica = saveReplicaDao.findByScope(op.serverKey, op.userKey, op.romId, op.romHash, op.slot)
            ?: return transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.PERMANENT_FAILURE,
                "no local SaveReplica found for scope", null, now, currentAttempt)

        val replicaGeneration = replica.localWrittenAtEpochMs
            ?: return transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.PERMANENT_FAILURE,
                "SaveReplica has null local generation — cannot validate", null, now, currentAttempt)

        if (replicaGeneration != op.localGenerationEpochMs) {
            // A newer local write superseded this queued operation; drop it.
            return transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.PERMANENT_FAILURE,
                "generation mismatch: replica=$replicaGeneration vs operation=${op.localGenerationEpochMs}", null, now, currentAttempt)
        }

        // --- Read exact durable SRAM bytes ---
        val sramBytes = saveContentStore.readLocal(op.serverKey, op.userKey, op.romId, op.romHash, op.slot)
            ?: return transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.PERMANENT_FAILURE,
                "local SRAM file missing", null, now, currentAttempt)

        val uploadRequest = SaveUploadRequest(
            romId = op.romId,
            slot = op.slot,
            emulator = replica.coreId,
            deviceId = deviceIdentity.rommDeviceId,
            sessionId = op.sessionId,
            overwrite = true,
            fileName = uploadFileName,
            bytes = sramBytes,
        )
        val uploadResult = uploadCaller.call(origin, uploadRequest)

        return when (uploadResult) {
            is SaveUploadResult.Success -> {
                saveReplicaDao.upsert(replica.copy(
                    rommSaveId = uploadResult.save.saveId,
                    serverHash = uploadResult.save.contentHash,
                    serverSizeBytes = uploadResult.save.fileSizeBytes,
                    serverUpdatedAtEpochMs = uploadResult.save.updatedAt?.toEpochMilli(),
                    syncStatus = SaveSyncStatus.SYNCED,
                    lastError = null,
                ))
                transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.SUCCEEDED, null, null, now, currentAttempt)
            }
            is SaveUploadResult.Conflict ->
                transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.CONFLICT,
                    "server conflict", uploadResult.httpCode, now, currentAttempt)
            is SaveUploadResult.Failure -> when (uploadResult.error) {
                RommApiError.AUTH_EXPIRED ->
                    transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.AUTH_REQUIRED,
                        "auth expired", uploadResult.httpCode, now, currentAttempt)
                RommApiError.NETWORK_ERROR, RommApiError.TLS_ERROR ->
                    transitionRetryable(op.id, now, currentAttempt)
                RommApiError.SERVER_ERROR -> {
                    if (uploadResult.httpCode != null && uploadResult.httpCode in 500..599)
                        transitionRetryable(op.id, now, currentAttempt)
                    else
                        transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.PERMANENT_FAILURE,
                            "server error", uploadResult.httpCode, now, currentAttempt)
                }
                else ->
                    transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.PERMANENT_FAILURE,
                        "error: ${uploadResult.error}", uploadResult.httpCode, now, currentAttempt)
            }
        }
    }

    /** RUNNING -> RETRYABLE_FAILURE -> PENDING, preserving attempt count. */
    private suspend fun transitionRetryable(id: Long, now: Long, attemptCount: Int): OperationOutcome {
        pendingOperationDao.updateStatus(id, PendingOperationStatus.RETRYABLE_FAILURE, attemptCount, "transport failure", null, now)
        pendingOperationDao.updateStatus(id, PendingOperationStatus.PENDING, attemptCount, null, null, now)
        return OperationOutcome.RETRYABLE
    }

    private suspend fun transitionTo(
        id: Long, from: PendingOperationStatus, to: PendingOperationStatus,
        error: String?, httpCode: Int?, now: Long, attemptCount: Int,
    ): OperationOutcome {
        if (!PendingOperationTransitions.isValidTransition(from, to)) return OperationOutcome.PERMANENT
        pendingOperationDao.updateStatus(id, to, attemptCount, error, httpCode, now)
        return if (PendingOperationTransitions.isTerminal(to)) OperationOutcome.PERMANENT else OperationOutcome.NON_TERMINAL
    }

    private enum class OperationOutcome { RETRYABLE, PERMANENT, NON_TERMINAL }
}

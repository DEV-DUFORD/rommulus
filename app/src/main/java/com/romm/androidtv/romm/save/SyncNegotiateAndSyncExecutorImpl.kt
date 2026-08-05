package com.romm.androidtv.romm.save

import com.romm.androidtv.romm.ClientSaveState
import com.romm.androidtv.romm.DeviceIdentity
import com.romm.androidtv.romm.RommApiError
import com.romm.androidtv.romm.RommSyncApi
import com.romm.androidtv.romm.SaveConfirmResult
import com.romm.androidtv.romm.SaveDownloadResult
import com.romm.androidtv.romm.SaveUploadRequest
import com.romm.androidtv.romm.SaveUploadResult
import com.romm.androidtv.romm.SyncAction
import com.romm.androidtv.romm.SyncCompleteRequest
import com.romm.androidtv.romm.SyncNegotiateRequest
import com.romm.androidtv.romm.SyncNegotiateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.time.Instant

/**
 * Production [SyncNegotiateAndSyncExecutor] that authenticates from the durable
 * native credential store, ensures device registration, negotiates a fresh session,
 * and executes the server's returned action for a [PendingOperationType.NEGOTIATE_AND_SYNC]
 * operation (LIBRETRO_REFACTOR.md section 11.3 post-play).
 *
 * Never reuses a stale pre-play session. Conflict sets explicit CONFLICT state for UI.
 * Download obeys provenance/exact known size. confirmDownload failures are classified:
 * auth→AUTH_REQUIRED terminal; network/TLS/5xx→PENDING retry; permanent 4xx→PERMANENT_FAILURE.
 */
class SyncNegotiateAndSyncExecutorImpl(
    private val client: OkHttpClient,
    private val pendingOperationDao: PendingOperationDao,
    private val saveReplicaDao: SaveReplicaDao,
    private val saveContentStore: SaveContentStore,
    private val sessionReader: SessionReader,
    private val deviceIdentityLoader: DeviceIdentityLoader,
    private val uploadCaller: SaveUploadCaller,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Resolved per-upload: governs the server auto-clean of the autosave slot. Defaults to on. */
    private val shouldAutoclean: () -> Boolean = { true },
) : SyncNegotiateAndSyncExecutor {

    override suspend fun executeOne(op: PendingOperationEntity): SyncNegotiateAndSyncExecutor.ExecutionOutcome =
        withContext(Dispatchers.IO) {
            val now = clock()
            val currentAttempt = op.attemptCount + 1
            pendingOperationDao.updateStatus(op.id, PendingOperationStatus.RUNNING, currentAttempt, null, null, now)

            // --- Validate NEGOTIATE_AND_SYNC metadata ---
            val negotiateFileName = op.negotiateFileName
                ?: return@withContext failAndReturn(op.id, PendingOperationStatus.PERMANENT_FAILURE,
                    "NEGOTIATE_AND_SYNC missing negotiateFileName", null, now, currentAttempt)

            val negotiateCoreId = op.negotiateCoreId
                ?: return@withContext failAndReturn(op.id, PendingOperationStatus.PERMANENT_FAILURE,
                    "NEGOTIATE_AND_SYNC missing negotiateCoreId", null, now, currentAttempt)

            val negotiateCoreBuildRevision = op.negotiateCoreBuildRevision
                ?: return@withContext failAndReturn(op.id, PendingOperationStatus.PERMANENT_FAILURE,
                    "NEGOTIATE_AND_SYNC missing negotiateCoreBuildRevision", null, now, currentAttempt)

            // --- Authenticate from durable native credential store ---
            val session = sessionReader.current()
                ?: return@withContext failAndReturn(op.id, PendingOperationStatus.AUTH_REQUIRED,
                    "no active session", null, now, currentAttempt)

            val username = session.username
                ?: return@withContext failAndReturn(op.id, PendingOperationStatus.AUTH_REQUIRED,
                    "no username in session", null, now, currentAttempt)

            val origin = session.origin

            // --- Ensure device registration ---
            val deviceIdentity = deviceIdentityLoader.load(origin, username)
                ?: return@withContext failAndReturn(op.id, PendingOperationStatus.AUTH_REQUIRED,
                    "device not registered", null, now, currentAttempt)

            // --- Validate current SaveReplica generation ---
            val replica = saveReplicaDao.findByScope(op.serverKey, op.userKey, op.romId, op.romHash, op.slot)
                ?: return@withContext failAndReturn(op.id, PendingOperationStatus.PERMANENT_FAILURE,
                    "no local SaveReplica for scope", null, now, currentAttempt)

            val replicaGeneration = replica.localWrittenAtEpochMs
                ?: return@withContext failAndReturn(op.id, PendingOperationStatus.PERMANENT_FAILURE,
                    "SaveReplica has null local generation", null, now, currentAttempt)

            // Validate negotiateCoreBuildRevision against the current replica to prevent stale revision usage.
            if (replica.coreBuildRevision != negotiateCoreBuildRevision) {
                return@withContext failAndReturn(op.id, PendingOperationStatus.PERMANENT_FAILURE,
                    "coreBuildRevision mismatch: replica='${replica.coreBuildRevision}' vs operation='$negotiateCoreBuildRevision'",
                    null, now, currentAttempt)
            }

            if (replicaGeneration != op.localGenerationEpochMs) {
                return@withContext failAndReturn(op.id, PendingOperationStatus.PERMANENT_FAILURE,
                    "generation mismatch: replica=$replicaGeneration vs operation=${op.localGenerationEpochMs}",
                    null, now, currentAttempt)
            }

            // --- Read local bytes for ClientSaveState ---
            val localBytes = saveContentStore.readLocal(op.serverKey, op.userKey, op.romId, op.romHash, op.slot)

            val clientSaves = if (localBytes != null) {
                listOf(
                    ClientSaveState(
                        romId = op.romId,
                        fileName = negotiateFileName,
                        slot = op.slot,
                        emulator = negotiateCoreId,
                        contentHash = replica.localHash,
                        updatedAt = Instant.ofEpochMilli(replica.localWrittenAtEpochMs),
                        fileSizeBytes = localBytes.size.toLong(),
                    )
                )
            } else {
                emptyList()
            }

            // --- Negotiate a FRESH session ---
            val negotiation = when (
                val result = RommSyncApi.negotiateSync(
                    client, origin, SyncNegotiateRequest(deviceIdentity.rommDeviceId, clientSaves)
                )
            ) {
                is SyncNegotiateResult.Success -> result.negotiation
                is SyncNegotiateResult.Failure -> {
                    return@withContext when (result.error) {
                        RommApiError.AUTH_EXPIRED -> failAndReturn(op.id, PendingOperationStatus.AUTH_REQUIRED,
                            "auth expired during negotiate", result.httpCode, now, currentAttempt)
                        RommApiError.NETWORK_ERROR, RommApiError.TLS_ERROR ->
                            retryableAndReturn(op.id, now, currentAttempt)
                        else -> {
                            // 5xx → retry; permanent 4xx/other → PERMANENT_FAILURE
                            if (result.httpCode != null && result.httpCode in 500..599) {
                                retryableAndReturn(op.id, now, currentAttempt)
                            } else {
                                failAndReturn(op.id, PendingOperationStatus.PERMANENT_FAILURE,
                                    "negotiate failed: ${result.error}", result.httpCode, now, currentAttempt)
                            }
                        }
                    }
                }
            }

            val operation = negotiation.operations.firstOrNull { opItem ->
                opItem.romId == op.romId && (opItem.slot == null || opItem.slot == op.slot)
            } ?: return@withContext failAndReturn(op.id, PendingOperationStatus.PERMANENT_FAILURE,
                "negotiate returned no matching operation", null, now, currentAttempt)

            // --- Execute the returned action ---
            val (completed, failed) = when (operation.action) {
                SyncAction.UPLOAD -> executeUploadAction(op, replica, deviceIdentity, origin, negotiateFileName, now, currentAttempt)
                SyncAction.NO_OP -> executeNoOpAction(op, replica, operation, now, currentAttempt)
                SyncAction.DOWNLOAD -> executeDownloadAction(op, replica, negotiateCoreId, deviceIdentity, origin, negotiation, operation, now, currentAttempt)
                SyncAction.CONFLICT -> executeConflictAction(op, replica, operation, now, currentAttempt)
            }

            // --- Complete session with exact counters ---
            completeSession(origin, negotiation.sessionId, completed, failed)

            // Terminal persisted statuses never request WorkManager retry.
            val finalStatus = pendingOperationDao.findById(op.id)?.status
            if (completed > 0 || (finalStatus != null && PendingOperationTransitions.isTerminal(finalStatus))) {
                SyncNegotiateAndSyncExecutor.ExecutionOutcome.Completed
            } else {
                SyncNegotiateAndSyncExecutor.ExecutionOutcome.Retryable
            }
        }

    private suspend fun executeUploadAction(
        op: PendingOperationEntity,
        replica: SaveReplicaEntity,
        deviceIdentity: DeviceIdentity,
        origin: String,
        fileName: String,
        now: Long,
        attemptCount: Int,
    ): Pair<Int, Int> {
        val sramBytes = saveContentStore.readLocal(op.serverKey, op.userKey, op.romId, op.romHash, op.slot)
            ?: return transitionTo(op.id, PendingOperationStatus.RUNNING,
                PendingOperationStatus.PERMANENT_FAILURE, "local SRAM missing for upload", null, now, attemptCount).let { 0 to 1 }

        val uploadRequest = SaveUploadRequest(
            romId = op.romId,
            slot = op.slot,
            emulator = replica.coreId,
            deviceId = deviceIdentity.rommDeviceId,
            sessionId = null,
            overwrite = true,
            fileName = fileName,
            bytes = sramBytes,
            // Keep this device's own "autosave" slot from growing unbounded server-side —
            // the server still mints a new timestamped file per upload, but only the newest
            // 5 are retained. Other slots/devices/manual web-UI saves are untouched.
            // May be disabled by the user via Settings → Advanced → "Auto-clean uploaded saves".
            autocleanup = shouldAutoclean(),
            autocleanupLimit = 5,
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
                transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.SUCCEEDED, null, null, now, attemptCount)
                1 to 0
            }
            is SaveUploadResult.Conflict -> {
                transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.CONFLICT,
                    "server conflict during upload", uploadResult.httpCode, now, attemptCount)
                0 to 1
            }
            is SaveUploadResult.Failure -> when (uploadResult.error) {
                RommApiError.AUTH_EXPIRED -> {
                    transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.AUTH_REQUIRED,
                        "auth expired during upload", uploadResult.httpCode, now, attemptCount)
                    0 to 1
                }
                RommApiError.NETWORK_ERROR, RommApiError.TLS_ERROR -> {
                    transitionRetryable(op.id, now, attemptCount)
                    0 to 1
                }
                else -> {
                    transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.PERMANENT_FAILURE,
                        "upload failed: ${uploadResult.error}", uploadResult.httpCode, now, attemptCount)
                    0 to 1
                }
            }
        }
    }

    private suspend fun executeNoOpAction(
        op: PendingOperationEntity,
        replica: SaveReplicaEntity,
        operation: com.romm.androidtv.romm.SyncOperation,
        now: Long,
        attemptCount: Int,
    ): Pair<Int, Int> {
        saveReplicaDao.upsert(replica.copy(
            rommSaveId = operation.saveId ?: replica.rommSaveId,
            serverHash = operation.serverContentHash ?: replica.serverHash,
            serverUpdatedAtEpochMs = operation.serverUpdatedAt?.toEpochMilli() ?: replica.serverUpdatedAtEpochMs,
            syncStatus = SaveSyncStatus.SYNCED,
            lastError = null,
        ))
        transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.SUCCEEDED, null, null, now, attemptCount)
        return 1 to 0
    }

    private suspend fun executeDownloadAction(
        op: PendingOperationEntity,
        replica: SaveReplicaEntity,
        coreId: String,
        deviceIdentity: DeviceIdentity,
        origin: String,
        negotiation: com.romm.androidtv.romm.SyncNegotiateInfo,
        operation: com.romm.androidtv.romm.SyncOperation,
        now: Long,
        attemptCount: Int,
    ): Pair<Int, Int> {
        val saveId = operation.saveId
            ?: return transitionTo(op.id, PendingOperationStatus.RUNNING,
                PendingOperationStatus.PERMANENT_FAILURE, "download missing saveId", null, now, attemptCount).let { 0 to 1 }

        val bytes = when (val result = RommSyncApi.downloadSaveContent(client, origin, saveId, deviceIdentity.rommDeviceId, negotiation.sessionId)) {
            is SaveDownloadResult.Success -> result.bytes
            is SaveDownloadResult.Failure -> {
                return when (result.error) {
                    RommApiError.AUTH_EXPIRED -> transitionTo(op.id, PendingOperationStatus.RUNNING,
                        PendingOperationStatus.AUTH_REQUIRED, "auth expired during download", result.httpCode, now, attemptCount).let { 0 to 1 }
                    RommApiError.NETWORK_ERROR, RommApiError.TLS_ERROR ->
                        transitionRetryable(op.id, now, attemptCount).let { 0 to 1 }
                    else -> {
                        // 5xx → retry; permanent 4xx/other → PERMANENT_FAILURE
                        if (result.httpCode != null && result.httpCode in 500..599) {
                            transitionRetryable(op.id, now, attemptCount).let { 0 to 1 }
                        } else {
                            transitionTo(op.id, PendingOperationStatus.RUNNING,
                                PendingOperationStatus.PERMANENT_FAILURE, "download failed: ${result.error}", result.httpCode, now, attemptCount).let { 0 to 1 }
                        }
                    }
                }
            }
        }

        // Verify provenance.
        val provenanceKnown = operation.emulator != null && operation.emulator == coreId
        if (!provenanceKnown) {
            saveContentStore.quarantine(
                op.serverKey, op.userKey, op.romId, op.romHash, op.slot, bytes, "unknown-provenance", now,
            )
            saveReplicaDao.upsert(replica.copy(
                syncStatus = SaveSyncStatus.QUARANTINED,
                lastError = "quarantined: unknown-provenance (post-play)",
            ))
            transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.PERMANENT_FAILURE,
                "download quarantined: unknown-provenance", null, now, attemptCount)
            return 0 to 1
        }

        // Known trusted size: exact-size gate.
        val expectedSize = replica.expectedSramSizeBytes
        if (expectedSize != null && bytes.size.toLong() != expectedSize) {
            saveContentStore.quarantine(
                op.serverKey, op.userKey, op.romId, op.romHash, op.slot, bytes, "size-mismatch", now,
            )
            saveReplicaDao.upsert(replica.copy(
                syncStatus = SaveSyncStatus.QUARANTINED,
                lastError = "quarantined: size-mismatch (post-play)",
            ))
            transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.PERMANENT_FAILURE,
                "download quarantined: size-mismatch", null, now, attemptCount)
            return 0 to 1
        }

        // Adopt download: write atomically, confirm, complete.
        saveContentStore.writeLocalAtomically(op.serverKey, op.userKey, op.romId, op.romHash, op.slot, bytes)
        val localHash = com.romm.androidtv.emulation.model.sha256Hex(bytes)
        saveReplicaDao.upsert(replica.copy(
            localHash = localHash,
            localSizeBytes = bytes.size.toLong(),
            localWrittenAtEpochMs = now,
            rommSaveId = saveId,
            serverHash = operation.serverContentHash,
            serverSizeBytes = bytes.size.toLong(),
            serverUpdatedAtEpochMs = operation.serverUpdatedAt?.toEpochMilli(),
            syncStatus = SaveSyncStatus.SYNCED,
            lastError = null,
        ))

        // Confirm download — classify failure: auth→AUTH_REQUIRED terminal;
        // network/TLS/5xx→PENDING retry; permanent 4xx→PERMANENT_FAILURE.
        val confirmResult = RommSyncApi.confirmDownload(client, origin, saveId, deviceIdentity.rommDeviceId)
        return when (confirmResult) {
            is SaveConfirmResult.Success -> {
                transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.SUCCEEDED, null, null, now, attemptCount)
                1 to 0
            }
            is SaveConfirmResult.Failure -> {
                when (confirmResult.error) {
                    RommApiError.AUTH_EXPIRED -> {
                        transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.AUTH_REQUIRED,
                            "confirmDownload auth expired", confirmResult.httpCode, now, attemptCount)
                        0 to 1
                    }
                    RommApiError.NETWORK_ERROR, RommApiError.TLS_ERROR -> {
                        transitionRetryable(op.id, now, attemptCount)
                        0 to 1
                    }
                    else -> {
                        // 5xx → retry; permanent 4xx → PERMANENT_FAILURE
                        val httpCode = confirmResult.httpCode
                        if (httpCode != null && httpCode in 500..599) {
                            transitionRetryable(op.id, now, attemptCount)
                            0 to 1
                        } else {
                            transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.PERMANENT_FAILURE,
                                "confirmDownload permanent failure: ${confirmResult.error}", httpCode, now, attemptCount)
                            0 to 1
                        }
                    }
                }
            }
        }
    }

    private suspend fun executeConflictAction(
        op: PendingOperationEntity,
        replica: SaveReplicaEntity,
        operation: com.romm.androidtv.romm.SyncOperation,
        now: Long,
        attemptCount: Int,
    ): Pair<Int, Int> {
        saveReplicaDao.upsert(replica.copy(
            syncStatus = SaveSyncStatus.CONFLICT,
            lastError = operation.reason,
        ))
        transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.CONFLICT,
            operation.reason, null, now, attemptCount)
        return 0 to 1
    }

    /** RUNNING -> RETRYABLE_FAILURE -> PENDING, preserving attempt count. */
    private suspend fun transitionRetryable(id: Long, now: Long, attemptCount: Int): Pair<Int, Int> {
        pendingOperationDao.updateStatus(id, PendingOperationStatus.RETRYABLE_FAILURE, attemptCount, "transport failure", null, now)
        pendingOperationDao.updateStatus(id, PendingOperationStatus.PENDING, attemptCount, null, null, now)
        return 0 to 1
    }

    private suspend fun transitionTo(
        id: Long, from: PendingOperationStatus, to: PendingOperationStatus,
        error: String?, httpCode: Int?, now: Long, attemptCount: Int,
    ): Pair<Int, Int> {
        if (!PendingOperationTransitions.isValidTransition(from, to)) return 0 to 1
        pendingOperationDao.updateStatus(id, to, attemptCount, error, httpCode, now)
        return if (PendingOperationTransitions.isTerminal(to)) {
            if (to == PendingOperationStatus.SUCCEEDED) 1 to 0 else 0 to 1
        } else 0 to 1
    }

    private suspend fun failAndReturn(
        id: Long, to: PendingOperationStatus,
        error: String?, httpCode: Int?, now: Long, attemptCount: Int,
    ): SyncNegotiateAndSyncExecutor.ExecutionOutcome {
        transitionTo(id, PendingOperationStatus.RUNNING, to, error, httpCode, now, attemptCount)
        return if (PendingOperationTransitions.isTerminal(to)) {
            // Terminal statuses are done — do not request WorkManager retry.
            SyncNegotiateAndSyncExecutor.ExecutionOutcome.Completed
        } else {
            SyncNegotiateAndSyncExecutor.ExecutionOutcome.Retryable
        }
    }

    private suspend fun retryableAndReturn(
        id: Long, now: Long, attemptCount: Int,
    ): SyncNegotiateAndSyncExecutor.ExecutionOutcome {
        transitionRetryable(id, now, attemptCount)
        return SyncNegotiateAndSyncExecutor.ExecutionOutcome.Retryable
    }

    private fun completeSession(origin: String, sessionId: Long, completed: Int, failed: Int) {
        when (val result = RommSyncApi.completeSyncSession(client, origin, sessionId, SyncCompleteRequest(completed, failed))) {
            is com.romm.androidtv.romm.SyncCompleteResult.Success -> Unit
            is com.romm.androidtv.romm.SyncCompleteResult.Failure ->
                Log.warning("completeSyncSession failed (non-fatal): session=$sessionId error=${result.error}")
        }
    }

    private companion object {
        val Log = java.util.logging.Logger.getLogger("SyncNegotiateAndSyncExecutor")
    }
}

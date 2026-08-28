package com.romm.desktop.sync

import com.romm.androidtv.emulation.model.sha256Hex
import com.romm.androidtv.romm.ClientSaveState
import com.romm.androidtv.romm.RommApiError
import com.romm.androidtv.romm.SaveUploadRequest
import com.romm.androidtv.storage.ports.PendingOperationStore
import com.romm.androidtv.storage.ports.SaveReplicaScope
import com.romm.androidtv.storage.ports.SaveReplicaStore
import com.romm.androidtv.storage.records.PendingOperationRecord
import com.romm.androidtv.storage.records.PendingOperationStatus
import com.romm.androidtv.storage.records.PendingOperationType
import com.romm.androidtv.storage.records.SaveReplicaRecord
import com.romm.androidtv.storage.records.SaveSyncStatus
import java.time.Instant
import java.util.logging.Logger

/**
 * Pure validator for [PendingOperationStatus] transitions — a faithful mirror of Android's
 * `com.romm.androidtv.romm.save.PendingOperationTransitions` (LIBRETRO_REFACTOR.md section 11.4):
 *
 * ```text
 * PENDING -> RUNNING -> SUCCEEDED
 *                     -> RETRYABLE_FAILURE -> PENDING
 *                     -> AUTH_REQUIRED
 *                     -> CONFLICT
 *                     -> PERMANENT_FAILURE
 * ```
 *
 * [SUCCEEDED], [AUTH_REQUIRED], [CONFLICT], and [PERMANENT_FAILURE] are terminal: resuming after
 * them means creating a NEW operation row, never mutating a terminal one in place. (The shared
 * storage-api module cannot be modified in this sub-unit, so the table is mirrored here.)
 */
object PendingOperationTransitions {

    private val allowed: Map<PendingOperationStatus, Set<PendingOperationStatus>> = mapOf(
        PendingOperationStatus.PENDING to setOf(PendingOperationStatus.RUNNING),
        PendingOperationStatus.RUNNING to setOf(
            PendingOperationStatus.SUCCEEDED,
            PendingOperationStatus.RETRYABLE_FAILURE,
            PendingOperationStatus.AUTH_REQUIRED,
            PendingOperationStatus.CONFLICT,
            PendingOperationStatus.PERMANENT_FAILURE,
        ),
        PendingOperationStatus.RETRYABLE_FAILURE to setOf(PendingOperationStatus.PENDING),
        PendingOperationStatus.SUCCEEDED to emptySet(),
        PendingOperationStatus.AUTH_REQUIRED to emptySet(),
        PendingOperationStatus.CONFLICT to emptySet(),
        PendingOperationStatus.PERMANENT_FAILURE to emptySet(),
    )

    /** Whether [from] -> [to] is a legal transition per section 11.4's state diagram. */
    fun isValidTransition(from: PendingOperationStatus, to: PendingOperationStatus): Boolean =
        to in allowed.getValue(from)

    /** True if no further automatic transition out of [status] exists. */
    fun isTerminal(status: PendingOperationStatus): Boolean = allowed.getValue(status).isEmpty()
}

/**
 * The desktop save-sync drain executor (Phase 9, plans/LINUX_X64.md — "offline play queues
 * upload" + "conflict preserves both copies"). This is a faithful port of the Android state
 * machine: `SaveUploadExecutorImpl` (UPLOAD ops) + `SyncNegotiateAndSyncExecutorImpl`
 * (NEGOTIATE_AND_SYNC ops), collapsed into one class because desktop has no WorkManager and no
 * coroutines dependency — [drainBatch] is a plain blocking call meant to run on the scheduler's
 * worker thread.
 *
 * Durability contract (same as Android): the durable queue ([PendingOperationStore]) is the
 * source of truth; an operation that entered RUNNING is NEVER left stranded — unexpected
 * exceptions perform RUNNING -> RETRYABLE_FAILURE -> PENDING. Terminal statuses (SUCCEEDED,
 * AUTH_REQUIRED, CONFLICT, PERMANENT_FAILURE) are never mutated in place.
 *
 * Conflict semantics: a 409 upload or a negotiated CONFLICT action marks the replica CONFLICT and
 * the operation CONFLICT — local bytes are NEVER overwritten; both copies are preserved for an
 * explicit user choice (conflict resolution is a separate sub-unit).
 */
class SaveSyncDrainExecutor(
    private val pendingOperations: PendingOperationStore,
    private val saveReplicas: SaveReplicaStore,
    private val content: SaveContentGateway,
    private val sessionReader: SaveSyncSessionReader,
    private val deviceIdentityLoader: SaveSyncDeviceIdentityLoader,
    private val sync: RommSyncGateway,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Resolved per-upload: governs the server auto-clean of the autosave slot. Defaults to on. */
    private val shouldAutoclean: () -> Boolean = { true },
) {

    /**
     * Outcome of one [drainBatch] — mirrors Android's `SaveUploadExecutor.DrainResult`, extended
     * with [Retry.maxAttemptCount] so the desktop scheduler can feed
     * `BackgroundSyncScheduler.scheduleRetryAfter(tentativeAttemptCount, cause)` (WorkManager had
     * no such need; desktop backoff does).
     */
    sealed interface DrainResult {
        /** No operations remain PENDING/RETRYABLE_FAILURE — the scheduler may markDrained(). */
        data object Complete : DrainResult

        /**
         * At least one operation remains retryable (it sits back in PENDING after this batch).
         * [maxAttemptCount] is the highest attempt count among the remaining PENDING operations —
         * use it as the scheduler's tentativeAttemptCount for backoff.
         */
        data class Retry(val maxAttemptCount: Int) : DrainResult
    }

    /** One drain cycle: recover stranded RUNNING rows, then process every PENDING operation. */
    fun drainBatch(): DrainResult {
        // First, recover any pre-existing RUNNING rows that may have been stranded
        // by a prior crash or process death.
        recoverStrandedRunningOperations()

        val pending = pendingOperations.findByStatus(PendingOperationStatus.PENDING)
        if (pending.isEmpty()) return DrainResult.Complete

        var anyRetryable = false
        for (op in pending) {
            val result = processOne(op)
            if (result == OperationOutcome.RETRYABLE) anyRetryable = true
        }
        if (!anyRetryable) return DrainResult.Complete
        // Retryable ops were put back to PENDING with their incremented attempt count; report the
        // highest so the scheduler's backoff reflects real retry pressure.
        val remaining = pendingOperations.findByStatus(PendingOperationStatus.PENDING)
        return DrainResult.Retry(remaining.maxOfOrNull { it.attemptCount } ?: 1)
    }

    /**
     * Recovers any pre-existing RUNNING rows that may have been stranded by a crash or process
     * death. Transitions them to RETRYABLE_FAILURE -> PENDING so the drain cycle retries them.
     */
    private fun recoverStrandedRunningOperations() {
        val running = pendingOperations.findByStatus(PendingOperationStatus.RUNNING)
        if (running.isEmpty()) return

        for (op in running) {
            try {
                val now = clock()
                val currentAttempt = op.attemptCount
                log.warning("recoverStrandedRunningOperations: recovering stranded RUNNING operation ${op.id} (attempt=$currentAttempt)")
                markStatus(op.id, PendingOperationStatus.RETRYABLE_FAILURE, currentAttempt, "recovered from stranded RUNNING state", null, now)
                markStatus(op.id, PendingOperationStatus.PENDING, currentAttempt, null, null, now)
            } catch (e: Exception) {
                log.severe("recoverStrandedRunningOperations: failed to recover operation ${op.id}: $e")
            }
        }
    }

    private fun processOne(op: PendingOperationRecord): OperationOutcome {
        return try {
            when (op.operationType) {
                PendingOperationType.UPLOAD -> processUpload(op)
                PendingOperationType.NEGOTIATE_AND_SYNC -> processNegotiateAndSync(op)
            }
        } catch (e: Exception) {
            // Unexpected exceptions after an operation becomes RUNNING must never strand it.
            handleUnexpectedException(op, e)
        }
    }

    // ------------------------------------------------------------------ UPLOAD operations

    /** Port of Android `SaveUploadExecutorImpl.processUpload` — transitions verbatim. */
    private fun processUpload(op: PendingOperationRecord): OperationOutcome {
        val now = clock()
        val currentAttempt = op.attemptCount + 1
        markStatus(op.id, PendingOperationStatus.RUNNING, currentAttempt, null, null, now)

        val origin = op.origin
            ?: return transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.PERMANENT_FAILURE,
                "legacy operation missing origin metadata", null, now, currentAttempt)

        val uploadFileName = op.uploadFileName
            ?: return transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.PERMANENT_FAILURE,
                "legacy operation missing upload filename", null, now, currentAttempt)

        val session = sessionReader.current()
            ?: return transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.AUTH_REQUIRED,
                "no active session", null, now, currentAttempt)
        val username = session.username
            ?: return transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.AUTH_REQUIRED,
                "no username in session", null, now, currentAttempt)

        val deviceIdentity = deviceIdentityLoader.load(origin, username)
            ?: return transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.AUTH_REQUIRED,
                "device not registered", null, now, currentAttempt)

        val replica = saveReplicas.findByScope(SaveReplicaScope(op.serverKey, op.userKey, op.romId, op.romHash, op.slot))
            ?: return transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.PERMANENT_FAILURE,
                "no local SaveReplica found for scope", null, now, currentAttempt)

        val replicaGeneration = replica.localWrittenAtEpochMs
            ?: return transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.PERMANENT_FAILURE,
                "SaveReplica has null local generation — cannot validate", null, now, currentAttempt)

        if (replicaGeneration != op.localGenerationEpochMs) {
            return transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.PERMANENT_FAILURE,
                "generation mismatch: replica=$replicaGeneration vs operation=${op.localGenerationEpochMs}", null, now, currentAttempt)
        }

        val sramBytes = content.readLocal(op.serverKey, op.userKey, op.romId, op.romHash, op.slot)
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
            // Keeps this device's own "autosave" slot at a short recent history (5 files) rather
            // than growing unbounded. May be disabled by the user via Settings → Advanced.
            autocleanup = shouldAutoclean(),
            autocleanupLimit = 5,
        )
        val uploadResult = sync.uploadSave(origin, uploadRequest)

        return when (uploadResult) {
            is com.romm.androidtv.romm.SaveUploadResult.Success -> {
                saveReplicas.markSyncedIfGenerationMatches(
                    scope = SaveReplicaScope(op.serverKey, op.userKey, op.romId, op.romHash, op.slot),
                    localGenerationEpochMs = op.localGenerationEpochMs,
                    rommSaveId = uploadResult.save.saveId,
                    serverHash = uploadResult.save.contentHash,
                    serverSizeBytes = uploadResult.save.fileSizeBytes,
                    serverUpdatedAtEpochMs = uploadResult.save.updatedAt?.toEpochMilli(),
                )
                transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.SUCCEEDED, null, null, now, currentAttempt)
            }
            is com.romm.androidtv.romm.SaveUploadResult.Conflict ->
                // "Conflict preserves both copies": local bytes untouched; replica keeps its
                // local generation for an explicit user choice.
                transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.CONFLICT,
                    "server conflict", uploadResult.httpCode, now, currentAttempt)
            is com.romm.androidtv.romm.SaveUploadResult.Failure -> when (uploadResult.error) {
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

    // ------------------------------------------------------- NEGOTIATE_AND_SYNC operations

    /** Port of Android `SyncNegotiateAndSyncExecutorImpl.executeOne` — transitions verbatim. */
    private fun processNegotiateAndSync(op: PendingOperationRecord): OperationOutcome {
        val now = clock()
        val currentAttempt = op.attemptCount + 1
        markStatus(op.id, PendingOperationStatus.RUNNING, currentAttempt, null, null, now)

        // --- Validate NEGOTIATE_AND_SYNC metadata ---
        val negotiateFileName = op.negotiateFileName
            ?: return failAndReturn(op.id, PendingOperationStatus.PERMANENT_FAILURE,
                "NEGOTIATE_AND_SYNC missing negotiateFileName", null, now, currentAttempt)

        val negotiateCoreId = op.negotiateCoreId
            ?: return failAndReturn(op.id, PendingOperationStatus.PERMANENT_FAILURE,
                "NEGOTIATE_AND_SYNC missing negotiateCoreId", null, now, currentAttempt)

        val negotiateCoreBuildRevision = op.negotiateCoreBuildRevision
            ?: return failAndReturn(op.id, PendingOperationStatus.PERMANENT_FAILURE,
                "NEGOTIATE_AND_SYNC missing negotiateCoreBuildRevision", null, now, currentAttempt)

        // --- Authenticate from the durable session store ---
        val session = sessionReader.current()
            ?: return failAndReturn(op.id, PendingOperationStatus.AUTH_REQUIRED,
                "no active session", null, now, currentAttempt)

        val username = session.username
            ?: return failAndReturn(op.id, PendingOperationStatus.AUTH_REQUIRED,
                "no username in session", null, now, currentAttempt)

        val origin = session.origin

        // --- Ensure device registration ---
        val deviceIdentity = deviceIdentityLoader.load(origin, username)
            ?: return failAndReturn(op.id, PendingOperationStatus.AUTH_REQUIRED,
                "device not registered", null, now, currentAttempt)

        // --- Validate current SaveReplica generation ---
        val replica = saveReplicas.findByScope(SaveReplicaScope(op.serverKey, op.userKey, op.romId, op.romHash, op.slot))
            ?: return failAndReturn(op.id, PendingOperationStatus.PERMANENT_FAILURE,
                "no local SaveReplica for scope", null, now, currentAttempt)

        val replicaGeneration = replica.localWrittenAtEpochMs
            ?: return failAndReturn(op.id, PendingOperationStatus.PERMANENT_FAILURE,
                "SaveReplica has null local generation", null, now, currentAttempt)

        // Validate negotiateCoreBuildRevision against the current replica to prevent stale revision usage.
        if (replica.coreBuildRevision != negotiateCoreBuildRevision) {
            return failAndReturn(op.id, PendingOperationStatus.PERMANENT_FAILURE,
                "coreBuildRevision mismatch: replica='${replica.coreBuildRevision}' vs operation='$negotiateCoreBuildRevision'",
                null, now, currentAttempt)
        }

        if (replicaGeneration != op.localGenerationEpochMs) {
            return failAndReturn(op.id, PendingOperationStatus.PERMANENT_FAILURE,
                "generation mismatch: replica=$replicaGeneration vs operation=${op.localGenerationEpochMs}",
                null, now, currentAttempt)
        }

        // --- Read local bytes for ClientSaveState ---
        val localBytes = content.readLocal(op.serverKey, op.userKey, op.romId, op.romHash, op.slot)

        val clientSaves = if (localBytes != null) {
            listOf(
                ClientSaveState(
                    romId = op.romId,
                    fileName = negotiateFileName,
                    slot = op.slot,
                    emulator = negotiateCoreId,
                    contentHash = replica.localHash,
                    updatedAt = Instant.ofEpochMilli(replicaGeneration),
                    fileSizeBytes = localBytes.size.toLong(),
                )
            )
        } else {
            emptyList()
        }

        // --- Negotiate a FRESH session (never reuse a stale pre-play one) ---
        val negotiation = when (val result = sync.negotiateSync(origin, com.romm.androidtv.romm.SyncNegotiateRequest(deviceIdentity.rommDeviceId, clientSaves))) {
            is com.romm.androidtv.romm.SyncNegotiateResult.Success -> result.negotiation
            is com.romm.androidtv.romm.SyncNegotiateResult.Failure -> {
                return when (result.error) {
                    RommApiError.AUTH_EXPIRED -> failAndReturn(op.id, PendingOperationStatus.AUTH_REQUIRED,
                        "auth expired during negotiate", result.httpCode, now, currentAttempt)
                    RommApiError.NETWORK_ERROR, RommApiError.TLS_ERROR ->
                        retryableAndReturn(op.id, now, currentAttempt)
                    else -> {
                        // 5xx -> retry; permanent 4xx/other -> PERMANENT_FAILURE
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
        } ?: return failAndReturn(op.id, PendingOperationStatus.PERMANENT_FAILURE,
            "negotiate returned no matching operation", null, now, currentAttempt)

        // --- Execute the returned action ---
        val (completed, failed) = when (operation.action) {
            com.romm.androidtv.romm.SyncAction.UPLOAD -> executeUploadAction(op, replica, deviceIdentity, origin, negotiateFileName, now, currentAttempt)
            com.romm.androidtv.romm.SyncAction.NO_OP -> executeNoOpAction(op, replica, operation, now, currentAttempt)
            com.romm.androidtv.romm.SyncAction.DOWNLOAD -> executeDownloadAction(op, replica, negotiateCoreId, deviceIdentity, origin, negotiation, operation, now, currentAttempt)
            com.romm.androidtv.romm.SyncAction.CONFLICT -> executeConflictAction(op, replica, operation, now, currentAttempt)
        }

        // --- Complete session with exact counters (non-fatal on failure) ---
        completeSession(origin, negotiation.sessionId, completed, failed)

        // Terminal persisted statuses never request a retry.
        val finalStatus = op.id?.let { id -> pendingOperations.findById(id) }?.status
        return if (completed > 0 || (finalStatus != null && PendingOperationTransitions.isTerminal(finalStatus))) {
            OperationOutcome.PERMANENT
        } else {
            OperationOutcome.RETRYABLE
        }
    }

    private fun executeUploadAction(
        op: PendingOperationRecord,
        replica: SaveReplicaRecord,
        deviceIdentity: com.romm.androidtv.romm.DeviceIdentity,
        origin: String,
        fileName: String,
        now: Long,
        attemptCount: Int,
    ): Pair<Int, Int> {
        val sramBytes = content.readLocal(op.serverKey, op.userKey, op.romId, op.romHash, op.slot)
            ?: return transitionToCounters(op.id, PendingOperationStatus.RUNNING,
                PendingOperationStatus.PERMANENT_FAILURE, "local SRAM missing for upload", null, now, attemptCount)

        val uploadRequest = SaveUploadRequest(
            romId = op.romId,
            slot = op.slot,
            emulator = replica.coreId,
            deviceId = deviceIdentity.rommDeviceId,
            sessionId = null, // fresh negotiated session — bookkeeping via the negotiation's sessionId
            overwrite = true,
            fileName = fileName,
            bytes = sramBytes,
            autocleanup = shouldAutoclean(),
            autocleanupLimit = 5,
        )
        val uploadResult = sync.uploadSave(origin, uploadRequest)

        return when (uploadResult) {
            is com.romm.androidtv.romm.SaveUploadResult.Success -> {
                saveReplicas.markSyncedIfGenerationMatches(
                    scope = SaveReplicaScope(op.serverKey, op.userKey, op.romId, op.romHash, op.slot),
                    localGenerationEpochMs = op.localGenerationEpochMs,
                    rommSaveId = uploadResult.save.saveId,
                    serverHash = uploadResult.save.contentHash,
                    serverSizeBytes = uploadResult.save.fileSizeBytes,
                    serverUpdatedAtEpochMs = uploadResult.save.updatedAt?.toEpochMilli(),
                )
                transitionToCounters(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.SUCCEEDED, null, null, now, attemptCount)
                1 to 0
            }
            is com.romm.androidtv.romm.SaveUploadResult.Conflict -> {
                transitionToCounters(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.CONFLICT,
                    "server conflict during upload", uploadResult.httpCode, now, attemptCount)
                0 to 1
            }
            is com.romm.androidtv.romm.SaveUploadResult.Failure -> when (uploadResult.error) {
                RommApiError.AUTH_EXPIRED -> {
                    transitionToCounters(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.AUTH_REQUIRED,
                        "auth expired during upload", uploadResult.httpCode, now, attemptCount)
                    0 to 1
                }
                RommApiError.NETWORK_ERROR, RommApiError.TLS_ERROR -> {
                    transitionRetryable(op.id, now, attemptCount)
                    0 to 1
                }
                else -> {
                    // Faithful to Android: in the negotiate path a SERVER_ERROR (incl. 5xx) is NOT
                    // retried here — the fresh negotiation will re-run on the next play/drain.
                    transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.PERMANENT_FAILURE,
                        "upload failed: ${uploadResult.error}", uploadResult.httpCode, now, attemptCount)
                    0 to 1
                }
            }
        }
    }

    private fun executeNoOpAction(
        op: PendingOperationRecord,
        replica: SaveReplicaRecord,
        operation: com.romm.androidtv.romm.SyncOperation,
        now: Long,
        attemptCount: Int,
    ): Pair<Int, Int> {
        upsertReplica(replica.copy(
            rommSaveId = operation.saveId ?: replica.rommSaveId,
            serverHash = operation.serverContentHash ?: replica.serverHash,
            serverUpdatedAtEpochMs = operation.serverUpdatedAt?.toEpochMilli() ?: replica.serverUpdatedAtEpochMs,
            syncStatus = SaveSyncStatus.SYNCED,
            lastError = null,
        ))
        transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.SUCCEEDED, null, null, now, attemptCount)
        return 1 to 0
    }

    private fun executeDownloadAction(
        op: PendingOperationRecord,
        replica: SaveReplicaRecord,
        coreId: String,
        deviceIdentity: com.romm.androidtv.romm.DeviceIdentity,
        origin: String,
        negotiation: com.romm.androidtv.romm.SyncNegotiateInfo,
        operation: com.romm.androidtv.romm.SyncOperation,
        now: Long,
        attemptCount: Int,
    ): Pair<Int, Int> {
        val saveId = operation.saveId
            ?: return transitionTo(op.id, PendingOperationStatus.RUNNING,
                PendingOperationStatus.PERMANENT_FAILURE, "download missing saveId", null, now, attemptCount).let { 0 to 1 }

        val bytes = when (val result = sync.downloadSaveContent(origin, saveId, deviceIdentity.rommDeviceId, negotiation.sessionId)) {
            is com.romm.androidtv.romm.SaveDownloadResult.Success -> result.bytes
            is com.romm.androidtv.romm.SaveDownloadResult.Failure -> {
                return when (result.error) {
                    RommApiError.AUTH_EXPIRED -> transitionTo(op.id, PendingOperationStatus.RUNNING,
                        PendingOperationStatus.AUTH_REQUIRED, "auth expired during download", result.httpCode, now, attemptCount).let { 0 to 1 }
                    RommApiError.NETWORK_ERROR, RommApiError.TLS_ERROR ->
                        transitionRetryable(op.id, now, attemptCount).let { 0 to 1 }
                    else -> {
                        // 5xx -> retry; permanent 4xx/other -> PERMANENT_FAILURE
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
            content.quarantine(op.serverKey, op.userKey, op.romId, op.romHash, op.slot, bytes, "unknown-provenance", now)
            upsertReplica(replica.copy(
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
            content.quarantine(op.serverKey, op.userKey, op.romId, op.romHash, op.slot, bytes, "size-mismatch", now)
            upsertReplica(replica.copy(
                syncStatus = SaveSyncStatus.QUARANTINED,
                lastError = "quarantined: size-mismatch (post-play)",
            ))
            transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.PERMANENT_FAILURE,
                "download quarantined: size-mismatch", null, now, attemptCount)
            return 0 to 1
        }

        // Adopt download: write atomically, confirm, complete. Local bytes are replaced ONLY after
        // provenance + exact-size validation — the prior local copy is what the user resolves
        // against if a later conflict occurs ("conflict preserves both copies").
        content.writeLocalAtomically(op.serverKey, op.userKey, op.romId, op.romHash, op.slot, bytes)
        val localHash = sha256Hex(bytes)
        upsertReplica(replica.copy(
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

        // Confirm download — classify failure: auth -> AUTH_REQUIRED terminal;
        // network/TLS/5xx -> PENDING retry; permanent 4xx -> PERMANENT_FAILURE.
        val confirmResult = sync.confirmDownload(origin, saveId, deviceIdentity.rommDeviceId)
        return when (confirmResult) {
            is com.romm.androidtv.romm.SaveConfirmResult.Success -> {
                transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.SUCCEEDED, null, null, now, attemptCount)
                1 to 0
            }
            is com.romm.androidtv.romm.SaveConfirmResult.Failure -> {
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
                        // 5xx -> retry; permanent 4xx -> PERMANENT_FAILURE
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

    private fun executeConflictAction(
        op: PendingOperationRecord,
        replica: SaveReplicaRecord,
        operation: com.romm.androidtv.romm.SyncOperation,
        now: Long,
        attemptCount: Int,
    ): Pair<Int, Int> {
        // "Conflict preserves both copies": mark the replica CONFLICT (UI surfaces it); local bytes
        // are untouched and the server copy stays on the server until an explicit user choice.
        // Also record the negotiation's server-side identity (save id + content hash) so explicit
        // conflict resolution later knows WHICH server copy to download/keep.
        upsertReplica(replica.copy(
            syncStatus = SaveSyncStatus.CONFLICT,
            lastError = operation.reason,
            rommSaveId = operation.saveId ?: replica.rommSaveId,
            serverHash = operation.serverContentHash ?: replica.serverHash,
        ))
        transitionTo(op.id, PendingOperationStatus.RUNNING, PendingOperationStatus.CONFLICT,
            operation.reason, null, now, attemptCount)
        return 0 to 1
    }

    // ------------------------------------------------------------------ shared helpers

    /** Handles an unexpected exception that occurred after an operation entered RUNNING. */
    private fun handleUnexpectedException(op: PendingOperationRecord, e: Exception): OperationOutcome {
        val now = clock()
        val currentAttempt = op.attemptCount + 1 // This attempt ran, even though it threw.
        log.severe("processOne: unexpected exception for operation ${op.id}: $e")
        try {
            markStatus(op.id, PendingOperationStatus.RETRYABLE_FAILURE, currentAttempt, "unexpected exception: ${e.javaClass.simpleName}: ${e.message}", null, now)
            markStatus(op.id, PendingOperationStatus.PENDING, currentAttempt, null, null, now)
        } catch (recoveryEx: Exception) {
            log.severe("handleUnexpectedException: FAILED to recover operation ${op.id} — operation may be stranded: $recoveryEx")
        }
        return OperationOutcome.RETRYABLE
    }

    /** RUNNING -> RETRYABLE_FAILURE -> PENDING, preserving attempt count. */
    private fun transitionRetryable(id: Long?, now: Long, attemptCount: Int): OperationOutcome {
        markStatus(id, PendingOperationStatus.RETRYABLE_FAILURE, attemptCount, "transport failure", null, now)
        markStatus(id, PendingOperationStatus.PENDING, attemptCount, null, null, now)
        return OperationOutcome.RETRYABLE
    }

    /** Upload-path transition: validates the table, persists, and classifies the outcome. */
    private fun transitionTo(
        id: Long?, from: PendingOperationStatus, to: PendingOperationStatus,
        error: String?, httpCode: Int?, now: Long, attemptCount: Int,
    ): OperationOutcome {
        if (!PendingOperationTransitions.isValidTransition(from, to)) return OperationOutcome.PERMANENT
        markStatus(id, to, attemptCount, error, httpCode, now)
        return if (PendingOperationTransitions.isTerminal(to)) OperationOutcome.PERMANENT else OperationOutcome.NON_TERMINAL
    }

    /** Negotiate-path transition: same table, but returns the session's (completed, failed) counters. */
    private fun transitionToCounters(
        id: Long?, from: PendingOperationStatus, to: PendingOperationStatus,
        error: String?, httpCode: Int?, now: Long, attemptCount: Int,
    ): Pair<Int, Int> {
        if (!PendingOperationTransitions.isValidTransition(from, to)) return 0 to 1
        markStatus(id, to, attemptCount, error, httpCode, now)
        return if (PendingOperationTransitions.isTerminal(to)) {
            if (to == PendingOperationStatus.SUCCEEDED) 1 to 0 else 0 to 1
        } else 0 to 1
    }

    private fun failAndReturn(
        id: Long?, to: PendingOperationStatus,
        error: String?, httpCode: Int?, now: Long, attemptCount: Int,
    ): OperationOutcome {
        transitionToCounters(id, PendingOperationStatus.RUNNING, to, error, httpCode, now, attemptCount)
        return if (PendingOperationTransitions.isTerminal(to)) {
            // Terminal statuses are done — do not request a retry.
            OperationOutcome.PERMANENT
        } else {
            OperationOutcome.RETRYABLE
        }
    }

    private fun retryableAndReturn(id: Long?, now: Long, attemptCount: Int): OperationOutcome {
        markStatus(id, PendingOperationStatus.RETRYABLE_FAILURE, attemptCount, "transport failure", null, now)
        markStatus(id, PendingOperationStatus.PENDING, attemptCount, null, null, now)
        return OperationOutcome.RETRYABLE
    }

    private fun completeSession(origin: String, sessionId: Long, completed: Int, failed: Int) {
        when (val result = sync.completeSyncSession(origin, sessionId, com.romm.androidtv.romm.SyncCompleteRequest(completed, failed))) {
            is com.romm.androidtv.romm.SyncCompleteResult.Success -> Unit
            is com.romm.androidtv.romm.SyncCompleteResult.Failure ->
                log.warning("completeSyncSession failed (non-fatal): session=$sessionId error=${result.error}")
        }
    }

    /** Unwraps the store's Result, turning a persistence failure into an exception so the
     *  never-strand recovery path (RUNNING -> RETRYABLE_FAILURE -> PENDING) applies. */
    private fun markStatus(id: Long?, status: PendingOperationStatus, attemptCount: Int, lastError: String?, lastHttpCode: Int?, now: Long) {
        val opId = id ?: throw IllegalStateException("pending operation has no assigned id")
        val result = pendingOperations.updateStatus(opId, status, attemptCount, lastError, lastHttpCode, now)
        if (result.isFailure) throw IllegalStateException("updateStatus failed for operation $id -> $status", result.exceptionOrNull())
    }

    private fun upsertReplica(replica: SaveReplicaRecord) {
        val result = saveReplicas.upsert(replica)
        if (result.isFailure) throw IllegalStateException("upsert replica failed for scope ${replica.serverKey}/${replica.userKey}/${replica.romId}", result.exceptionOrNull())
    }

    private enum class OperationOutcome { RETRYABLE, PERMANENT, NON_TERMINAL }

    companion object {
        private val log: Logger = Logger.getLogger("SaveSyncDrainExecutor")
    }
}

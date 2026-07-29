package com.romm.androidtv.romm.save

import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.network.RommOrigin
import com.romm.androidtv.romm.ClientSaveState
import com.romm.androidtv.romm.DeviceRegistrationResult
import com.romm.androidtv.romm.DeviceRepository
import com.romm.androidtv.romm.RommApiError
import com.romm.androidtv.romm.RommSyncApi
import com.romm.androidtv.romm.SaveDownloadResult
import com.romm.androidtv.romm.SaveConfirmResult
import com.romm.androidtv.romm.SyncAction
import com.romm.androidtv.romm.SyncCompleteRequest
import com.romm.androidtv.romm.SyncNegotiateInfo
import com.romm.androidtv.romm.SyncNegotiateRequest
import com.romm.androidtv.romm.SyncNegotiateResult
import com.romm.androidtv.romm.SyncOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.time.Instant

/**
 * Real [SaveSyncCoordinator] implementation (LIBRETRO_REFACTOR.md section
 * 11.3). One [syncBeforeLaunch] call performs exactly one
 * `POST /api/sync/negotiate` round trip for a single ROM/slot scope, applies
 * whichever of the four outcomes the server returned, and completes the
 * sync session accordingly:
 *
 * - `no_op`: just records the agreed-upon metadata.
 * - `download`: synchronously downloads, verifies (provenance + exact
 *   expected SRAM size), and atomically adopts the server's copy — this
 *   must finish before launch, so it is never deferred to a background
 *   queue.
 * - `upload`: never uploads inline. It durably queues a
 *   [PendingOperationEntity] (idempotent: an already-active queued
 *   operation for the exact same local generation is reused rather than
 *   duplicated; a stale one for an older generation is superseded first).
 *   The actual HTTP upload, with backoff, is Milestone 7's
 *   `CoroutineWorker`'s job.
 * - `conflict`: stops automatically, persists [SaveSyncStatus.CONFLICT],
 *   and returns the server's side of the conflict for a future explicit
 *   user choice (Milestone 8's conflict-resolution screen). Never guesses
 *   from wall-clock timestamps.
 */
class SaveSyncCoordinatorImpl(
    private val client: OkHttpClient,
    private val sessionStore: SessionStore,
    private val deviceRepository: DeviceRepository,
    private val saveReplicaDao: SaveReplicaDao,
    private val pendingOperationDao: PendingOperationDao,
    private val saveContentStore: SaveContentStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SaveSyncCoordinator {

    override suspend fun syncBeforeLaunch(request: SaveSyncRequest): SaveSyncOutcome = withContext(Dispatchers.IO) {
        val session = sessionStore.current() ?: return@withContext SaveSyncOutcome.Failure(RommApiError.AUTH_EXPIRED)
        val username = session.username ?: return@withContext SaveSyncOutcome.Failure(RommApiError.AUTH_EXPIRED)
        val origin = session.origin
        val serverKey = RommOrigin.parse(origin)?.host ?: origin
        val userKey = username

        val registration = deviceRepository.ensureRegistered(origin, username)
        val deviceId = when (registration) {
            is DeviceRegistrationResult.Success -> registration.identity.rommDeviceId
            is DeviceRegistrationResult.Failure ->
                return@withContext SaveSyncOutcome.Failure(registration.error, registration.httpCode)
        }

        val existingReplica = saveReplicaDao.findByScope(serverKey, userKey, request.romId, request.romHash, request.slot)
        val localBytes = saveContentStore.readLocal(serverKey, userKey, request.romId, request.romHash, request.slot)

        val clientSaves = if (existingReplica?.localWrittenAtEpochMs != null && localBytes != null) {
            listOf(
                ClientSaveState(
                    romId = request.romId,
                    fileName = request.fileName,
                    slot = request.slot,
                    emulator = request.coreId,
                    contentHash = existingReplica.localHash,
                    updatedAt = Instant.ofEpochMilli(existingReplica.localWrittenAtEpochMs),
                    fileSizeBytes = localBytes.size.toLong(),
                )
            )
        } else {
            emptyList()
        }

        val negotiation = when (
            val result = RommSyncApi.negotiateSync(client, origin, SyncNegotiateRequest(deviceId, clientSaves))
        ) {
            is SyncNegotiateResult.Success -> result.negotiation
            is SyncNegotiateResult.Failure -> return@withContext SaveSyncOutcome.Failure(result.error, result.httpCode)
        }

        val operation = negotiation.operations.firstOrNull { op ->
            op.romId == request.romId && (op.slot == null || op.slot == request.slot)
        } ?: return@withContext SaveSyncOutcome.Failure(RommApiError.PARSE_ERROR)

        when (operation.action) {
            SyncAction.NO_OP -> {
                saveReplicaDao.upsert(
                    mergeAgreedMetadata(existingReplica, request, serverKey, userKey, operation, localBytes)
                )
                completeSession(origin, negotiation.sessionId, completed = 1, failed = 0)
                SaveSyncOutcome.NoOpSynced(negotiation.sessionId)
            }

            SyncAction.DOWNLOAD -> handleDownload(request, serverKey, userKey, origin, deviceId, negotiation, operation, existingReplica)

            SyncAction.UPLOAD -> handleUploadQueue(request, serverKey, userKey, origin, negotiation, existingReplica, localBytes)

            SyncAction.CONFLICT -> {
                saveReplicaDao.upsert(
                    (existingReplica ?: newReplica(request, serverKey, userKey)).copy(
                        syncStatus = SaveSyncStatus.CONFLICT,
                        lastError = operation.reason,
                    )
                )
                completeSession(origin, negotiation.sessionId, completed = 0, failed = 1)
                SaveSyncOutcome.ConflictRequiresResolution(negotiation.sessionId, operation)
            }
        }
    }

    private suspend fun handleDownload(
        request: SaveSyncRequest,
        serverKey: String,
        userKey: String,
        origin: String,
        deviceId: String,
        negotiation: SyncNegotiateInfo,
        operation: SyncOperation,
        existingReplica: SaveReplicaEntity?,
    ): SaveSyncOutcome {
        val saveId = operation.saveId ?: return SaveSyncOutcome.Failure(RommApiError.PARSE_ERROR)

        val bytes = when (val result = RommSyncApi.downloadSaveContent(client, origin, saveId, deviceId, negotiation.sessionId)) {
            is SaveDownloadResult.Success -> result.bytes
            is SaveDownloadResult.Failure -> return SaveSyncOutcome.Failure(result.error, result.httpCode)
        }

        // Section 11.1: verify ROM/core provenance and the exact expected SRAM size before ever
        // adopting a downloaded save. Missing/mismatched emulator metadata is treated as an
        // unknown-provenance "legacy save" — quarantined, never adopted, never /downloaded-confirmed.
        val provenanceKnown = operation.emulator != null && operation.emulator == request.coreId
        val sizeMatches = bytes.size.toLong() == request.expectedSramSizeBytes
        if (!provenanceKnown || !sizeMatches) {
            val reason = if (!sizeMatches) "size-mismatch" else "unknown-provenance"
            val quarantinedPath = saveContentStore.quarantine(
                serverKey, userKey, request.romId, request.romHash, request.slot, bytes, reason, clock(),
            )
            saveReplicaDao.upsert(
                (existingReplica ?: newReplica(request, serverKey, userKey)).copy(
                    syncStatus = SaveSyncStatus.QUARANTINED,
                    lastError = "quarantined: $reason",
                )
            )
            completeSession(origin, negotiation.sessionId, completed = 0, failed = 1)
            return SaveSyncOutcome.Quarantined(reason, quarantinedPath)
        }

        saveContentStore.writeLocalAtomically(serverKey, userKey, request.romId, request.romHash, request.slot, bytes)
        val now = clock()
        saveReplicaDao.upsert(
            (existingReplica ?: newReplica(request, serverKey, userKey)).copy(
                localHash = sha256Hex(bytes),
                localSizeBytes = bytes.size.toLong(),
                localWrittenAtEpochMs = now,
                rommSaveId = saveId,
                serverHash = operation.serverContentHash,
                serverSizeBytes = bytes.size.toLong(),
                serverUpdatedAtEpochMs = operation.serverUpdatedAt?.toEpochMilli(),
                syncStatus = SaveSyncStatus.SYNCED,
                lastError = null,
            )
        )

        val confirmed = RommSyncApi.confirmDownload(client, origin, saveId, deviceId) is SaveConfirmResult.Success
        completeSession(origin, negotiation.sessionId, completed = 1, failed = 0)
        return SaveSyncOutcome.Downloaded(negotiation.sessionId, saveId, bytes.size.toLong(), confirmed)
    }

    private suspend fun handleUploadQueue(
        request: SaveSyncRequest,
        serverKey: String,
        userKey: String,
        origin: String,
        negotiation: SyncNegotiateInfo,
        existingReplica: SaveReplicaEntity?,
        localBytes: ByteArray?,
    ): SaveSyncOutcome {
        val generation = existingReplica?.localWrittenAtEpochMs
        if (localBytes == null || generation == null) {
            // The server negotiated "upload" but this device has no local save on record at all —
            // nothing durable to queue. A real mismatch worth surfacing rather than silently queuing.
            return SaveSyncOutcome.Failure(RommApiError.PARSE_ERROR)
        }

        // Idempotent resume (section 11.4 dedupe rule): drop any queued operation for an older
        // generation of this scope, then reuse an already-queued one for the current generation
        // rather than inserting a duplicate.
        pendingOperationDao.deleteStaleForScope(
            serverKey, userKey, request.romId, request.romHash, request.slot,
            PendingOperationType.UPLOAD, olderThanLocalGenerationEpochMs = generation,
        )
        val active = pendingOperationDao.findActiveByScope(
            serverKey, userKey, request.romId, request.romHash, request.slot, PendingOperationType.UPLOAD,
        )
        val pendingId = active.firstOrNull { it.localGenerationEpochMs == generation }?.id ?: run {
            val now = clock()
            pendingOperationDao.insert(
                PendingOperationEntity(
                    serverKey = serverKey,
                    userKey = userKey,
                    romId = request.romId,
                    romHash = request.romHash,
                    slot = request.slot,
                    operationType = PendingOperationType.UPLOAD,
                    localGenerationEpochMs = generation,
                    status = PendingOperationStatus.PENDING,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                )
            )
        }

        saveReplicaDao.upsert(existingReplica.copy(syncStatus = SaveSyncStatus.PENDING_UPLOAD, lastError = null))
        completeSession(origin, negotiation.sessionId, completed = 1, failed = 0)
        return SaveSyncOutcome.UploadQueued(negotiation.sessionId, pendingId)
    }

    private fun mergeAgreedMetadata(
        existingReplica: SaveReplicaEntity?,
        request: SaveSyncRequest,
        serverKey: String,
        userKey: String,
        operation: SyncOperation,
        localBytes: ByteArray?,
    ): SaveReplicaEntity {
        val base = existingReplica ?: newReplica(request, serverKey, userKey)
        return base.copy(
            localHash = existingReplica?.localHash ?: localBytes?.let(::sha256Hex),
            localSizeBytes = existingReplica?.localSizeBytes ?: localBytes?.size?.toLong(),
            rommSaveId = operation.saveId ?: base.rommSaveId,
            serverHash = operation.serverContentHash ?: base.serverHash,
            serverUpdatedAtEpochMs = operation.serverUpdatedAt?.toEpochMilli() ?: base.serverUpdatedAtEpochMs,
            syncStatus = SaveSyncStatus.SYNCED,
            lastError = null,
        )
    }

    private fun newReplica(request: SaveSyncRequest, serverKey: String, userKey: String) = SaveReplicaEntity(
        serverKey = serverKey,
        userKey = userKey,
        romId = request.romId,
        romHash = request.romHash,
        slot = request.slot,
        coreId = request.coreId,
        coreBuildRevision = request.coreBuildRevision,
        expectedSramSizeBytes = request.expectedSramSizeBytes,
    )

    private suspend fun completeSession(origin: String, sessionId: Long, completed: Int, failed: Int) {
        // Best-effort: an already-applied local outcome (adopted download, queued upload, recorded
        // conflict/quarantine) is not rolled back if this call fails — it only affects the server's
        // own bookkeeping of the sync session, not local save-data safety.
        RommSyncApi.completeSyncSession(client, origin, sessionId, SyncCompleteRequest(completed, failed))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

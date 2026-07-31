package com.romm.androidtv.romm.save

import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.emulation.model.sha256Hex
import com.romm.androidtv.network.extractServerKey
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
    /** Conflict resolver; defaults to production impl sharing the same DAOs. Tests inject a fake. */
    private val conflictResolver: ConflictResolver? = null,
    /**
     * Invoked every time a [PendingOperationEntity] (upload or negotiate-and-sync) is durably
     * queued, so the caller can schedule the [com.romm.androidtv.sync.SaveUploadWorker] batch via
     * [com.romm.androidtv.sync.SaveUploadEnqueueHelper]. Queuing a row in Room alone never causes
     * it to be uploaded — something must call this to actually schedule the WorkManager job.
     * Defaults to a no-op so plain-JVM unit tests never need an Android [android.content.Context].
     */
    private val onOperationQueued: () -> Unit = {},
) : SaveSyncCoordinator, SaveSyncCoordinatorInternal {

    /** Lazy singleton: resolves once on first conflict, reuses across calls. */
    private val resolver: ConflictResolver by lazy {
        conflictResolver ?: ConflictResolverImpl(client, deviceRepository, saveReplicaDao, saveContentStore, clock)
    }

    override suspend fun syncBeforeLaunch(request: SaveSyncRequest): SaveSyncOutcome = withContext(Dispatchers.IO) {
        val session = sessionStore.current() ?: return@withContext SaveSyncOutcome.Failure(RommApiError.AUTH_EXPIRED)
        val username = session.username ?: return@withContext SaveSyncOutcome.Failure(RommApiError.AUTH_EXPIRED)
        val origin = session.origin
        val serverKey = extractServerKey(origin)
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
        } ?: run {
            // A missing operation entry is NOT a parse failure: the server omits an operation
            // entirely when there is nothing to reconcile for this rom/slot (e.g. the very first
            // launch of a title with no local save and no save recorded on the server yet).
            // Nothing was assigned to complete, so report zero completed/failed to the server.
            completeSession(origin, negotiation.sessionId, completed = 0, failed = 0)
            return@withContext SaveSyncOutcome.NoOpSynced(negotiation.sessionId)
        }

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

    override suspend fun syncPostPlay(request: PostPlayCheckpointRequest): PostPlayCheckpointResult =
        withContext(Dispatchers.IO) {
            val existingReplica = saveReplicaDao.findByScope(
                request.serverKey, request.userKey, request.romId, request.romHash, request.slot,
            )

            // Unchanged bytes: localHash matches checkpointed hash — no new generation, no work.
            if (existingReplica?.localHash == request.checkpointedHash) {
                return@withContext PostPlayCheckpointResult.Unchanged
            }

            val now = clock()

            // Persist honest SaveReplicaEntity with new generation.
            val updatedReplica = (existingReplica ?: SaveReplicaEntity(
                serverKey = request.serverKey,
                userKey = request.userKey,
                romId = request.romId,
                romHash = request.romHash,
                slot = request.slot,
                coreId = request.coreId,
                coreBuildRevision = request.coreBuildRevision,
            )).copy(
                localHash = request.checkpointedHash,
                localSizeBytes = request.checkpointedSizeBytes,
                localWrittenAtEpochMs = now,
                syncStatus = SaveSyncStatus.UNSYNCED,
                lastError = null,
            )
            saveReplicaDao.upsert(updatedReplica)

            // Durably enqueue NEGOTIATE_AND_SYNC operation (idempotent dedupe: drop stale, reuse current).
            pendingOperationDao.deleteStaleForScope(
                request.serverKey, request.userKey, request.romId, request.romHash, request.slot,
                PendingOperationType.NEGOTIATE_AND_SYNC, olderThanLocalGenerationEpochMs = now,
            )
            val active = pendingOperationDao.findActiveByScope(
                request.serverKey, request.userKey, request.romId, request.romHash, request.slot,
                PendingOperationType.NEGOTIATE_AND_SYNC,
            )
            val pendingId = active.firstOrNull { it.localGenerationEpochMs == now }?.id ?: run {
                pendingOperationDao.insert(
                    PendingOperationEntity(
                        serverKey = request.serverKey,
                        userKey = request.userKey,
                        romId = request.romId,
                        romHash = request.romHash,
                        slot = request.slot,
                        operationType = PendingOperationType.NEGOTIATE_AND_SYNC,
                        localGenerationEpochMs = now,
                        status = PendingOperationStatus.PENDING,
                        origin = null, // Resolved at executor time from session store.
                        negotiateFileName = request.fileName,
                        negotiateCoreId = request.coreId,
                        negotiateCoreBuildRevision = request.coreBuildRevision,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                    )
                )
            }

            onOperationQueued()
            PostPlayCheckpointResult.Queued(pendingId)
        }

    override suspend fun finalizeAdoption(request: FinalizeAdoptionRequest): FinalizeAdoptionResult =
        withContext(Dispatchers.IO) {
            val session = sessionStore.current() ?: return@withContext FinalizeAdoptionResult.Failure(RommApiError.AUTH_EXPIRED)
            val username = session.username ?: return@withContext FinalizeAdoptionResult.Failure(RommApiError.AUTH_EXPIRED)
            val origin = session.origin

            val registration = deviceRepository.ensureRegistered(origin, username)
            val deviceId = when (registration) {
                is DeviceRegistrationResult.Success -> registration.identity.rommDeviceId
                is DeviceRegistrationResult.Failure ->
                    return@withContext FinalizeAdoptionResult.Failure(registration.error, registration.httpCode)
            }

            // Idempotent confirm: re-confirming an already-confirmed download is a no-op on the server.
            val confirmed = RommSyncApi.confirmDownload(client, origin, request.rommSaveId, deviceId) is SaveConfirmResult.Success

            // Complete the sync session (idempotent: server ignores duplicate completions).
            completeSession(origin, request.sessionId, completed = 1, failed = 0)

            // Persist the adopted replica honestly with the checkpoint hash from EmulationActivity.
            val existingReplica = saveReplicaDao.findByScope(
                request.serverKey, request.userKey, request.romId, request.romHash, request.slot,
            )
            val now = clock()
            saveReplicaDao.upsert(
                (existingReplica ?: SaveReplicaEntity(
                    serverKey = request.serverKey,
                    userKey = request.userKey,
                    romId = request.romId,
                    romHash = request.romHash,
                    slot = request.slot,
                    coreId = request.coreId,
                    coreBuildRevision = request.coreBuildRevision,
                )).copy(
                    localHash = request.checkpointedHash,
                    localSizeBytes = request.checkpointedSizeBytes,
                    localWrittenAtEpochMs = now,
                    rommSaveId = request.rommSaveId,
                    serverHash = request.serverContentHash,
                    syncStatus = SaveSyncStatus.SYNCED,
                    lastError = null,
                    expectedSramSizeBytes = request.expectedSramSizeBytes ?: existingReplica?.expectedSramSizeBytes,
                )
            )

            FinalizeAdoptionResult.Success(confirmed)
        }

    override suspend fun recordPlaySession(request: PlaySessionRecordRequest): PlaySessionRecordResult =
        withContext(Dispatchers.IO) {
            // Backend requires end_time > start_time; a session with no measurable duration has
            // nothing to report and would otherwise fail server-side validation.
            if (request.endEpochMs <= request.startEpochMs) {
                return@withContext PlaySessionRecordResult.Success(createdCount = 0, skippedCount = 0)
            }

            val session = sessionStore.current() ?: return@withContext PlaySessionRecordResult.Failure(RommApiError.AUTH_EXPIRED)
            val username = session.username ?: return@withContext PlaySessionRecordResult.Failure(RommApiError.AUTH_EXPIRED)
            val origin = session.origin

            val registration = deviceRepository.ensureRegistered(origin, username)
            val deviceId = when (registration) {
                is DeviceRegistrationResult.Success -> registration.identity.rommDeviceId
                is DeviceRegistrationResult.Failure ->
                    return@withContext PlaySessionRecordResult.Failure(registration.error, registration.httpCode)
            }

            when (
                val result = RommSyncApi.ingestPlaySessions(
                    client,
                    origin,
                    com.romm.androidtv.romm.PlaySessionIngestRequest(
                        deviceId = deviceId,
                        sessions = listOf(
                            com.romm.androidtv.romm.PlaySessionEntry(
                                romId = request.romId,
                                saveSlot = request.slot,
                                startTime = Instant.ofEpochMilli(request.startEpochMs),
                                endTime = Instant.ofEpochMilli(request.endEpochMs),
                                durationMs = request.endEpochMs - request.startEpochMs,
                            )
                        ),
                    ),
                )
            ) {
                is com.romm.androidtv.romm.PlaySessionIngestResult.Success ->
                    PlaySessionRecordResult.Success(result.createdCount, result.skippedCount)
                is com.romm.androidtv.romm.PlaySessionIngestResult.Failure ->
                    PlaySessionRecordResult.Failure(result.error, result.httpCode)
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

        // Section 11.1: verify ROM/core provenance before any adoption. Missing/mismatched emulator
        // metadata is always quarantined permanently, regardless of whether size is known.
        val provenanceKnown = operation.emulator != null && operation.emulator == request.coreId
        if (!provenanceKnown) {
            val quarantinedPath = saveContentStore.quarantine(
                serverKey, userKey, request.romId, request.romHash, request.slot, bytes, "unknown-provenance", clock(),
            )
            saveReplicaDao.upsert(
                (existingReplica ?: newReplica(request, serverKey, userKey)).copy(
                    syncStatus = SaveSyncStatus.QUARANTINED,
                    lastError = "quarantined: unknown-provenance",
                )
            )
            completeSession(origin, negotiation.sessionId, completed = 0, failed = 1)
            return SaveSyncOutcome.Quarantined("unknown-provenance", quarantinedPath)
        }

        // Known trusted size: exact-size gate before adoption.
        if (request.expectedSramSizeBytes != null) {
            if (bytes.size.toLong() != request.expectedSramSizeBytes) {
                val quarantinedPath = saveContentStore.quarantine(
                    serverKey, userKey, request.romId, request.romHash, request.slot, bytes, "size-mismatch", clock(),
                )
                saveReplicaDao.upsert(
                    (existingReplica ?: newReplica(request, serverKey, userKey)).copy(
                        syncStatus = SaveSyncStatus.QUARANTINED,
                        lastError = "quarantined: size-mismatch",
                    )
                )
                completeSession(origin, negotiation.sessionId, completed = 0, failed = 1)
                return SaveSyncOutcome.Quarantined("size-mismatch", quarantinedPath)
            }
            // Trusted known size matches — adopt, confirm, complete.
            return adoptDownload(serverKey, userKey, origin, deviceId, negotiation, operation, existingReplica, request, bytes, saveId)
        }

        // Unknown size: provenance validated, download to durable quarantine, do NOT adopt or confirm.
        // Later orchestration (post-load JNI size query) will decide whether to adopt.
        val quarantinedPath = saveContentStore.quarantine(
            serverKey, userKey, request.romId, request.romHash, request.slot, bytes, "awaiting-core-validation", clock(),
        )
        saveReplicaDao.upsert(
            (existingReplica ?: newReplica(request, serverKey, userKey)).copy(
                rommSaveId = saveId,
                serverHash = operation.serverContentHash,
                serverSizeBytes = bytes.size.toLong(),
                serverUpdatedAtEpochMs = operation.serverUpdatedAt?.toEpochMilli(),
                syncStatus = SaveSyncStatus.AWAITING_CORE_VALIDATION,
                lastError = null,
            )
        )
        // Do NOT complete the session here — later orchestration completes it after core validation.
        return SaveSyncOutcome.AwaitingCoreValidation(
            sessionId = negotiation.sessionId,
            rommSaveId = saveId,
            quarantinedPath = quarantinedPath,
            downloadedSizeBytes = bytes.size.toLong(),
            serverContentHash = operation.serverContentHash,
            emulator = operation.emulator,
        )
    }

    private suspend fun adoptDownload(
        serverKey: String,
        userKey: String,
        origin: String,
        deviceId: String,
        negotiation: SyncNegotiateInfo,
        operation: SyncOperation,
        existingReplica: SaveReplicaEntity?,
        request: SaveSyncRequest,
        bytes: ByteArray,
        saveId: Long,
    ): SaveSyncOutcome {
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
        onOperationQueued()
        return SaveSyncOutcome.UploadQueued(negotiation.sessionId, pendingId)
    }

    // ---- SaveSyncCoordinatorInternal implementation (DAO access facade) ----

    override suspend fun findReplicaByScope(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
    ): SaveReplicaEntity? = saveReplicaDao.findByScope(serverKey, userKey, romId, romHash, slot)

    override suspend fun resolveConflict(request: ResolveConflictRequest): ConflictResolutionResult {
        // Use the original domain SyncOperation when available (preserves full serverContentHash
        // and serverUpdatedAt end-to-end). Fall back to reconstructed operation for backward compat.
        val operation = request.operation ?: SyncOperation(
            action = SyncAction.CONFLICT,
            romId = request.romId,
            saveId = request.serverSaveId,
            fileName = request.fileName ?: "autosave.srm",
            slot = request.serverSlot ?: request.slot,
            emulator = request.serverEmulator,
            reason = request.reason,
            serverUpdatedAt = null,
            serverContentHash = null,
        )

        val localEntity = saveReplicaDao.findByScope(
            extractServerKey(request.serverOrigin),
            request.username,
            request.romId,
            request.romHash,
            request.slot,
        ) ?: return ConflictResolutionResult.Failure(
            RommApiError.PARSE_ERROR,
            reason = "no-local-replica: cannot resolve conflict without local save replica",
        )

        return when (request.choice) {
            ConflictChoice.KEEP_LOCAL -> resolver.resolveKeepLocal(
                sessionId = request.sessionId,
                serverOrigin = request.serverOrigin,
                username = request.username,
                localEntity = localEntity,
                operation = operation,
                localFileName = request.fileName ?: "autosave.srm",
            )
            ConflictChoice.KEEP_SERVER -> resolver.resolveKeepServer(
                sessionId = request.sessionId,
                serverOrigin = request.serverOrigin,
                username = request.username,
                localEntity = localEntity,
                operation = operation,
            )
        }
    }

    // ---- Private helpers ----

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
        // own bookkeeping of the sync session, not local save-data safety. Log explicitly.
        when (val result = RommSyncApi.completeSyncSession(client, origin, sessionId, SyncCompleteRequest(completed, failed))) {
            is com.romm.androidtv.romm.SyncCompleteResult.Success -> Unit
            is com.romm.androidtv.romm.SyncCompleteResult.Failure ->
                Log.warning("completeSyncSession failed (non-fatal, local data safe): session=$sessionId error=${result.error} httpCode=${result.httpCode}")
        }
    }

    private companion object {
        // JVM-compatible logger: java.util.logging works in both Android and JVM unit tests.
        val Log = java.util.logging.Logger.getLogger("SaveSyncCoordinator")
    }
}

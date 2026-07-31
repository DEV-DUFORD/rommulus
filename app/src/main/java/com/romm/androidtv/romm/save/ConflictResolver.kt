package com.romm.androidtv.romm.save

import com.romm.androidtv.emulation.model.sha256Hex
import com.romm.androidtv.romm.RommApiError
import com.romm.androidtv.romm.RommSyncApi
import com.romm.androidtv.romm.SaveDownloadResult
import com.romm.androidtv.romm.SaveUploadResult
import com.romm.androidtv.romm.SyncAction
import com.romm.androidtv.romm.SyncCompleteRequest
import com.romm.androidtv.romm.SyncOperation
import okhttp3.OkHttpClient

/**
 * Domain-level resolver for a genuine RomM save conflict (section 11.3).
 *
 * This interface is the **concrete explicit conflict resolver**: it performs
 * all filesystem, network, and Room operations required to honor a user's
 * explicit choice (keep-local or keep-server) for a single conflict session.
 *
 * **Contract** (enforced by [ConflictResolverImpl]):
 * - Input validation is strict: [sessionId] > 0, [operation].action must be
 *   [SyncAction.CONFLICT], [operation].saveId must be present, provenance
 *   (coreId) must be compatible. Incompatible/unknown provenance is rejected
 *   and remains quarantine-UI only.
 * - No timestamps are used for decision-making. No automatic choice.
 * - The losing copy is durably backed up before any overwrite. Backup paths
 *   are deterministic (keyed by session + choice + content hash) so retries
 *   are idempotent across crash boundaries.
 * - Status is persisted **only after** all operations succeed. Any failure
 *   (network, backup, adoption) preserves both copies and returns explicit
 *   [ConflictResolutionResult.Failure].
 * - Cancel/dismiss is handled outside this resolver (non-mutating, per the
 *   [com.romm.androidtv.library.ui.ConflictPresentationAction] contract).
 *
 * KEEP LOCAL flow:
 * 1. Authenticate/register device (via [DeviceRepository]).
 * 2. Read local durable bytes from [SaveContentStore].
 * 3. Fetch losing server copy via `RommSyncApi.downloadSaveContentBackup`
 *    (optimistic=false, no session count).
 * 4. Validate available server hash/provenance/known size without fabrication.
 * 5. Durably backup server bytes under unique conflict-specific path.
 * 6. Upload local bytes with overwrite=true and original conflict sessionId.
 * 7. Persist status only after success.
 * 8. Complete exact session counters.
 *
 * KEEP SERVER flow:
 * 1. Authenticate/register device.
 * 2. Session-aware download of server bytes.
 * 3. Validate hash/provenance/exact known SRAM size.
 * 4. Durably backup current local bytes before atomic replacement.
 * 5. Adopt server bytes atomically.
 * 6. Persist replica metadata.
 * 7. Confirm download and complete session.
 */
interface ConflictResolver {
    /**
     * Resolves a conflict by keeping the local copy and overwriting the server.
     *
     * @param sessionId  The sync negotiation session ID from [SaveSyncOutcome.ConflictRequiresResolution].
     * @param serverOrigin The RomM server origin URL.
     * @param username The authenticated username (used for device registration scope).
     * @param localEntity The caller-supplied local [SaveReplicaEntity] (must have matching scope).
     * @param operation The [SyncOperation] with action == [SyncAction.CONFLICT].
     * @param localFileName The display file name for the upload (e.g. "autosave.srm").
     */
    suspend fun resolveKeepLocal(
        sessionId: Long,
        serverOrigin: String,
        username: String,
        localEntity: SaveReplicaEntity,
        operation: SyncOperation,
        localFileName: String,
    ): ConflictResolutionResult

    /**
     * Resolves a conflict by keeping the server copy and overwriting the local.
     *
     * @param sessionId  The sync negotiation session ID from [SaveSyncOutcome.ConflictRequiresResolution].
     * @param serverOrigin The RomM server origin URL.
     * @param username The authenticated username (used for device registration scope).
     * @param localEntity The caller-supplied local [SaveReplicaEntity] (must have matching scope).
     * @param operation The [SyncOperation] with action == [SyncAction.CONFLICT].
     */
    suspend fun resolveKeepServer(
        sessionId: Long,
        serverOrigin: String,
        username: String,
        localEntity: SaveReplicaEntity,
        operation: SyncOperation,
    ): ConflictResolutionResult
}

/**
 * Result of a conflict resolution attempt.
 */
sealed interface ConflictResolutionResult {
    data class Success(
        val choice: ConflictChoice,
        val serverBackupPath: String?,
        val localBackupPath: String?,
        val newServerSaveInfo: com.romm.androidtv.romm.ServerSaveInfo?,
    ) : ConflictResolutionResult

    data class Failure(
        val error: RommApiError,
        val httpCode: Int? = null,
        val reason: String = "",
    ) : ConflictResolutionResult
}

enum class ConflictChoice {
    KEEP_LOCAL,
    KEEP_SERVER,
}

/**
 * Production [ConflictResolver] implementation.
 *
 * Injected dependencies:
 * - [client]: OkHttp client for network calls.
 * - [deviceRepository]: For device registration/authentication.
 * - [saveReplicaDao]: For reading/writing replica metadata.
 * - [saveContentStore]: For reading/writing/backup of save bytes.
 * - [clock]: Injected clock for deterministic timestamps in metadata (not backup paths).
 */
class ConflictResolverImpl(
    private val client: OkHttpClient,
    private val deviceRepository: com.romm.androidtv.romm.DeviceRepository,
    private val saveReplicaDao: SaveReplicaDao,
    private val saveContentStore: SaveContentStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ConflictResolver {

    override suspend fun resolveKeepLocal(
        sessionId: Long,
        serverOrigin: String,
        username: String,
        localEntity: SaveReplicaEntity,
        operation: SyncOperation,
        localFileName: String,
    ): ConflictResolutionResult {
        // ---- Strict input validation ----
        val inputError = validateConflictInput(sessionId, operation, localEntity)
        if (inputError != null) return inputError

        val saveId = operation.saveId!! // validated non-null above

        // ---- Provenance compatibility check ----
        if (!isProvenanceCompatible(localEntity.coreId, operation.emulator)) {
            return ConflictResolutionResult.Failure(
                RommApiError.PARSE_ERROR,
                reason = "incompatible-provenance: local core '${localEntity.coreId}' vs server '${operation.emulator}' — quarantine UI only",
            )
        }

        // ---- Authenticate ----
        val deviceId = resolveDeviceId(serverOrigin, username) ?: return@resolveKeepLocal ConflictResolutionResult.Failure(
            RommApiError.AUTH_EXPIRED,
            reason = "device-registration-failed",
        )

        // ---- Read local durable bytes ----
        val localBytes = saveContentStore.readLocal(
            localEntity.serverKey, localEntity.userKey, localEntity.romId, localEntity.romHash, localEntity.slot,
        ) ?: return ConflictResolutionResult.Failure(
            RommApiError.PARSE_ERROR,
            reason = "no-local-bytes: cannot keep local without durable local save",
        )

        // ---- Fetch losing server copy (optimistic=false, no session count) ----
        val serverBytes = when (val dlResult = RommSyncApi.downloadSaveContentBackup(client, serverOrigin, saveId, deviceId)) {
            is SaveDownloadResult.Success -> dlResult.bytes
            is SaveDownloadResult.Failure -> return ConflictResolutionResult.Failure(dlResult.error, dlResult.httpCode, "server-download-failed")
        }

        // ---- Validate server hash/size (no fabrication) ----
        val actualServerHash = sha256Hex(serverBytes)
        if (operation.serverContentHash != null && operation.serverContentHash != actualServerHash) {
            return ConflictResolutionResult.Failure(
                RommApiError.PARSE_ERROR,
                reason = "server-hash-mismatch: expected '${operation.serverContentHash}', got '$actualServerHash'",
            )
        }

        // ---- Durably backup server bytes under unique conflict-specific path ----
        val serverBackupPath = saveContentStore.conflictBackup(
            serverKey = localEntity.serverKey,
            userKey = localEntity.userKey,
            romId = localEntity.romId,
            romHash = localEntity.romHash,
            slot = localEntity.slot,
            bytes = serverBytes,
            sessionId = sessionId,
            choice = "keep-local",
            contentHash = actualServerHash,
        )

        // ---- Upload local bytes with overwrite=true and original conflict sessionId ----
        val uploadResult = RommSyncApi.uploadSave(
            client,
            serverOrigin,
            com.romm.androidtv.romm.SaveUploadRequest(
                romId = localEntity.romId,
                slot = localEntity.slot,
                emulator = localEntity.coreId,
                deviceId = deviceId,
                sessionId = sessionId,
                overwrite = true,
                fileName = localFileName,
                bytes = localBytes,
                // Same autosave-slot autocleanup as the ordinary upload paths.
                autocleanup = true,
                autocleanupLimit = 5,
            ),
        )

        val newServerSaveInfo = when (uploadResult) {
            is SaveUploadResult.Success -> uploadResult.save
            is SaveUploadResult.Conflict -> {
                // Server still reports conflict after overwrite=true — unexpected, abort.
                // Both copies remain intact (server backed up, local untouched).
                return ConflictResolutionResult.Failure(
                    RommApiError.SERVER_ERROR,
                    uploadResult.httpCode,
                    "upload-still-conflict: server rejected overwrite",
                )
            }
            is SaveUploadResult.Failure -> {
                // Upload failed — server backup is preserved, local untouched.
                return ConflictResolutionResult.Failure(uploadResult.error, uploadResult.httpCode, "upload-failed")
            }
        }

        // ---- Persist status only after success ----
        val now = clock()
        saveReplicaDao.upsert(
            localEntity.copy(
                rommSaveId = newServerSaveInfo.saveId,
                serverHash = newServerSaveInfo.contentHash ?: actualServerHash,
                serverSizeBytes = newServerSaveInfo.fileSizeBytes,
                serverUpdatedAtEpochMs = newServerSaveInfo.updatedAt?.toEpochMilli(),
                localHash = sha256Hex(localBytes),
                localSizeBytes = localBytes.size.toLong(),
                localWrittenAtEpochMs = now,
                syncStatus = SaveSyncStatus.SYNCED,
                lastError = null,
            )
        )

        // ---- Complete exact session counters ----
        completeSession(client, serverOrigin, sessionId, completed = 1, failed = 0)

        return ConflictResolutionResult.Success(
            choice = ConflictChoice.KEEP_LOCAL,
            serverBackupPath = serverBackupPath,
            localBackupPath = null,
            newServerSaveInfo = newServerSaveInfo,
        )
    }

    override suspend fun resolveKeepServer(
        sessionId: Long,
        serverOrigin: String,
        username: String,
        localEntity: SaveReplicaEntity,
        operation: SyncOperation,
    ): ConflictResolutionResult {
        // ---- Strict input validation ----
        val inputError = validateConflictInput(sessionId, operation, localEntity)
        if (inputError != null) return inputError

        val saveId = operation.saveId!! // validated non-null above

        // ---- Provenance compatibility check ----
        if (!isProvenanceCompatible(localEntity.coreId, operation.emulator)) {
            return ConflictResolutionResult.Failure(
                RommApiError.PARSE_ERROR,
                reason = "incompatible-provenance: local core '${localEntity.coreId}' vs server '${operation.emulator}' — quarantine UI only",
            )
        }

        // ---- Authenticate ----
        val deviceId = resolveDeviceId(serverOrigin, username) ?: return ConflictResolutionResult.Failure(
            RommApiError.AUTH_EXPIRED,
            reason = "device-registration-failed",
        )

        // ---- Session-aware download of server bytes ----
        val serverBytes = when (val dlResult = RommSyncApi.downloadSaveContent(client, serverOrigin, saveId, deviceId, sessionId)) {
            is SaveDownloadResult.Success -> dlResult.bytes
            is SaveDownloadResult.Failure -> return ConflictResolutionResult.Failure(dlResult.error, dlResult.httpCode, "server-download-failed")
        }

        // ---- Validate hash/provenance/exact known SRAM size ----
        val actualServerHash = sha256Hex(serverBytes)
        if (operation.serverContentHash != null && operation.serverContentHash != actualServerHash) {
            return ConflictResolutionResult.Failure(
                RommApiError.PARSE_ERROR,
                reason = "server-hash-mismatch: expected '${operation.serverContentHash}', got '$actualServerHash'",
            )
        }

        // Exact known SRAM size validation (if the local entity knows its expected size).
        if (localEntity.expectedSramSizeBytes != null && serverBytes.size.toLong() != localEntity.expectedSramSizeBytes) {
            return ConflictResolutionResult.Failure(
                RommApiError.PARSE_ERROR,
                reason = "sram-size-mismatch: expected ${localEntity.expectedSramSizeBytes}, got ${serverBytes.size}",
            )
        }

        // ---- Durably backup current local bytes before atomic replacement ----
        val localBackupPath = saveContentStore.readLocal(
            localEntity.serverKey, localEntity.userKey, localEntity.romId, localEntity.romHash, localEntity.slot,
        )?.let { localBytes ->
            val localHash = sha256Hex(localBytes)
            saveContentStore.conflictBackup(
                serverKey = localEntity.serverKey,
                userKey = localEntity.userKey,
                romId = localEntity.romId,
                romHash = localEntity.romHash,
                slot = localEntity.slot,
                bytes = localBytes,
                sessionId = sessionId,
                choice = "keep-server",
                contentHash = localHash,
            )
        }

        // ---- Adopt server: atomic write ----
        saveContentStore.writeLocalAtomically(
            localEntity.serverKey, localEntity.userKey, localEntity.romId, localEntity.romHash, localEntity.slot,
            serverBytes,
        )

        // ---- Persist replica metadata ----
        val now = clock()
        saveReplicaDao.upsert(
            localEntity.copy(
                localHash = actualServerHash,
                localSizeBytes = serverBytes.size.toLong(),
                localWrittenAtEpochMs = now,
                rommSaveId = saveId,
                serverHash = operation.serverContentHash ?: actualServerHash,
                serverSizeBytes = serverBytes.size.toLong(),
                serverUpdatedAtEpochMs = operation.serverUpdatedAt?.toEpochMilli(),
                syncStatus = SaveSyncStatus.SYNCED,
                lastError = null,
            )
        )

        // ---- Confirm download (idempotent) ----
        RommSyncApi.confirmDownload(client, serverOrigin, saveId, deviceId)

        // ---- Complete session ----
        completeSession(client, serverOrigin, sessionId, completed = 1, failed = 0)

        return ConflictResolutionResult.Success(
            choice = ConflictChoice.KEEP_SERVER,
            serverBackupPath = null,
            localBackupPath = localBackupPath,
            newServerSaveInfo = null,
        )
    }

    // ---- Validation helpers ----

    private fun validateConflictInput(
        sessionId: Long,
        operation: SyncOperation,
        localEntity: SaveReplicaEntity,
    ): ConflictResolutionResult.Failure? {
        if (sessionId <= 0) {
            return ConflictResolutionResult.Failure(
                RommApiError.PARSE_ERROR,
                reason = "invalid-session: sessionId must be positive",
            )
        }
        if (operation.action != SyncAction.CONFLICT) {
            return ConflictResolutionResult.Failure(
                RommApiError.PARSE_ERROR,
                reason = "invalid-action: expected CONFLICT, got ${operation.action}",
            )
        }
        if (operation.saveId == null || operation.saveId <= 0) {
            return ConflictResolutionResult.Failure(
                RommApiError.PARSE_ERROR,
                reason = "missing-saveId: conflict operation must carry a valid saveId",
            )
        }
        // Scope consistency: operation romId must match entity romId.
        if (operation.romId != localEntity.romId) {
            return ConflictResolutionResult.Failure(
                RommApiError.PARSE_ERROR,
                reason = "scope-mismatch: operation romId ${operation.romId} != entity romId ${localEntity.romId}",
            )
        }
        return null
    }

    private fun isProvenanceCompatible(localCoreId: String, serverEmulator: String?): Boolean {
        if (serverEmulator == null) return false
        return localCoreId == serverEmulator
    }

    private suspend fun resolveDeviceId(serverOrigin: String, username: String): String? {
        val registration = deviceRepository.ensureRegistered(serverOrigin, username)
        return when (registration) {
            is com.romm.androidtv.romm.DeviceRegistrationResult.Success -> registration.identity.rommDeviceId
            is com.romm.androidtv.romm.DeviceRegistrationResult.Failure -> null
        }
    }

    private fun completeSession(
        client: OkHttpClient,
        origin: String,
        sessionId: Long,
        completed: Int,
        failed: Int,
    ) {
        // Best-effort: local outcome is already persisted; this only affects server bookkeeping.
        // Do NOT roll back already-safe data on failure — log explicitly for observability.
        when (val result = RommSyncApi.completeSyncSession(client, origin, sessionId, SyncCompleteRequest(completed, failed))) {
            is com.romm.androidtv.romm.SyncCompleteResult.Success -> Unit
            is com.romm.androidtv.romm.SyncCompleteResult.Failure ->
                // Non-fatal: local data is safe; server bookkeeping will be retried next sync.
                Log.warning("completeSyncSession failed (non-fatal, local data safe): session=$sessionId error=${result.error} httpCode=${result.httpCode}")
        }
    }

    private companion object {
        // JVM-compatible logger: java.util.logging works in both Android and JVM unit tests.
        val Log = java.util.logging.Logger.getLogger("ConflictResolver")
    }
}

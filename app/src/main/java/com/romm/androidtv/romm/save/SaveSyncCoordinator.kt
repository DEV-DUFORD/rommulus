package com.romm.androidtv.romm.save

import com.romm.androidtv.emulation.model.SavePathPolicy
import com.romm.androidtv.romm.RommApiError
import com.romm.androidtv.romm.SyncOperation

/**
 * Pre-launch save negotiation (LIBRETRO_REFACTOR.md section 11.3): decides,
 * for one server/user/ROM/slot scope, whether the local autosave and the
 * server's copy already agree, whether the server's copy should be
 * downloaded and adopted before launch, whether the local copy should be
 * queued for upload, or whether an explicit user conflict choice is needed.
 *
 * This interface exists (rather than a single top-level function) so it can
 * be faked in tests that exercise callers of it (Milestone 9,
 * `EmulationActivity`/`MainActivity` wiring) without a real network or
 * database.
 */
interface SaveSyncCoordinator {
    suspend fun syncBeforeLaunch(request: SaveSyncRequest): SaveSyncOutcome

    /**
     * Post-play checkpoint finalization (LIBRETRO_REFACTOR.md section 11.3 post-play).
     *
     * Called from the main process after EmulationActivity reports a successful atomic
     * canonical SRAM checkpoint (normal result or durable journal recovery). This method:
     * 1. Reads the checkpointed bytes and hashes them.
     * 2. Compares against the last durable [SaveReplicaEntity.localHash].
     * 3. If bytes changed: persists an honest SaveReplicaEntity with the new generation,
     *    durably enqueues a [PendingOperationType.NEGOTIATE_AND_SYNC] operation, and
     *    returns the queued operation ID.
     * 4. If bytes unchanged: returns null (no generation, no work).
     *
     * The WorkManager executor (Milestone 7) drains the NEGOTIATE_AND_SYNC queue,
     * authenticates, registers, negotiates a fresh session, and executes the server's
     * returned action. This call never touches the network — it only persists durable
     * state so the worker can replay after process death.
     *
     * Idempotent: calling twice for the same checkpoint hash produces the same outcome
     * (the second call sees unchanged bytes and returns null).
     */
    suspend fun syncPostPlay(request: PostPlayCheckpointRequest): PostPlayCheckpointResult

    /**
     * Finalizes a candidate adoption after EmulationActivity's post-load JNI size validation
     * succeeds (LIBRETRO_REFACTOR.md section 11.3, Phase B wiring). Called only from the main
     * process after the emulation process has durably checkpointed the adopted SRAM.
     *
     * This method:
     * 1. Confirms the download via `/downloaded` (idempotent).
     * 2. Completes the sync session [sessionId] with `completed=1`.
     * 3. Upserts the [SaveReplicaEntity] to SYNCED with the honest checkpoint hash/size.
     *
     * Idempotent: calling twice for the same sessionId that is already confirmed/SYNCED
     * is a no-op (re-confirms, re-upserts identical data).
     */
    suspend fun finalizeAdoption(request: FinalizeAdoptionRequest): FinalizeAdoptionResult
}

/**
 * Request to finalize adoption of a previously quarantined candidate save.
 */
data class FinalizeAdoptionRequest(
    val sessionId: Long,
    val rommSaveId: Long,
    val serverKey: String,
    val userKey: String,
    val romId: Long,
    val romHash: String,
    val slot: String,
    val coreId: String,
    val coreBuildRevision: String,
    /** SHA-256 hex of the checkpointed SRAM (honest hash from EmulationActivity). */
    val checkpointedHash: String,
    /** Exact byte-size of the checkpointed SRAM. */
    val checkpointedSizeBytes: Long,
    /** Server-reported content hash from the original negotiation. Null if not reported. */
    val serverContentHash: String?,
    /** JNI-learned expected SRAM size in bytes. Persisted for future sync decisions. Null if unknown. */
    val expectedSramSizeBytes: Long? = null,
)

/**
 * Result of [SaveSyncCoordinator.finalizeAdoption].
 */
sealed interface FinalizeAdoptionResult {
    data class Success(val confirmed: Boolean) : FinalizeAdoptionResult
    data class Failure(val error: RommApiError, val httpCode: Int? = null) : FinalizeAdoptionResult
}

data class SaveSyncRequest(
    val romId: Long,
    val romHash: String,
    val slot: String = SavePathPolicy.AUTOSAVE_SLOT,
    /** Sent as RomM's `emulator` field (a stable producer/core identifier) — section 11.1. */
    val coreId: String,
    val coreBuildRevision: String,
    /** Nullable: only set when a prior trusted replica or JNI query already knows the exact SRAM size. */
    val expectedSramSizeBytes: Long?,
    /** Display file name sent to the server on upload/negotiate (e.g. the ROM's own save file name). */
    val fileName: String,
)

sealed interface SaveSyncOutcome {
    /** Local and server already agree; nothing more to do before launch. */
    data class NoOpSynced(val sessionId: Long) : SaveSyncOutcome

    /**
     * The server's save was downloaded, verified (ROM/core provenance + exact expected SRAM size),
     * and atomically adopted as the new local autosave before launch.
     *
     * [confirmed] is false if `/downloaded` failed to reach the server after an otherwise-successful,
     * already-adopted download — the local copy is still valid and safe to launch with; only the
     * server's bookkeeping of "was this delivered" is stale. There is no queued retry for this yet
     * (a `PendingOperationType.DOWNLOAD_CONFIRMATION` would be needed) — a known gap, not silently
     * hidden by this field.
     */
    data class Downloaded(val sessionId: Long, val rommSaveId: Long, val sizeBytes: Long, val confirmed: Boolean) : SaveSyncOutcome

    /**
     * The local autosave is newer; a durable, idempotent upload has been queued
     * ([PendingOperationEntity], `status = PENDING`). The actual HTTP upload runs later, with
     * backoff, from Milestone 7's `CoroutineWorker` — this call never uploads inline.
     */
    data class UploadQueued(val sessionId: Long, val pendingOperationId: Long) : SaveSyncOutcome

    /**
     * The server negotiated a `conflict` outcome. Automatic replacement is stopped: nothing was
     * adopted, uploaded, or queued. [operation] carries the server's side of the conflict for the
     * conflict-resolution screen (Milestone 8) to render alongside the local record.
     */
    data class ConflictRequiresResolution(val sessionId: Long, val operation: SyncOperation) : SaveSyncOutcome

    /**
     * A downloaded save had unknown/incompatible provenance (an unrecognized/mismatched core id) or
     * didn't match [SaveSyncRequest.expectedSramSizeBytes] — preserved at [quarantinedPath] but never
     * adopted, and never `/downloaded`-confirmed (section 11.1).
     */
    data class Quarantined(val reason: String, val quarantinedPath: String) : SaveSyncOutcome

    /**
     * The server's save was downloaded and quarantined because [SaveSyncRequest.expectedSramSizeBytes]
     * was unknown at pre-launch time. Provenance (core id) and hash were validated, but exact size
     * cannot be confirmed until the emulation core loads the ROM. The candidate bytes are preserved
     * at [quarantinedPath]; the RomM save is [rommSaveId]. Later orchestration must:
     * (1) query JNI for the actual SRAM size, (2) compare against [downloadedSizeBytes],
     * (3) on exact match, restore candidate into core memory and atomically checkpoint,
     * (4) confirm the download via `/downloaded` and complete the sync session [sessionId].
     * A mismatch remains permanently quarantined.
     */
    data class AwaitingCoreValidation(
        val sessionId: Long,
        val rommSaveId: Long,
        val quarantinedPath: String,
        val downloadedSizeBytes: Long,
        val serverContentHash: String?,
        val emulator: String?,
    ) : SaveSyncOutcome

    /** Device registration, negotiate, download, or an underlying network/API call failed outright. */
    data class Failure(val error: RommApiError, val httpCode: Int? = null) : SaveSyncOutcome
}

/**
 * Request to finalize a post-play checkpoint (section 11.3 post-play).
 * Carries the scope, the checkpointed hash/size, and the core metadata
 * needed for the NEGOTIATE_AND_SYNC operation.
 */
data class PostPlayCheckpointRequest(
    val serverKey: String,
    val userKey: String,
    val romId: Long,
    val romHash: String,
    val slot: String,
    val coreId: String,
    val coreBuildRevision: String,
    /** Display file name for the server (e.g. the ROM's save file name). */
    val fileName: String,
    /** SHA-256 hex of the checkpointed SRAM (honest hash from EmulationActivity). */
    val checkpointedHash: String,
    /** Exact byte-size of the checkpointed SRAM. */
    val checkpointedSizeBytes: Long,
)

/**
 * Result of [SaveSyncCoordinator.syncPostPlay].
 */
sealed interface PostPlayCheckpointResult {
    /**
     * Checkpointed bytes differ from the last durable replica. A new generation was
     * persisted and a [PendingOperationType.NEGOTIATE_AND_SYNC] operation was durably
     * enqueued. [pendingOperationId] is the database row the WorkManager executor will drain.
     */
    data class Queued(val pendingOperationId: Long) : PostPlayCheckpointResult

    /**
     * Checkpointed bytes match the last durable replica (unchanged). No new generation,
     * no work enqueued. Safe to ignore.
     */
    data object Unchanged : PostPlayCheckpointResult

    /**
     * Could not read checkpointed bytes, or a database write failed.
     * [error] is a [RommApiError] classification; the caller should surface this.
     */
    data class Failure(val error: RommApiError) : PostPlayCheckpointResult
}

/**
 * Request to resolve a save conflict via an explicit user choice (keep local or keep server).
 * All fields are authoritative: derived from the original [SaveSyncOutcome.ConflictRequiresResolution]
 * and the caller's session state. The coordinator owns all DAO/store access internally.
 */
data class ResolveConflictRequest(
    val sessionId: Long,
    val serverOrigin: String,
    val username: String,
    val romId: Long,
    val romHash: String,
    val slot: String,
    /** The user's explicit choice from the conflict-resolution UI. */
    val choice: ConflictChoice,
    /**
     * The original domain [SyncOperation] from negotiation (action == CONFLICT).
     * Carries the full serverContentHash and serverUpdatedAt end-to-end.
     * When present, this is used directly; otherwise legacy fields are used for backward compat.
     */
    val operation: SyncOperation? = null,
    /** Server-reported save ID from the original negotiation operation. Null if not available. */
    val serverSaveId: Long?,
    /** Server-reported file name. Falls back to "autosave.srm" if null. */
    val fileName: String?,
    /** Server-reported slot. Falls back to [slot] if null. */
    val serverSlot: String?,
    /** Server-reported emulator/core id from the original negotiation operation. Null if not available. */
    val serverEmulator: String?,
    /** Server-reported conflict reason/description. */
    val reason: String,
)

/**
 * Reads a [SaveReplicaEntity] by scope without mutating anything. Exposed so callers
 * (e.g. the conflict-resolution overlay in MainActivity) can inspect local save metadata
 * without accessing DAOs directly. The coordinator keeps its DAO references private.
 */
suspend fun SaveSyncCoordinator.findSaveReplicaByScope(
    serverKey: String,
    userKey: String,
    romId: Long,
    romHash: String,
    slot: String,
): SaveReplicaEntity? = (this as SaveSyncCoordinatorInternal).findReplicaByScope(serverKey, userKey, romId, romHash, slot)

/** @hide Internal interface; public entry points are [findSaveReplicaByScope] and direct cast. */
internal interface SaveSyncCoordinatorInternal {
    suspend fun findReplicaByScope(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
    ): SaveReplicaEntity?

    /**
     * Resolves a conflict by delegating to the internal [ConflictResolver].
     * The coordinator owns all DAO/store access; callers supply only user-facing data.
     */
    suspend fun resolveConflict(request: ResolveConflictRequest): ConflictResolutionResult
}

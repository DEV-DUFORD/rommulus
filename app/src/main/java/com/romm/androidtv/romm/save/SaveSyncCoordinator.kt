package com.romm.androidtv.romm.save

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
     * After persisting durable state, production immediately drains the operation while the
     * app is still foregrounded. WorkManager remains the retry/process-death fallback.
     *
     * Idempotent: calling twice for the same checkpoint hash produces the same outcome
     * (the second call sees unchanged bytes and returns null).
     */
    suspend fun syncPostPlay(request: PostPlayCheckpointRequest): PostPlayCheckpointResult

    /**
     * Downloads and adopts one specific, user-chosen server save (the native save-picker
     * screen's "Choose Save" flow), bypassing the normal negotiate-driven [syncBeforeLaunch]
     * decision entirely — the user has already told the app which save they want, so there is
     * nothing left to negotiate. Unlike [syncBeforeLaunch]'s auto-sync download path, this does
     * NOT gate on emulator/core provenance: SRAM saves are cross-core compatible for the same
     * platform (a save produced by one core loads fine under a different, compatible core), so
     * a chosen save's reported `emulator` not matching [AdoptSaveRequest.coreId] is not treated
     * as a sign of an incompatible or untrustworthy save — the user explicitly picked it from
     * this ROM's own save list. Still runs an exact-size check when the expected SRAM size is
     * already known (a save that plainly can't fit the console's expected SRAM size is
     * quarantined, never silently adopted); an unknown size still defers final adoption to
     * post-load JNI validation exactly like the normal download path (returns
     * [SaveSyncOutcome.AwaitingCoreValidation]). Reuses [SaveSyncOutcome] rather than a new sealed
     * type since every existing branch (Downloaded/Quarantined/AwaitingCoreValidation/Failure)
     * already models exactly what this needs; the [SaveSyncOutcome.Downloaded]/
     * [SaveSyncOutcome.AwaitingCoreValidation] `sessionId` is `0L` here (there is no negotiate
     * sync-session backing an explicit adoption) — downstream `completeSession`/`finalizeAdoption`
     * calls already treat that server bookkeeping as best-effort/non-fatal.
     */
    suspend fun adoptChosenSave(request: AdoptSaveRequest): SaveSyncOutcome

    /**
     * Lists every server save for one ROM (native save-picker screen's "Choose Save" flow),
     * across all slots/devices — mirrors RomM's own web UI "All Saves" list rather than just
     * this device's own "autosave" slot history. Read-only: never mutates local state.
     */
    suspend fun listSavesForRom(romId: Long): com.romm.androidtv.romm.SaveListResult

    /**
     * Finalizes a candidate adoption after EmulationActivity's post-load JNI size validation
     * succeeds (LIBRETRO_REFACTOR.md section 11.3, Phase B wiring). Called only from the main
     * process after the emulation process has durably checkpointed the adopted SRAM.
     *
     * This method:
     * 1. Confirms the download via `/downloaded` (idempotent).
     * 2. Completes the sync session [sessionId] with `completed=1`.
     * 3. Upserts the [SaveReplicaEntity] with the adopted server hash as its comparison
     *    baseline. The caller then passes the post-game checkpoint to [syncPostPlay], which
     *    queues an upload when gameplay changed the adopted bytes.
     *
     * Idempotent: calling twice for the same sessionId that is already confirmed/SYNCED
     * is a no-op (re-confirms, re-upserts identical data).
     */
    suspend fun finalizeAdoption(request: FinalizeAdoptionRequest): FinalizeAdoptionResult

    /**
     * Reports a completed gameplay session so the server can advance `rom_user.last_played`
     * (drives the RomM Home screen's "Continue Playing" row). Best-effort: a [PlaySessionRecordResult.Failure]
     * must never block save-sync, gameplay, or journal cleanup — callers should log and move on.
     */
    suspend fun recordPlaySession(request: PlaySessionRecordRequest): PlaySessionRecordResult
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

// NOTE: SaveSyncRequest / AdoptSaveRequest / SaveSyncOutcome (pure types) now live in
// :shared:presentation at com.romm.androidtv.romm.save.SaveSyncTypes — same package, so no
// import changes are needed anywhere in this module.

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
     * enqueued. [pendingOperationId] is the database row drained immediately or by WorkManager.
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
 * Request to record a completed gameplay session for "Continue Playing" tracking.
 * Best-effort: has no bearing on save-sync correctness.
 */
data class PlaySessionRecordRequest(
    val romId: Long,
    val slot: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
)

/**
 * Result of [SaveSyncCoordinator.recordPlaySession].
 */
sealed interface PlaySessionRecordResult {
    data class Success(val createdCount: Int, val skippedCount: Int) : PlaySessionRecordResult
    data class Failure(val error: RommApiError, val httpCode: Int? = null) : PlaySessionRecordResult
}

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

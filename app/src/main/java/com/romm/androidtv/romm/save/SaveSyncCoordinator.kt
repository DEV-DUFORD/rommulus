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
}

data class SaveSyncRequest(
    val romId: Long,
    val romHash: String,
    val slot: String = SavePathPolicy.AUTOSAVE_SLOT,
    /** Sent as RomM's `emulator` field (a stable producer/core identifier) — section 11.1. */
    val coreId: String,
    val coreBuildRevision: String,
    val expectedSramSizeBytes: Long,
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

    /** Device registration, negotiate, download, or an underlying network/API call failed outright. */
    data class Failure(val error: RommApiError, val httpCode: Int? = null) : SaveSyncOutcome
}

package com.romm.androidtv.romm.save

import com.romm.androidtv.emulation.model.SavePathPolicy
import com.romm.androidtv.romm.RommApiError
import com.romm.androidtv.romm.SyncOperation

/**
 * Pure request/result types shared by [SaveSyncCoordinator] implementations and the
 * pre-launch preparation decision logic (`LaunchPreparationDecision` in
 * `:shared:presentation`). Kept in the original `com.romm.androidtv.romm.save` package so
 * existing callers need no import changes. No Room/Android dependencies — every field is a
 * primitive, a [RommApiError], or a [SyncOperation].
 */

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

/**
 * Request to download and adopt one specific, user-chosen server save — the native save-picker
 * screen's "Choose Save" flow. [chosenSaveId]/[chosenSaveEmulator]/[chosenSaveContentHash] come
 * straight from the `RommSyncApi.listSaves` row the user tapped (no extra lookup needed).
 */
data class AdoptSaveRequest(
    val romId: Long,
    val romHash: String,
    val slot: String = SavePathPolicy.AUTOSAVE_SLOT,
    val coreId: String,
    val coreBuildRevision: String,
    /** Nullable: only set when a prior trusted replica or JNI query already knows the exact SRAM size. */
    val expectedSramSizeBytes: Long?,
    val chosenSaveId: Long,
    /**
     * The chosen save's `emulator` field, as returned by the list — carried through for display
     * and journaling only; NOT gated against [coreId] (SRAM saves are cross-core compatible for
     * the same platform, so a mismatch here is not treated as untrustworthy).
     */
    val chosenSaveEmulator: String?,
    /**
     * Opaque RomM `content_hash` metadata. The pinned server's value is MD5-based (including a
     * special deterministic ZIP fingerprint), not a SHA-256 digest of the downloaded response.
     */
    val chosenSaveContentHash: String?,
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
     * (a pending-operation row with `status = PENDING`). The caller schedules its execution.
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
     * was unknown at pre-launch time. Provenance (core id) was validated, but exact size cannot be
     * confirmed until the emulation core loads the ROM. Server hash metadata is carried through
     * for journaling; it is not itself the SRAM compatibility gate. The candidate bytes are
     * preserved at [quarantinedPath]; the RomM save is [rommSaveId]. Later orchestration must:
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

    /**
     * Pre-launch sync failed with a transient network/server error (server unreachable or
     * unhealthy), but a valid durable local save exists on this device. The game may launch
     * offline using the local copy; the save remains queued (pending-operation row, retried
     * with backoff) and will reconcile with the server once connectivity returns.
     */
    data class PlayOfflineLocal(val error: RommApiError, val httpCode: Int? = null) : SaveSyncOutcome
}

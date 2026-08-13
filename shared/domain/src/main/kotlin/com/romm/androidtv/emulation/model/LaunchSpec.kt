package com.romm.androidtv.emulation.model

import com.romm.androidtv.config.PlaybackBackend
import java.util.UUID

/**
 * Immutable description of one launch request, handed from the main process
 * to `EmulationActivity` (LIBRETRO_REFACTOR.md section 6). Only validated
 * app-private paths and IDs travel here — never ROM bytes, and never a raw
 * server URL.
 *
 * This is a Phase 1 seam type: nothing constructs a [LaunchSpec] in this
 * build yet. Native launches remain disabled ([PlaybackBackend.NATIVE_LIBRETRO]
 * is never resolved by [com.romm.androidtv.config.PlaybackBackendPolicy]).
 */
data class LaunchSpec(
    /** RomM's canonical numeric ROM ID, as extracted by a strict URL parser. */
    val romId: Long,
    /** Verified content hash of the staged ROM file. Empty until the download pipeline exists. */
    val romHash: String,
    /** Absolute app-private path to the staged, validated ROM content. Null until staged. */
    val contentPath: String?,
    val coreId: String,
    val platformSlug: String = "",
    val backend: PlaybackBackend,
    /** Stable save slot name; only "autosave" is supported in the first release. */
    val saveSlot: String = "autosave",
    /**
     * Authoritative app launch session ID (UUID). This is the single source of truth
     * for correlating journal entries, candidate cache keys, EmulationActivity results,
     * and result handler processing. Distinct from RomM sync session IDs (Long), which
     * live in [com.romm.androidtv.emulation.model.CandidateSaveMetadata.rommSessionId].
     */
    val sessionId: UUID,
    /**
     * Authoritative server save file name from the staged RomM file metadata.
     * Distinct from [com.romm.androidtv.emulation.model.SavePathPolicy]'s local filename;
     * this is the exact `file_name` value from RomM's ROM file record, used for all
     * server-facing save operations (negotiate, upload, download). Never fabricated —
     * sourced only from [com.romm.androidtv.romm.RomRepositoryImpl].
     */
    val serverSaveFileName: String,
) {
    init {
        require(romId > 0) { "romId must be a positive RomM ROM ID" }
        require(coreId.isNotBlank()) { "coreId must not be blank" }
        require(serverSaveFileName.isNotBlank()) { "serverSaveFileName must not be blank" }
    }

    /** String representation of [sessionId], safe for Intent extras and journal keys. */
    val sessionIdString: String get() = sessionId.toString()
}

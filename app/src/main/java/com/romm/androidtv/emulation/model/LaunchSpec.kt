package com.romm.androidtv.emulation.model

import com.romm.androidtv.config.PlaybackBackend

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
    val backend: PlaybackBackend,
    /** Stable save slot name; only "autosave" is supported in the first release. */
    val saveSlot: String = "autosave",
    /** Opaque session identifier used to correlate the dirty marker and result descriptor. */
    val sessionId: String,
) {
    init {
        require(romId > 0) { "romId must be a positive RomM ROM ID" }
        require(coreId.isNotBlank()) { "coreId must not be blank" }
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
    }
}

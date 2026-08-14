package com.romm.androidtv.romm

/**
 * Outcome of staging firmware/BIOS files for a core (LIBRETRO_REFACTOR.md
 * section 10, "Firmware"). Moved from `:app` to `:shared:presentation` so the
 * BIOS configuration presenter can reference it platform-neutrally (Linux port
 * Phase 4); the package is unchanged so existing `com.romm.androidtv.romm`
 * imports keep resolving.
 */
sealed interface FirmwareStagingOutcome {
    /** Absolute paths for every requested file name, all hash-verified. */
    data class Success(val stagedPaths: Map<String, String>) : FirmwareStagingOutcome
    data class Missing(val fileNames: List<String>) : FirmwareStagingOutcome
    object AuthExpired : FirmwareStagingOutcome
    data class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long) : FirmwareStagingOutcome
    data class CorruptedDownload(val fileName: String, val reason: String) : FirmwareStagingOutcome
    data class NetworkError(val message: String) : FirmwareStagingOutcome
}

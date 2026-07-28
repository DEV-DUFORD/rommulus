package com.romm.androidtv.romm

/**
 * Discovers, validates, and stages required firmware/BIOS files into a core's
 * system directory (LIBRETRO_REFACTOR.md section 10, "Firmware"). No
 * implementation exists yet: this is a Phase 1 seam.
 *
 * Implementations must stage only verified files and report missing or
 * mismatched firmware before a core load is attempted; they must never guess
 * a BIOS identity based only on file extension.
 */
interface FirmwareRepository {
    /** Returns which of [requiredFileNames] are present and hash-verified in local storage. */
    suspend fun checkAvailability(requiredFileNames: List<String>): FirmwareAvailability
}

data class FirmwareAvailability(
    val present: List<String>,
    val missing: List<String>,
    val hashMismatches: List<String>,
) {
    val isReady: Boolean get() = missing.isEmpty() && hashMismatches.isEmpty()
}

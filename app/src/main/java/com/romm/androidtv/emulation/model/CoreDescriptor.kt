package com.romm.androidtv.emulation.model

/**
 * Per-core behavior declaration. Every core-specific rule must live here, not
 * scattered through UI or session code (LIBRETRO_REFACTOR.md section 5,
 * "Architectural rules").
 *
 * This is a Phase 1 seam: no [CoreDescriptor] is backed by a real native core
 * yet. It is deliberately narrower than [com.romm.androidtv.emulation.model.CoreLicenseFinding],
 * which records licensing/approval state; this type records the *runtime*
 * behavior a native host needs once a core is approved and built.
 */
data class CoreDescriptor(
    /** Must match a [CoreLicenseFinding.coreId] in [CoreManifest]. */
    val coreId: String,
    val displayName: String,
    val supportedSystems: List<String>,
    val supportedExtensions: List<String>,
    /** Firmware file names this core cannot run without. */
    val requiredFirmware: List<String> = emptyList(),
    /** Firmware file names this core can use if present but does not require. */
    val optionalFirmware: List<String> = emptyList(),
    /**
     * Mirrors `RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME` / `need_fullpath` (section 7.1):
     * whether the core expects a real filesystem path rather than in-memory content.
     */
    val needsFullPath: Boolean = false,
    /** Whether archive extraction must be skipped and the archive passed to the core directly. */
    val blockExtract: Boolean = false,
    /** Stable Libretro save-RAM identifier(s) this core exposes, if known ahead of load. */
    val memoryRegions: List<String> = listOf("RETRO_MEMORY_SAVE_RAM"),
) {
    init {
        require(coreId.isNotBlank()) { "coreId must not be blank" }
        require(supportedSystems.isNotEmpty()) { "supportedSystems must not be empty" }
    }
}

package com.romm.androidtv.emulation.model

const val MGBA_STABLE_SAVE_SIZE_BYTES = 128L * 1024L

private val MGBA_LEGACY_SAVE_SIZES = setOf(
    512L,
    8L * 1024L,
    32L * 1024L,
    64L * 1024L,
    MGBA_STABLE_SAVE_SIZE_BYTES,
)

/**
 * Validates save sizes without weakening the exact-size contract globally.
 *
 * mGBA historically reports the detected GBA save type (512 B through
 * 128 KiB), while the frontend now persists its stable 128 KiB backing
 * buffer. Both sides may therefore be a valid legacy GBA size during
 * migration. Other cores remain exact, apart from Genesis Plus GX's
 * existing variable-length cartridge-save contract.
 */
fun isCompatibleSaveSize(
    coreId: String,
    expectedSizeBytes: Long,
    actualSizeBytes: Long,
): Boolean {
    if (expectedSizeBytes == actualSizeBytes) return true

    if (coreId == "mgba") {
        return expectedSizeBytes in MGBA_LEGACY_SAVE_SIZES &&
            actualSizeBytes in MGBA_LEGACY_SAVE_SIZES
    }

    return coreId == "genesis_plus_gx" &&
        expectedSizeBytes == GENESIS_PLUS_GX_SRAM_SIZE_BYTES &&
        actualSizeBytes in 1..expectedSizeBytes
}

/** New mGBA observations are recorded against the engine's stable persistence contract. */
fun normalizeExpectedSaveSize(coreId: String, observedSizeBytes: Long?): Long? =
    if (coreId == "mgba" &&
        observedSizeBytes != null &&
        observedSizeBytes in MGBA_LEGACY_SAVE_SIZES
    ) {
        MGBA_STABLE_SAVE_SIZE_BYTES
    } else {
        observedSizeBytes
    }

private const val GENESIS_PLUS_GX_SRAM_SIZE_BYTES = 64L * 1024L

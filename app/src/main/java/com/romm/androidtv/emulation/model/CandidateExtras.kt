package com.romm.androidtv.emulation.model

/**
 * Intent extra keys for candidate save metadata (Phase B wiring).
 *
 * Platform-bound companion to [CandidateSaveMetadata] (which lives in `:shared:presentation`):
 * only the strict-extras marshalling here needs the platform Intent type, so it stays in the app.
 */
object CandidateExtras {
    const val ROMM_SESSION_ID = "com.romm.androidtv.emulation.candidate.ROMM_SESSION_ID"
    const val ROMM_SAVE_ID = "com.romm.androidtv.emulation.candidate.ROMM_SAVE_ID"
    const val CANDIDATE_PATH = "com.romm.androidtv.emulation.candidate.CANDIDATE_PATH"
    const val DOWNLOADED_SIZE_BYTES = "com.romm.androidtv.emulation.candidate.DOWNLOADED_SIZE_BYTES"
    const val SERVER_CONTENT_HASH = "com.romm.androidtv.emulation.candidate.SERVER_CONTENT_HASH"
    const val EMULATOR = "com.romm.androidtv.emulation.candidate.EMULATOR"
    const val ROM_ID = "com.romm.androidtv.emulation.candidate.ROM_ID"
    const val ROM_HASH = "com.romm.androidtv.emulation.candidate.ROM_HASH"
    const val CORE_ID = "com.romm.androidtv.emulation.candidate.CORE_ID"
    const val CORE_BUILD_REVISION = "com.romm.androidtv.emulation.candidate.CORE_BUILD_REVISION"

    /**
     * Extracts [CandidateSaveMetadata] from an Intent, or null if no candidate
     * extras are present (normal launch without a pending download).
     */
    fun extractFromIntent(intent: android.content.Intent): CandidateSaveMetadata? {
        // -1L (the getLongExtra default) means the extra is absent; `0L` is a valid,
        // deliberately-passed sentinel meaning "no real negotiate session backs this
        // candidate" (see rommSessionId's KDoc) and must NOT be treated as absent here.
        val rommSessionId = intent.getLongExtra(ROMM_SESSION_ID, -1L)
        if (rommSessionId < 0) return null
        val rommSaveId = intent.getLongExtra(ROMM_SAVE_ID, -1L)
        val candidatePath = intent.getStringExtra(CANDIDATE_PATH) ?: return null
        val downloadedSizeBytes = intent.getLongExtra(DOWNLOADED_SIZE_BYTES, -1L)
        val serverContentHash = intent.getStringExtra(SERVER_CONTENT_HASH)
        val emulator = intent.getStringExtra(EMULATOR)
        val romId = intent.getLongExtra(ROM_ID, -1L)
        if (romId <= 0) return null // Authoritative ROM ID required — reject incomplete metadata.
        val romHash = intent.getStringExtra(ROM_HASH) ?: return null
        val coreId = intent.getStringExtra(CORE_ID) ?: return null
        val coreBuildRevision = intent.getStringExtra(CORE_BUILD_REVISION) ?: return null
        return CandidateSaveMetadata(
            rommSessionId = rommSessionId,
            rommSaveId = rommSaveId,
            candidatePath = candidatePath,
            downloadedSizeBytes = downloadedSizeBytes,
            serverContentHash = serverContentHash,
            emulator = emulator,
            romId = romId,
            romHash = romHash,
            coreId = coreId,
            coreBuildRevision = coreBuildRevision,
        )
    }

    /**
     * Writes [metadata] into an Intent as extras. Idempotent: calling twice
     * overwrites with the same values.
     */
    fun putIntoIntent(intent: android.content.Intent, metadata: CandidateSaveMetadata) {
        intent.putExtra(ROMM_SESSION_ID, metadata.rommSessionId)
        intent.putExtra(ROMM_SAVE_ID, metadata.rommSaveId)
        intent.putExtra(CANDIDATE_PATH, metadata.candidatePath)
        intent.putExtra(DOWNLOADED_SIZE_BYTES, metadata.downloadedSizeBytes)
        intent.putExtra(SERVER_CONTENT_HASH, metadata.serverContentHash)
        intent.putExtra(EMULATOR, metadata.emulator)
        intent.putExtra(ROM_ID, metadata.romId)
        intent.putExtra(ROM_HASH, metadata.romHash)
        intent.putExtra(CORE_ID, metadata.coreId)
        intent.putExtra(CORE_BUILD_REVISION, metadata.coreBuildRevision)
    }
}

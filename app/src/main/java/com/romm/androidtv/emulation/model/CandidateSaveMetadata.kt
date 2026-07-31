package com.romm.androidtv.emulation.model

import java.io.File

/**
 * Small, immutable metadata describing a quarantined candidate SRAM that
 * [EmulationActivity] should validate and potentially adopt after core load.
 *
 * Carried from the main process to EmulationActivity via strict Intent extras.
 * Path validation rejects anything outside the app-private canonical save
 * directory tree (LIBRETRO_REFACTOR.md section 6: "strict app-private
 * canonical path validation").
 *
 * All fields are authoritative: romId/romHash/coreId/coreBuildRevision come
 * from the staged LaunchSpec and CoreManifest; no fabricated values are permitted.
 */
data class CandidateSaveMetadata(
    /**
     * RomM's sync session ID (Long). Distinct from the app launch session UUID in
     * LaunchSpec.sessionId. `0L` is a valid sentinel meaning "no real negotiate session backs
     * this candidate" — used by the native save-picker's explicit adoption flow
     * ([com.romm.androidtv.romm.save.SaveSyncCoordinator.adoptChosenSave]), which downloads a
     * user-chosen save directly rather than via a negotiated sync session. Real RomM session
     * IDs are always positive (server auto-increment starting at 1), so 0 never collides with
     * one. Downstream `completeSession`/`finalizeAdoption` calls already treat session
     * completion as best-effort/non-fatal, so a sentinel session id is safe there too.
     */
    val rommSessionId: Long,
    /** RomM's save ID for the downloaded content. */
    val rommSaveId: Long,
    /** App-private path to the quarantined candidate bytes. */
    val candidatePath: String,
    /** Exact byte-size of the downloaded candidate (for JNI size comparison). */
    val downloadedSizeBytes: Long,
    /** Server-reported content hash. Persisted in SessionDescriptor for ADOPTED recovery. Null if not reported. */
    val serverContentHash: String?,
    /** Server-reported emulator/core provenance. Null if not reported. */
    val emulator: String?,
    /** Exact ROM ID from the LaunchSpec. Required for post-adoption finalization identity. */
    val romId: Long,
    /** Verified content hash of the staged ROM file. Required for post-adoption finalization. */
    val romHash: String,
    /** Core ID from authoritative CoreManifest entry. Required for post-adoption finalization. */
    val coreId: String,
    /** Exact core build revision from authoritative CoreManifest entry. Required for post-adoption finalization. */
    val coreBuildRevision: String,
) {
    init {
        require(rommSessionId >= 0) { "rommSessionId must be non-negative" }
        require(rommSaveId > 0) { "rommSaveId must be positive" }
        require(downloadedSizeBytes > 0) { "downloadedSizeBytes must be positive" }
        require(candidatePath.isNotBlank()) { "candidatePath must not be blank" }
        require(romId > 0) { "romId must be positive" }
        require(romHash.isNotBlank()) { "romHash must not be blank" }
        require(coreId.isNotBlank()) { "coreId must not be blank" }
        require(coreBuildRevision.isNotBlank()) { "coreBuildRevision must not be blank" }
    }

    /**
     * Validates that [candidatePath] is within the given app-private base directory.
     * Rejects path traversal attempts (`..`, absolute paths outside the base).
     */
    fun validateAppPrivate(baseDir: File): Result<Unit> = runCatching {
        val candidateFile = File(candidatePath)
        require(candidateFile.isAbsolute) { "candidatePath must be absolute: $candidatePath" }
        val canonicalCandidate = candidateFile.canonicalPath
        val canonicalBase = baseDir.canonicalPath
        require(canonicalCandidate.startsWith(canonicalBase)) {
            "candidatePath escapes app-private directory: $canonicalCandidate not under $canonicalBase"
        }
    }
}

/**
 * Intent extra keys for candidate save metadata (Phase B wiring).
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

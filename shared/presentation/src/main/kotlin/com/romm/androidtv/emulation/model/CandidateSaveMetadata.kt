package com.romm.androidtv.emulation.model

import java.io.File

/**
 * Small, immutable metadata describing a quarantined candidate SRAM that
 * `EmulationActivity` should validate and potentially adopt after core load.
 *
 * Carried from the main process to the emulation activity via strict extras.
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
     * (`SaveSyncCoordinator.adoptChosenSave`), which downloads a user-chosen save directly
     * rather than via a negotiated sync session. Real RomM session IDs are always positive
     * (server auto-increment starting at 1), so 0 never collides with one. Downstream
     * `completeSession`/`finalizeAdoption` calls already treat session completion as
     * best-effort/non-fatal, so a sentinel session id is safe there too.
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

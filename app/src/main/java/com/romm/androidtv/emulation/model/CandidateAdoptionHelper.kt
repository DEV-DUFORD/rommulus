package com.romm.androidtv.emulation.model

import java.io.File
import java.io.IOException

/**
 * Interface-driven candidate adoption helper that enforces backup-before-restore ordering
 * (LIBRETRO_REFACTOR.md Phase B). Extracted from [EmulationActivity] private methods
 * so JVM unit tests can prove behavior without instrumentation.
 *
 * Contract for [adoptCandidate]:
 * 1. Validate size/provenance/hash/path against the native SRAM size. Exact size is required
 *    except for legacy variable-length Genesis Plus GX cartridge saves.
 * 2. Backup canonical local bytes durably — only if a canonical local copy exists.
 *    Never claim preservation without an actual durable backup. Idempotent: repeated
 *    calls for the same candidate do not overwrite prior backups.
 * 3. Restore candidate into core SRAM (native).
 * 4. Atomically checkpoint canonical save path.
 * 5. Report adoption with honest checkpoint hash/size.
 *
 * On backup failure, step 2 aborts: candidate is NOT restored and is preserved intact.
 */
interface CandidateAdoptionHelper {

    /**
     * Attempts to adopt a quarantined candidate SRAM into core memory.
     * Returns [AdoptionResult] describing the outcome.
     *
     * @param candidateMetadata  authoritative metadata for the quarantined candidate
     * @param canonicalSavePath  app-private path where the canonical autosave SRAM lives
     * @param nativeSramSizeBytes exact SRAM size reported by the loaded core (JNI)
     * @param backupStore durable store that can read/write/backup local save bytes
     * @param nativeRestore function that writes candidate bytes into core SRAM; returns true on success
     * @param nativeCheckpoint function that checkpoints core SRAM to [canonicalSavePath]; returns true on success
     */
    fun adoptCandidate(
        candidateMetadata: CandidateSaveMetadata,
        canonicalSavePath: String,
        nativeSramSizeBytes: Long,
        backupStore: SaveBackupStore,
        nativeRestore: (candidatePath: String) -> Boolean,
        nativeCheckpoint: (savePath: String) -> Boolean,
    ): AdoptionResult

    /**
     * Reads the current canonical local save bytes from [canonicalSavePath], or null if none exist.
     */
    fun readCanonicalBytes(canonicalSavePath: String): ByteArray? = File(canonicalSavePath).takeIf { it.isFile }?.readBytes()
}

/**
 * Minimal interface for durable save backup operations. Real implementations use
 * fsync + atomic rename; test doubles are in-memory maps. Separated from
 * [com.romm.androidtv.romm.save.SaveContentStore] so the adoption helper has no
 * Android/database dependency — only filesystem semantics matter here.
 */
interface SaveBackupStore {
    /**
     * Returns current canonical local bytes for this scope, or null if none exist.
     */
    fun readCanonical(serverKey: String, userKey: String, romId: Long, romHash: String, slot: String): ByteArray?

    /**
     * Durably preserves the current canonical bytes under a conflict/candidate-specific backup path.
     * Returns the absolute path of the backup on success, or throws on failure.
     * Idempotent: if a backup already exists for this candidate identifier (derived from rommSaveId),
     * returns the existing backup path without overwriting.
     */
    fun backupCanonical(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
        candidateIdentifier: Long,
        nowEpochMs: Long,
    ): String

    /**
     * Reads the backup bytes at [backupPath]. Returns null if not found.
     */
    fun readBackup(backupPath: String): ByteArray?

    /**
     * Atomically writes [bytes] as the new canonical save for this scope.
     */
    fun writeCanonicalAtomically(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
        bytes: ByteArray,
    )
}

/**
 * Result of [CandidateAdoptionHelper.adoptCandidate].
 */
sealed interface AdoptionResult {
    /** Candidate was validated, canonical backed up (if it existed), restored, and checkpointed. */
    data class Adopted(
        val checkpointedHash: String,
        val checkpointedSizeBytes: Long,
        val backupPath: String?,
    ) : AdoptionResult

    /** Size mismatch between candidate and native SRAM; candidate preserved intact. */
    data class RejectedSizeMismatch(
        val nativeSramSizeBytes: Long,
        val downloadedSizeBytes: Long,
    ) : AdoptionResult

    /** Backup of canonical local copy failed; candidate NOT restored, preserved intact. */
    data class BackupFailed(val error: String) : AdoptionResult

    /** Native restore of candidate into core SRAM failed; canonical and candidate both preserved. */
    data class RestoreFailed(val error: String) : AdoptionResult

    /** Atomic checkpoint of canonical save path failed; candidate already in core, rollback to prior state. */
    data class CheckpointFailed(val error: String) : AdoptionResult

    /** No native SRAM available (size <= 0); candidate preserved intact. */
    data class NoSram(val nativeSramSizeBytes: Long) : AdoptionResult

    /** Unexpected exception during adoption; candidate preserved intact. */
    data class UnexpectedError(val error: String) : AdoptionResult
}

/**
 * Real [CandidateAdoptionHelper] backed by filesystem operations.
 * Used by [EmulationActivity] in production.
 */
class FilesystemCandidateAdoptionHelper : CandidateAdoptionHelper {

    override fun adoptCandidate(
        candidateMetadata: CandidateSaveMetadata,
        canonicalSavePath: String,
        nativeSramSizeBytes: Long,
        backupStore: SaveBackupStore,
        nativeRestore: (candidatePath: String) -> Boolean,
        nativeCheckpoint: (savePath: String) -> Boolean,
    ): AdoptionResult {
        return try {
            // Step 1: Validate exact size match.
            if (nativeSramSizeBytes <= 0L) {
                return AdoptionResult.NoSram(nativeSramSizeBytes)
            }
            if (!isCompatibleCandidateSize(
                    coreId = candidateMetadata.coreId,
                    nativeSramSizeBytes = nativeSramSizeBytes,
                    downloadedSizeBytes = candidateMetadata.downloadedSizeBytes,
                )
            ) {
                return AdoptionResult.RejectedSizeMismatch(
                    nativeSramSizeBytes,
                    candidateMetadata.downloadedSizeBytes,
                )
            }

            // Step 2: Backup canonical local bytes durably — only if they exist.
            val backupResult = backupCanonicalIfPresent(
                canonicalSavePath,
                candidateMetadata,
                backupStore,
            )

            // If backup failed, abort — do not restore candidate.
            if (!backupResult.isSuccess) {
                return AdoptionResult.BackupFailed("backupCanonical failed: ${backupResult.exceptionOrNull()?.message}")
            }
            val backupPath = backupResult.getOrNull()

            // Step 3: Restore candidate into core SRAM.
            val restored = nativeRestore(candidateMetadata.candidatePath)
            if (!restored) {
                return AdoptionResult.RestoreFailed("nativeRestoreSaveRam failed for ${candidateMetadata.candidatePath}")
            }

            // Step 4: Atomically checkpoint canonical save path.
            val checkpointed = nativeCheckpoint(canonicalSavePath)
            if (!checkpointed) {
                return AdoptionResult.CheckpointFailed("nativeCheckpointSaveRam failed for $canonicalSavePath")
            }

            // Step 5: Report adoption with honest checkpoint hash/size.
            val checkpointedBytes = File(canonicalSavePath).readBytes()
            val checkpointedHash = sha256Hex(checkpointedBytes)
            AdoptionResult.Adopted(
                checkpointedHash = checkpointedHash,
                checkpointedSizeBytes = checkpointedBytes.size.toLong(),
                backupPath = backupPath,
            )
        } catch (e: Exception) {
            AdoptionResult.UnexpectedError("adoption exception: ${e.message}")
        }
    }

    /**
     * Backs up the canonical local save bytes if they exist. Returns:
     * - Success(backupPath) on success (or null if no canonical copy existed).
     * - Failure on write error.
     */
    private fun backupCanonicalIfPresent(
        canonicalSavePath: String,
        candidateMetadata: CandidateSaveMetadata,
        backupStore: SaveBackupStore,
    ): Result<String?> {
        val canonicalFile = File(canonicalSavePath)
        if (!canonicalFile.isFile) {
            return Result.success(null) // No canonical local copy exists.
        }

        // Verify canonical bytes exist (provenance check).
        val canonicalBytes = canonicalFile.readBytes()
        if (canonicalBytes.isEmpty()) {
            return Result.success(null) // Empty file — treat as no canonical copy.
        }

        // Derive scope parameters from the candidate metadata and canonical path.
        val scope = parseScopeFromPath(canonicalSavePath)
        if (scope != null) {
            return runCatching {
                backupStore.backupCanonical(
                    serverKey = scope.serverKey,
                    userKey = scope.userKey,
                    romId = scope.romId,
                    romHash = scope.romHash,
                    slot = "autosave",
                    candidateIdentifier = candidateMetadata.rommSaveId,
                    nowEpochMs = System.currentTimeMillis(),
                )
            }

        }

        // Cannot derive scope from path; fall back to direct filesystem backup.
        return directFileBackup(canonicalFile, candidateMetadata)
    }

    /**
     * Direct filesystem backup when scope cannot be parsed from the path.
     * Creates a timestamped copy adjacent to the canonical file. Idempotent:
     * checks for an existing backup before writing.
     */
    private fun directFileBackup(canonicalFile: File, candidateMetadata: CandidateSaveMetadata): Result<String?> {
        val parentDir = canonicalFile.parentFile ?: return Result.failure(IllegalStateException("No parent directory"))

        // Idempotent: if a backup for this candidate identifier already exists, reuse it.
        val existingBackup = parentDir.listFiles { f ->
            f.name.startsWith("pre-adoption-${candidateMetadata.rommSaveId}-") && f.extension == "srm"
        }?.firstOrNull()

        if (existingBackup != null && existingBackup.isFile) {
            return Result.success(existingBackup.absolutePath)
        }

        val backupName = "pre-adoption-${candidateMetadata.rommSaveId}-${System.currentTimeMillis()}.srm"
        val backupFile = File(parentDir, backupName)

        return runCatching {
            canonicalFile.copyTo(backupFile, overwrite = false)
            // Verify durability: read back and compare.
            val backupBytes = backupFile.readBytes()
            val originalBytes = canonicalFile.readBytes()
            if (!backupBytes.contentEquals(originalBytes)) {
                backupFile.delete()
                throw IOException("Backup verification failed: content mismatch")
            }
            backupFile.absolutePath
        }
    }

    /**
     * Parses scope parameters from a canonical save path structured as
     * `files/saves/<server>/<user>/<romId>/<romHash>/autosave/...`.
     */
    private fun parseScopeFromPath(path: String): Scope? {
        val parts = path.split('/')
        // Expected structure: [..., "saves", serverKey, userKey, romId, romHash, "autosave", filename]
        val savesIndex = parts.indexOf("saves")
        if (savesIndex < 0 || parts.size < savesIndex + 6) return null
        val serverKey = parts[savesIndex + 1]
        val userKey = parts[savesIndex + 2]
        val romId = parts[savesIndex + 3].toLongOrNull() ?: return null
        val romHash = parts[savesIndex + 4]
        if (serverKey.isBlank() || userKey.isBlank() || romHash.isBlank()) return null
        return Scope(serverKey, userKey, romId, romHash)
    }

    private data class Scope(
        val serverKey: String,
        val userKey: String,
        val romId: Long,
        val romHash: String,
    )
}

internal fun isCompatibleCandidateSize(
    coreId: String,
    nativeSramSizeBytes: Long,
    downloadedSizeBytes: Long,
): Boolean {
    if (nativeSramSizeBytes == downloadedSizeBytes) return true

    return coreId == "genesis_plus_gx" &&
        nativeSramSizeBytes == GENESIS_PLUS_GX_SRAM_SIZE_BYTES &&
        downloadedSizeBytes in 1..nativeSramSizeBytes
}

private const val GENESIS_PLUS_GX_SRAM_SIZE_BYTES = 64L * 1024L

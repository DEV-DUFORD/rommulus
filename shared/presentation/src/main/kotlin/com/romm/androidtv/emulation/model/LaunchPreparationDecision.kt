package com.romm.androidtv.emulation.model

import com.romm.androidtv.romm.RommApiError
import com.romm.androidtv.romm.save.SaveSyncOutcome

/**
 * Platform-neutral log sink for the launch-preparation decision logic. Replaces direct use of
 * the platform logging facility so the mapping stays testable on the JVM with a recording fake.
 * Priority values are conventional (see [LaunchLogPriority]).
 */
fun interface LaunchLogger {
    fun log(priority: Int, tag: String, message: String)
}

/** Conventional log priorities; numeric values mirror standard Android log levels. */
object LaunchLogPriority {
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
}

/**
 * Pure pre-launch save-sync preparation decision logic, extracted from the platform
 * orchestrator so it is unit-testable without any activity, intent, or logging framework.
 *
 * Maps a [SaveSyncOutcome] (produced by a `SaveSyncCoordinator` implementation) onto a
 * [PreparationResult] that presentation layers can dispatch:
 * - NoOpSynced / Downloaded / UploadQueued -> [PreparationResult.Ready] (no candidate)
 * - AwaitingCoreValidation -> [PreparationResult.Ready] with [CandidateSaveMetadata]
 * - ConflictRequiresResolution -> [PreparationResult.Conflict]
 * - Quarantined -> [PreparationResult.Quarantined]
 * - PlayOfflineLocal -> [PreparationResult.OfflineLocal]
 * - Failure(AUTH_EXPIRED) -> [PreparationResult.AuthExpired]; any other failure -> [PreparationResult.Failed]
 *
 * No suspension, no I/O: the outcome is already fully decided by the coordinator; this unit
 * only decides how to present it.
 */
class LaunchPreparationDecision(
    private val logger: LaunchLogger = LaunchLogger { _, _, _ -> },
    private val logTag: String = "SaveLaunchOrch",
) {

    /** Pure outcome-to-presentation mapping (see class KDoc for the branch table). */
    fun mapSyncOutcome(
        outcome: SaveSyncOutcome,
        romId: Long,
        romHash: String,
        coreId: String,
        coreBuildRevision: String,
    ): PreparationResult {
        return when (outcome) {
            is SaveSyncOutcome.AwaitingCoreValidation -> {
                val candidateMetadata = CandidateSaveMetadata(
                    rommSessionId = outcome.sessionId,
                    rommSaveId = outcome.rommSaveId,
                    candidatePath = outcome.quarantinedPath,
                    downloadedSizeBytes = outcome.downloadedSizeBytes,
                    serverContentHash = outcome.serverContentHash,
                    emulator = outcome.emulator,
                    romId = romId,
                    romHash = romHash,
                    coreId = coreId,
                    coreBuildRevision = coreBuildRevision,
                )
                logger.log(LaunchLogPriority.INFO, logTag, "prepare: AwaitingCoreValidation candidate prepared")
                PreparationResult.Ready(candidateMetadata)
            }
            is SaveSyncOutcome.Downloaded -> {
                logger.log(LaunchLogPriority.DEBUG, logTag, "prepare: sync downloaded server save")
                PreparationResult.Ready(null)
            }
            is SaveSyncOutcome.NoOpSynced -> {
                logger.log(LaunchLogPriority.DEBUG, logTag, "prepare: no-op sync")
                PreparationResult.Ready(null)
            }
            is SaveSyncOutcome.UploadQueued -> {
                logger.log(LaunchLogPriority.DEBUG, logTag, "prepare: upload queued for post-play")
                PreparationResult.Ready(null)
            }
            is SaveSyncOutcome.ConflictRequiresResolution -> {
                logger.log(LaunchLogPriority.WARN, logTag, "prepare: conflict requires resolution")
                PreparationResult.Conflict(
                    sessionId = outcome.sessionId,
                    operation = outcome.operation,
                )
            }
            is SaveSyncOutcome.Quarantined -> {
                logger.log(LaunchLogPriority.WARN, logTag, "prepare: download quarantined (${outcome.reason})")
                PreparationResult.Quarantined(
                    reason = outcome.reason,
                    quarantinedPath = outcome.quarantinedPath,
                )
            }
            is SaveSyncOutcome.PlayOfflineLocal -> {
                logger.log(LaunchLogPriority.WARN, logTag, "prepare: sync offline, launching with local save (${outcome.error})")
                PreparationResult.OfflineLocal(outcome.error.name)
            }
            is SaveSyncOutcome.Failure -> {
                logger.log(LaunchLogPriority.WARN, logTag, "prepare: pre-launch sync failed (${outcome.error})")
                // Preserve typed AUTH_EXPIRED so caller can attempt reconciliation.
                if (outcome.error == RommApiError.AUTH_EXPIRED) {
                    return PreparationResult.AuthExpired
                }
                PreparationResult.Failed("Save sync failed: ${outcome.error.name}")
            }
        }
    }

    /**
     * Builds a [CandidateSaveMetadata] from an AwaitingCoreValidation outcome's fields.
     * All inputs must be authoritative (see [CandidateSaveMetadata]).
     */
    fun buildCandidateMetadata(
        rommSessionId: Long,
        rommSaveId: Long,
        quarantinedPath: String,
        downloadedSizeBytes: Long,
        serverContentHash: String?,
        emulator: String?,
        romId: Long,
        romHash: String,
        coreId: String,
        coreBuildRevision: String,
    ): CandidateSaveMetadata = CandidateSaveMetadata(
        rommSessionId = rommSessionId,
        rommSaveId = rommSaveId,
        candidatePath = quarantinedPath,
        downloadedSizeBytes = downloadedSizeBytes,
        serverContentHash = serverContentHash,
        emulator = emulator,
        romId = romId,
        romHash = romHash,
        coreId = coreId,
        coreBuildRevision = coreBuildRevision,
    )
}

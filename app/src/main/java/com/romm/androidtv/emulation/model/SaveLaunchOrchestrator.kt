package com.romm.androidtv.emulation.model

import android.util.Log
import com.romm.androidtv.library.ui.ConflictResolutionMapper
import com.romm.androidtv.romm.StagingOutcome
import com.romm.androidtv.romm.save.SaveSyncCoordinator
import com.romm.androidtv.romm.save.SaveSyncOutcome
import com.romm.androidtv.romm.save.SaveSyncRequest

/**
 * Orchestrates pre-launch save-sync preparation for both debug and native-library flows.
 * Eliminates duplicated sync-outcome handling between [MainActivity.launchStagedRom]
 * and [MainActivity.launchStagedRomNativeLibrary].
 *
 * Presentation policies are injected via [PreLaunchPresentationPolicy]:
 * - Debug builds can report failure as a generic error string.
 * - Native Library routes conflict/quarantine into overlay state (conflictModel/quarantineModel).
 */
class SaveLaunchOrchestrator(
    private val coordinator: SaveSyncCoordinator,
    private val logTag: String = "SaveLaunchOrch",
) {

    /**
     * Runs pre-launch sync negotiation and returns a [PreparationResult].
     * The caller (MainActivity) is responsible for dispatching the result
     * through its presentation policy.
     */
    suspend fun prepare(
        romId: Long,
        romHash: String,
        coreId: String,
        @Suppress("UNUSED_PARAMETER") coreBuildRevision: String, // Intentionally resolved from CoreManifest internally.
        expectedSramSizeBytes: Long?,
        fileName: String,
    ): PreparationResult {
        // Resolve authoritative core build revision from CoreManifest — never fabricate.
        val coreFinding = CoreManifest.findById(coreId)
            ?: return PreparationResult.Failed("No approved core manifest for $coreId")

        val resolvedCoreBuildRevision = coreFinding.commitSha.takeIf { it.isNotBlank() }
            ?: coreFinding.releaseTag.takeIf { it.isNotBlank() }
            ?: return PreparationResult.Failed("Core manifest incomplete for $coreId")

        val syncOutcome = try {
            coordinator.syncBeforeLaunch(
                SaveSyncRequest(
                    romId = romId,
                    romHash = romHash,
                    coreId = coreId,
                    coreBuildRevision = resolvedCoreBuildRevision,
                    expectedSramSizeBytes = expectedSramSizeBytes,
                    fileName = fileName,
                )
            )
        } catch (e: Exception) {
            Log.e(logTag, "prepare: pre-launch sync threw exception", e)
            return PreparationResult.Failed("Save sync error: ${e.message ?: "unknown"}. Check network connectivity.")
        }

        return mapSyncOutcome(syncOutcome, romId, romHash, coreId, resolvedCoreBuildRevision)
    }

    private suspend fun mapSyncOutcome(
        outcome: SaveSyncOutcome,
        romId: Long,
        romHash: String,
        coreId: String,
        coreBuildRevision: String,
    ): PreparationResult = when (outcome) {
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
            Log.i(logTag, "prepare: AwaitingCoreValidation candidate prepared")
            PreparationResult.Ready(candidateMetadata)
        }
        is SaveSyncOutcome.Downloaded -> {
            Log.d(logTag, "prepare: sync downloaded server save")
            PreparationResult.Ready(null)
        }
        is SaveSyncOutcome.NoOpSynced -> {
            Log.d(logTag, "prepare: no-op sync")
            PreparationResult.Ready(null)
        }
        is SaveSyncOutcome.UploadQueued -> {
            Log.d(logTag, "prepare: upload queued for post-play")
            PreparationResult.Ready(null)
        }
        is SaveSyncOutcome.ConflictRequiresResolution -> {
            Log.w(logTag, "prepare: conflict requires resolution")
            PreparationResult.Conflict(
                sessionId = outcome.sessionId,
                operation = outcome.operation,
            )
        }
        is SaveSyncOutcome.Quarantined -> {
            Log.w(logTag, "prepare: download quarantined (${outcome.reason})")
            PreparationResult.Quarantined(
                reason = outcome.reason,
                quarantinedPath = outcome.quarantinedPath,
            )
        }
        is SaveSyncOutcome.Failure -> {
            Log.w(logTag, "prepare: pre-launch sync failed (${outcome.error})")
            PreparationResult.Failed("Save sync failed: ${outcome.error.name}. Check network and try again.")
        }
    }

    /**
     * Builds a [CandidateSaveMetadata] from an AwaitingCoreValidation outcome.
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

    /**
     * Sealed result of [prepare]. Each branch carries only the data the caller
     * needs for its presentation policy. The orchestrator does NOT mutate UI state.
     */
    sealed interface PreparationResult {
        /** Ready to launch EmulationActivity; candidate metadata may be null. */
        data class Ready(val candidateMetadata: CandidateSaveMetadata?) : PreparationResult

        /** Conflict detected; caller must show conflict-resolution overlay and await user choice. */
        data class Conflict(
            val sessionId: Long,
            val operation: com.romm.androidtv.romm.SyncOperation,
        ) : PreparationResult

        /** Quarantined download; caller may show quarantine overlay. */
        data class Quarantined(val reason: String, val quarantinedPath: String) : PreparationResult

        /** Launch blocked with an actionable error message. */
        data class Failed(val reason: String) : PreparationResult
    }
}

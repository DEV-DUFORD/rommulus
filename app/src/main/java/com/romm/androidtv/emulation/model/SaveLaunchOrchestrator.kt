package com.romm.androidtv.emulation.model

import android.util.Log
import com.romm.androidtv.romm.SyncOperation
import com.romm.androidtv.romm.save.SaveSyncCoordinator
import com.romm.androidtv.romm.save.SaveSyncRequest

/** Stable tag for all auth-loop boundary diagnostics (logcat -s RommAuthDx). */
private const val DIAG_TAG = "RommAuthDx"

/** Safe diagnostic logger: swallows unmocked android.util.Log in JVM unit tests. */
private fun diagLog(priority: Int, message: String) {
    try { android.util.Log.println(priority, DIAG_TAG, message) } catch (_: Exception) { /* JVM test env */ }
}

/**
 * Orchestrates pre-launch save-sync preparation for both debug and native-library flows.
 * Eliminates duplicated sync-outcome handling between [MainActivity.launchStagedRom]
 * and [MainActivity.launchStagedRomNativeLibrary].
 *
 * Thin platform delegator: the pure outcome-to-presentation mapping lives in
 * [LaunchPreparationDecision] (`:shared:presentation`, canonical type
 * [com.romm.androidtv.emulation.model.PreparationResult]), which is shared with the desktop
 * port. This class only (1) resolves the authoritative core build revision from
 * CoreManifest, (2) invokes the injected [SaveSyncCoordinator], (3) converts the shared
 * result into the app-local nested [PreparationResult] below, and (4) bridges platform
 * logging into the decision unit.
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
     * App-local mirror of the canonical shared [com.romm.androidtv.emulation.model.PreparationResult]
     * (same variants, same fields). Kotlin does not support nested type aliases, so this
     * declaration exists purely for source compatibility with existing
     * `SaveLaunchOrchestrator.PreparationResult.*` references in MainActivity and tests.
     * Delete once those callers migrate to the shared top-level type.
     */
    sealed interface PreparationResult {
        /** Ready to launch EmulationActivity; candidate metadata may be null. */
        data class Ready(val candidateMetadata: CandidateSaveMetadata?) : PreparationResult

        /** Conflict detected; caller must show conflict-resolution overlay and await user choice. */
        data class Conflict(
            val sessionId: Long,
            val operation: SyncOperation,
        ) : PreparationResult

        /** Quarantined download; caller may show quarantine overlay. */
        data class Quarantined(val reason: String, val quarantinedPath: String) : PreparationResult

        /** Bearer-authenticated sync returned AUTH_EXPIRED (401/403). Caller should reconcile or prompt login. */
        data object AuthExpired : PreparationResult

        /**
         * Pre-launch sync failed with a transient server outage but a valid durable local save
         * exists. Caller should launch the game with the local copy (the pending save remains
         * queued for later upload). [reason] is the underlying error name, informational only.
         */
        data class OfflineLocal(val reason: String) : PreparationResult

        /** Launch blocked with an actionable error message (non-auth failure). */
        data class Failed(val reason: String) : PreparationResult
    }

    private val decision = LaunchPreparationDecision(
        logger = LaunchLogger { priority, tag, message ->
            try { android.util.Log.println(priority, tag, message) } catch (_: Exception) { /* JVM test env */ }
        },
        logTag = logTag,
    )

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
            diagLog(android.util.Log.WARN, "SaveLaunchOrch.prepare: exception ${e.javaClass.simpleName}")
            return PreparationResult.Failed("Save sync error: ${e.message ?: "unknown"}")
        }

        val result = toAppResult(decision.mapSyncOutcome(syncOutcome, romId, romHash, coreId, resolvedCoreBuildRevision))
        diagLog(android.util.Log.DEBUG, "SaveLaunchOrch.prepare: result=${result.javaClass.simpleName}")
        return result
    }

    /**
     * Native save-picker variant of [prepare]: the user has already chosen a specific server
     * save (from [com.romm.androidtv.romm.RommSyncApi.listSaves]) rather than letting negotiate
     * decide. Calls [SaveSyncCoordinator.adoptChosenSave] instead of
     * [SaveSyncCoordinator.syncBeforeLaunch] and reuses the exact same shared mapping
     * ([LaunchPreparationDecision.mapSyncOutcome]), so downstream callers (MainActivity's
     * `dispatchPreparationResult`) need no special-casing — a mismatch still surfaces as
     * [PreparationResult.Quarantined], never a silent overwrite.
     */
    suspend fun prepareWithChosenSave(
        romId: Long,
        romHash: String,
        coreId: String,
        expectedSramSizeBytes: Long?,
        chosenSaveId: Long,
        chosenSaveEmulator: String?,
        chosenSaveContentHash: String?,
    ): PreparationResult {
        val coreFinding = CoreManifest.findById(coreId)
            ?: return PreparationResult.Failed("No approved core manifest for $coreId")

        val resolvedCoreBuildRevision = coreFinding.commitSha.takeIf { it.isNotBlank() }
            ?: coreFinding.releaseTag.takeIf { it.isNotBlank() }
            ?: return PreparationResult.Failed("Core manifest incomplete for $coreId")

        val syncOutcome = try {
            coordinator.adoptChosenSave(
                com.romm.androidtv.romm.save.AdoptSaveRequest(
                    romId = romId,
                    romHash = romHash,
                    coreId = coreId,
                    coreBuildRevision = resolvedCoreBuildRevision,
                    expectedSramSizeBytes = expectedSramSizeBytes,
                    chosenSaveId = chosenSaveId,
                    chosenSaveEmulator = chosenSaveEmulator,
                    chosenSaveContentHash = chosenSaveContentHash,
                )
            )
        } catch (e: Exception) {
            Log.e(logTag, "prepareWithChosenSave: adoption threw exception", e)
            diagLog(android.util.Log.WARN, "SaveLaunchOrch.prepareWithChosenSave: exception ${e.javaClass.simpleName}")
            return PreparationResult.Failed("Save adopt error: ${e.message ?: "unknown"}")
        }

        val result = toAppResult(decision.mapSyncOutcome(syncOutcome, romId, romHash, coreId, resolvedCoreBuildRevision))
        diagLog(android.util.Log.DEBUG, "SaveLaunchOrch.prepareWithChosenSave: result=${result.javaClass.simpleName}")
        return result
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
    ): CandidateSaveMetadata = decision.buildCandidateMetadata(
        rommSessionId = rommSessionId,
        rommSaveId = rommSaveId,
        quarantinedPath = quarantinedPath,
        downloadedSizeBytes = downloadedSizeBytes,
        serverContentHash = serverContentHash,
        emulator = emulator,
        romId = romId,
        romHash = romHash,
        coreId = coreId,
        coreBuildRevision = coreBuildRevision,
    )

    /**
     * Converts the canonical shared result into the app-local mirror type (identical shape).
     */
    private fun toAppResult(
        result: com.romm.androidtv.emulation.model.PreparationResult,
    ): PreparationResult = when (result) {
        is com.romm.androidtv.emulation.model.PreparationResult.Ready ->
            PreparationResult.Ready(result.candidateMetadata)
        is com.romm.androidtv.emulation.model.PreparationResult.Conflict ->
            PreparationResult.Conflict(result.sessionId, result.operation)
        is com.romm.androidtv.emulation.model.PreparationResult.Quarantined ->
            PreparationResult.Quarantined(result.reason, result.quarantinedPath)
        is com.romm.androidtv.emulation.model.PreparationResult.AuthExpired ->
            PreparationResult.AuthExpired
        is com.romm.androidtv.emulation.model.PreparationResult.OfflineLocal ->
            PreparationResult.OfflineLocal(result.reason)
        is com.romm.androidtv.emulation.model.PreparationResult.Failed ->
            PreparationResult.Failed(result.reason)
    }
}

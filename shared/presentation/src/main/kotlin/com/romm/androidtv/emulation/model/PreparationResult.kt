package com.romm.androidtv.emulation.model

import com.romm.androidtv.romm.SyncOperation

/**
 * Sealed result of pre-launch save-sync preparation ([LaunchPreparationDecision.mapSyncOutcome]).
 * Each branch carries only the data the caller needs for its presentation policy. The decision
 * unit does NOT mutate UI state; the platform orchestrator (e.g. `SaveLaunchOrchestrator` in the
 * Android app) dispatches the result through its own presentation policy.
 */
sealed interface PreparationResult {
    /** Ready to launch the emulation activity; candidate metadata may be null. */
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

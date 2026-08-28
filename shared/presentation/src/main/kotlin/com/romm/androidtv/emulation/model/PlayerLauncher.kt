package com.romm.androidtv.emulation.model

/**
 * Opaque handle for one in-flight emulation launch, returned by [PlayerLauncher.launch].
 *
 * The [sessionId] is the app launch session ID ([LaunchSpec.sessionId] as a string) —
 * the same value that correlates journal entries and results. Callers must not parse it;
 * implementations may attach platform-specific state to the token internally.
 */
data class LaunchToken(val sessionId: String) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
    }
}

/**
 * One emulation result recovered from durable state after a process restart, paired
 * with the [LaunchToken] that identifies its session.
 */
data class RecoveredResult(val token: LaunchToken, val result: EmulationResult) {
    init {
        require(token.sessionId == result.sessionId) {
            "token/result session mismatch: ${token.sessionId} != ${result.sessionId}"
        }
    }
}

/**
 * Platform-neutral contract for starting an emulation session and observing its outcome.
 *
 * Two implementations are anticipated (Linux port plan, plans/LINUX_X64.md):
 *
 * - **Android**: starts `EmulationActivity` via an activity-result launcher
 *   (`ActivityResultLauncher` + strict Intent extras); [awaitResult] suspends until the
 *   activity result callback delivers an [EmulationResult]; [recoverPending] re-reads
 *   durable launch-journal state for sessions orphaned by process death.
 * - **Desktop (Linux)**: spawns a supervised `rommulus-player` child process;
 *   [awaitResult] suspends until the process exits and its result-file protocol entry is
 *   read and parsed into an [EmulationResult]; [recoverPending] scans pending session
 *   directories for results written by a previous run.
 *
 * Callers (presenters/view-models) depend only on this contract, never on the platform
 * mechanism underneath it. Implementations must not fabricate results: every
 * [EmulationResult] must be backed by an honest journal entry or result file.
 */
interface PlayerLauncher {

    /**
     * Starts one emulation session for [spec], using [savePath] as the canonical SRAM
     * location and carrying optional [candidateMetadata] (a quarantined server candidate
     * awaiting post-load core validation). Returns a token to pass to [awaitResult].
     */
    suspend fun launch(
        spec: LaunchSpec,
        savePath: String,
        candidateMetadata: CandidateSaveMetadata?,
    ): LaunchToken

    /**
     * Suspends until the session identified by [token] finishes, then returns its outcome.
     */
    suspend fun awaitResult(token: LaunchToken): EmulationResult

    /**
     * Recovers outcomes for sessions that were still pending when the previous process
     * instance died (journal/result-file backed). Returns an empty list when nothing is
     * pending. Must be safe to call repeatedly (idempotent recovery).
     */
    suspend fun recoverPending(): List<RecoveredResult>
}

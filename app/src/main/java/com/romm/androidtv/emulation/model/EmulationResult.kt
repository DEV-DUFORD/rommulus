package com.romm.androidtv.emulation.model

/**
 * Outcome of one emulation session, returned from the emulation process to the
 * main process (LIBRETRO_REFACTOR.md section 6, steps 11-13). Drives whether
 * SRAM is adopted, synchronization is queued, and the session's dirty marker
 * is cleared.
 *
 * This is a Phase 1 seam type: no emulation session exists yet to produce one.
 */
sealed interface EmulationResult {

    /** The session that produced this result. */
    val sessionId: String

    data class Completed(
        override val sessionId: String,
        /** Absolute app-private path to the atomically checkpointed SRAM file, if any changed. */
        val checkpointedSavePath: String?,
        val checkpointedSaveHash: String?,
    ) : EmulationResult

    data class Crashed(
        override val sessionId: String,
        val message: String,
        /**
         * Whether a checkpoint from before the crash exists and should be recovered
         * on the main process's next resume (section 6, step 13).
         */
        val recoverableCheckpointPath: String?,
    ) : EmulationResult

    data class Rejected(
        override val sessionId: String,
        val reason: String,
    ) : EmulationResult
}

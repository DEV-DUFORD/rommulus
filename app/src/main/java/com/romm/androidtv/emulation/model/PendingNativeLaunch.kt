package com.romm.androidtv.emulation.model

/**
 * Holds everything needed to launch [EmulationActivity] once a fade-to-black transition
 * has completed. The main process populates this when pre-launch preparation reports
 * [SaveLaunchOrchestrator.PreparationResult.Ready], allowing the Compose overlay to animate
 * before actually starting the emulation activity.
 */
class PendingNativeLaunch(
    val spec: LaunchSpec,
    val savePath: String,
    val candidateMetadata: CandidateSaveMetadata?,
) {
    /**
     * Set once the fade-to-black overlay has actually started EmulationActivity. Guards against
     * the overlay's [LaunchedEffect] re-running (and double-launching) on recomposition while the
     * overlay stays visible during the activity transition.
     */
    @Volatile
    var launched: Boolean = false

    override fun toString(): String = "PendingNativeLaunch(romId=${spec.romId}, launched=$launched)"
}
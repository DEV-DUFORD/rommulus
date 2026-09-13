package com.romm.androidtv.emulation.process

import com.romm.androidtv.emulation.model.DescriptorState
import com.romm.androidtv.emulation.model.SessionDescriptor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EmulationInputRoutingPolicyTest {
    @Test
    fun `gameplay receives input only while no blocking overlay is visible`() {
        assertThat(shouldRouteGameplayInput(true, PauseOverlay.CLOSED, false)).isTrue()
        assertThat(shouldRouteGameplayInput(true, PauseOverlay.MENU, false)).isFalse()
        assertThat(shouldRouteGameplayInput(true, PauseOverlay.CONTROLLER_MENU, false)).isFalse()
        assertThat(shouldRouteGameplayInput(true, PauseOverlay.VIDEO_OPTIONS, false)).isFalse()
        assertThat(shouldRouteGameplayInput(true, PauseOverlay.CONTROLLER_SETTINGS, false)).isFalse()
        assertThat(shouldRouteGameplayInput(true, PauseOverlay.TOUCH_CONTROLLER_SETTINGS, false)).isFalse()
        assertThat(shouldRouteGameplayInput(true, PauseOverlay.CLOSED, true)).isFalse()
        assertThat(shouldRouteGameplayInput(false, PauseOverlay.CLOSED, false)).isFalse()
    }

    @Test
    fun `backgrounding gameplay opens pause menu without discarding an existing pause page`() {
        assertThat(pauseOverlayOnBackground(PauseOverlay.CLOSED)).isEqualTo(PauseOverlay.MENU)
        assertThat(pauseOverlayOnBackground(PauseOverlay.MENU)).isEqualTo(PauseOverlay.MENU)
        assertThat(pauseOverlayOnBackground(PauseOverlay.CONTROLLER_MENU))
            .isEqualTo(PauseOverlay.CONTROLLER_MENU)
        assertThat(pauseOverlayOnBackground(PauseOverlay.VIDEO_OPTIONS))
            .isEqualTo(PauseOverlay.VIDEO_OPTIONS)
        assertThat(pauseOverlayOnBackground(PauseOverlay.CONTROLLER_SETTINGS))
            .isEqualTo(PauseOverlay.CONTROLLER_SETTINGS)
        assertThat(pauseOverlayOnBackground(PauseOverlay.TOUCH_CONTROLLER_SETTINGS))
            .isEqualTo(PauseOverlay.TOUCH_CONTROLLER_SETTINGS)
    }

    @Test
    fun `recreated activity restores a paused overlay and never resumes gameplay`() {
        assertThat(restoredPauseOverlay("VIDEO_OPTIONS")).isEqualTo(PauseOverlay.VIDEO_OPTIONS)
        assertThat(restoredPauseOverlay("CLOSED")).isEqualTo(PauseOverlay.MENU)
        assertThat(restoredPauseOverlay("unknown")).isEqualTo(PauseOverlay.MENU)
        assertThat(restoredPauseOverlay(null)).isEqualTo(PauseOverlay.MENU)
    }

    @Test
    fun `activity recreation never re-adopts original candidate over recovered SRAM`() {
        val adopted = sessionDescriptor(state = DescriptorState.ADOPTED)
        val backgrounded = sessionDescriptor(
            state = DescriptorState.CORE_LOADED,
            recoveryStatePath = "/data/recovery/session.state",
            recoveryPauseOverlay = "MENU",
        )

        assertThat(shouldAttemptCandidateAdoption(null)).isTrue()
        assertThat(shouldAttemptCandidateAdoption(sessionDescriptor(DescriptorState.LAUNCHED))).isTrue()
        assertThat(shouldAttemptCandidateAdoption(adopted)).isFalse()
        assertThat(shouldAttemptCandidateAdoption(sessionDescriptor(DescriptorState.REJECTED))).isFalse()
        assertThat(shouldAttemptCandidateAdoption(sessionDescriptor(DescriptorState.CRASHED))).isFalse()
        assertThat(shouldAttemptCandidateAdoption(backgrounded)).isFalse()
        assertThat(
            shouldAttemptCandidateAdoption(
                sessionDescriptor(DescriptorState.CORE_LOADED).copy(recoveryStateVersion = 1),
            ),
        ).isFalse()
    }

    private fun sessionDescriptor(
        state: DescriptorState,
        recoveryStatePath: String? = null,
        recoveryPauseOverlay: String? = null,
    ) = SessionDescriptor(
        sessionId = "session",
        state = state,
        candidatePath = "/data/candidate.srm",
        candidateDownloadedSizeBytes = 32768,
        canonicalSavePath = "/data/autosave.srm",
        checkpointedHash = "hash",
        errorDetail = null,
        recoveryStatePath = recoveryStatePath,
        recoveryStateVersion = recoveryStatePath?.let { 1 },
        recoveryPauseOverlay = recoveryPauseOverlay,
    )

    @Test
    fun `quick back from video options or controller settings returns to menu never closes`() {
        assertThat(quickBackTransition(PauseOverlay.CLOSED)).isEqualTo(PauseOverlay.MENU)
        assertThat(quickBackTransition(PauseOverlay.MENU)).isEqualTo(PauseOverlay.CLOSED)
        assertThat(quickBackTransition(PauseOverlay.CONTROLLER_MENU)).isEqualTo(PauseOverlay.MENU)
        assertThat(quickBackTransition(PauseOverlay.VIDEO_OPTIONS)).isEqualTo(PauseOverlay.MENU)
        assertThat(quickBackTransition(PauseOverlay.CONTROLLER_SETTINGS)).isEqualTo(PauseOverlay.MENU)
        assertThat(quickBackTransition(PauseOverlay.TOUCH_CONTROLLER_SETTINGS))
            .isEqualTo(PauseOverlay.MENU)
    }

    @Test
    fun `controller settings opens a touch submenu only on touchscreen devices`() {
        assertThat(controllerSettingsTransition(hasTouchscreen = true))
            .isEqualTo(PauseOverlay.CONTROLLER_MENU)
        assertThat(controllerSettingsTransition(hasTouchscreen = false))
            .isEqualTo(PauseOverlay.CONTROLLER_SETTINGS)
    }

    @Test
    fun `game without save memory exits without reporting a checkpoint failure`() {
        assertThat(classifyCheckpointOutcome(0L, checkpointSucceeded = false))
            .isEqualTo(CheckpointOutcome.NO_SAVE_MEMORY)
    }

    @Test
    fun `save-capable game still reports a real checkpoint failure`() {
        assertThat(classifyCheckpointOutcome(32768L, checkpointSucceeded = false))
            .isEqualTo(CheckpointOutcome.FAILED)
        assertThat(classifyCheckpointOutcome(32768L, checkpointSucceeded = true))
            .isEqualTo(CheckpointOutcome.SAVED)
    }
}

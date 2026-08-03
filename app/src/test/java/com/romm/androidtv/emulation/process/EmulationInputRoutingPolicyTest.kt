package com.romm.androidtv.emulation.process

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EmulationInputRoutingPolicyTest {
    @Test
    fun `gameplay receives input only while no blocking overlay is visible`() {
        assertThat(shouldRouteGameplayInput(true, PauseOverlay.CLOSED, false)).isTrue()
        assertThat(shouldRouteGameplayInput(true, PauseOverlay.MENU, false)).isFalse()
        assertThat(shouldRouteGameplayInput(true, PauseOverlay.CONTROLLER_SETTINGS, false)).isFalse()
        assertThat(shouldRouteGameplayInput(true, PauseOverlay.CLOSED, true)).isFalse()
        assertThat(shouldRouteGameplayInput(false, PauseOverlay.CLOSED, false)).isFalse()
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

package com.romm.androidtv.emulation.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EmulationResultTest {

    @Test
    fun `Completed carries its session id and checkpoint`() {
        val result: EmulationResult = EmulationResult.Completed(
            sessionId = "s1",
            checkpointedSavePath = "/data/saves/s1/autosave.srm",
            checkpointedSaveHash = "abc123",
        )

        assertThat(result.sessionId).isEqualTo("s1")
        assertThat(result).isInstanceOf(EmulationResult.Completed::class.java)
    }

    @Test
    fun `Crashed can carry a recoverable checkpoint path`() {
        val result: EmulationResult = EmulationResult.Crashed(
            sessionId = "s2",
            message = "native SIGSEGV",
            recoverableCheckpointPath = "/data/saves/s2/autosave.srm",
        )

        assertThat((result as EmulationResult.Crashed).recoverableCheckpointPath).isNotNull()
    }

    @Test
    fun `Rejected carries a human-readable reason`() {
        val result: EmulationResult = EmulationResult.Rejected(
            sessionId = "s3",
            reason = "another session is already active",
        )

        assertThat((result as EmulationResult.Rejected).reason).contains("already active")
    }
}

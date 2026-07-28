package com.romm.androidtv.emulation.model

import com.romm.androidtv.config.PlaybackBackend
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LaunchSpecTest {

    private fun spec(
        romId: Long = 42L,
        coreId: String = "sameboy",
        sessionId: String = "session-1",
    ) = LaunchSpec(
        romId = romId,
        romHash = "",
        contentPath = null,
        coreId = coreId,
        backend = PlaybackBackend.WEBVIEW,
        sessionId = sessionId,
    )

    @Test
    fun `a valid LaunchSpec constructs successfully`() {
        val result = spec()
        assertThat(result.saveSlot).isEqualTo("autosave")
    }

    @Test
    fun `romId must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { spec(romId = 0) }
        assertThrows(IllegalArgumentException::class.java) { spec(romId = -1) }
    }

    @Test
    fun `coreId must not be blank`() {
        assertThrows(IllegalArgumentException::class.java) { spec(coreId = "") }
    }

    @Test
    fun `sessionId must not be blank`() {
        assertThrows(IllegalArgumentException::class.java) { spec(sessionId = "") }
    }
}

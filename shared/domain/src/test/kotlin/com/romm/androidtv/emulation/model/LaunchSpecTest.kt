package com.romm.androidtv.emulation.model

import com.romm.androidtv.config.PlaybackBackend
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class LaunchSpecTest {

    private fun spec(
        romId: Long = 42L,
        coreId: String = "sameboy",
        sessionId: UUID = UUID.randomUUID(),
        serverSaveFileName: String = "test_rom.gbc",
    ) = LaunchSpec(
        romId = romId,
        romHash = "",
        contentPath = null,
        coreId = coreId,
        backend = PlaybackBackend.WEBVIEW,
        sessionId = sessionId,
        serverSaveFileName = serverSaveFileName,
    )

    @Test
    fun `a valid LaunchSpec constructs successfully`() {
        val result = spec()
        assertThat(result.saveSlot).isEqualTo("autosave")
        assertThat(result.serverSaveFileName).isEqualTo("test_rom.gbc")
        // sessionId is a UUID; sessionIdString is the string representation.
        assertThat(result.sessionIdString).isEqualTo(result.sessionId.toString())
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
    fun `sessionId is a UUID and sessionIdString matches`() {
        val uuid = UUID.randomUUID()
        val result = spec(sessionId = uuid)
        assertThat(result.sessionId).isEqualTo(uuid)
        assertThat(result.sessionIdString).isEqualTo(uuid.toString())
    }

    @Test
    fun `serverSaveFileName must not be blank`() {
        assertThrows(IllegalArgumentException::class.java) { spec(serverSaveFileName = "") }
    }

    @Test
    fun `serverSaveFileName is distinct from local SavePathPolicy filename`() {
        val result = spec(serverSaveFileName = "my_game.gbc")
        // The server save file name is the authoritative RomM file name, not the local path.
        assertThat(result.serverSaveFileName).isEqualTo("my_game.gbc")
        // Local save path uses SavePathPolicy layout, completely separate from serverSaveFileName.
        val localPath = SavePathPolicy.autosaveSramPath(
            filesDir = java.io.File("/data/local"),
            serverKey = "server",
            userKey = "alice",
            romId = 42L,
            romHash = "abc123",
        )
        assertThat(localPath).doesNotContain("my_game.gbc")
    }
}

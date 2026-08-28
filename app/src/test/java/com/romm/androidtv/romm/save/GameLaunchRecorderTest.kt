package com.romm.androidtv.romm.save

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GameLaunchRecorderTest {

    @Test
    fun `recordLaunch reports a minimal session ending at launch time`() = runBlocking {
        var recordedRequest: PlaySessionRecordRequest? = null
        val recorder = GameLaunchRecorder(
            recordPlaySession = {
                recordedRequest = it
                PlaySessionRecordResult.Success(createdCount = 1, skippedCount = 0)
            },
            clock = { 1_000L },
        )

        recorder.recordLaunch(romId = 42L)

        assertThat(recordedRequest).isEqualTo(
            PlaySessionRecordRequest(
                romId = 42L,
                slot = "autosave",
                startEpochMs = 0L,
                endEpochMs = 1_000L,
            )
        )
    }
}

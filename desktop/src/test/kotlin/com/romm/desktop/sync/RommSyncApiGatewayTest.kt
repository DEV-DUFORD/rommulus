package com.romm.desktop.sync

import com.romm.androidtv.romm.PlaySessionEntry
import com.romm.androidtv.romm.PlaySessionIngestRequest
import com.romm.androidtv.romm.PlaySessionIngestResult
import com.romm.androidtv.romm.RommApiError
import com.romm.desktop.library.StubServer
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Focused tests for [RommSyncApiGateway.ingestPlaySessions]: the gateway must delegate to
 * `RommSyncApi.ingestPlaySessions` on the shared client and surface its result. A local
 * [StubServer] stands in for the RomM backend (the `:desktop` test classpath has no
 * MockWebServer).
 */
@DisplayName("RommSyncApiGateway — ingestPlaySessions")
class RommSyncApiGatewayTest {

    private fun request(deviceId: String = "device-1") = PlaySessionIngestRequest(
        deviceId = deviceId,
        sessions = listOf(
            PlaySessionEntry(
                romId = 7L,
                saveSlot = "autosave",
                startTime = Instant.parse("2026-08-20T10:00:00Z"),
                endTime = Instant.parse("2026-08-20T10:00:00.001Z"),
                durationMs = 1L,
            ),
        ),
    )

    @Test
    fun `delegates to RommSyncApi with the exact payload and parses the success counts`() {
        StubServer().use { stub ->
            stub.start()
            val gateway = RommSyncApiGateway(OkHttpClient())

            val result = gateway.ingestPlaySessions(stub.origin, request())

            assertThat(result).isEqualTo(PlaySessionIngestResult.Success(1, 0))
            val body = stub.lastPlaySessionsBody
                ?: throw AssertionError("no request reached /api/play-sessions")
            assertThat(body).contains("\"device_id\":\"device-1\"")
            assertThat(body).contains("\"rom_id\":7")
            assertThat(body).contains("\"save_slot\":\"autosave\"")
            assertThat(body).contains("\"start_time\":\"2026-08-20T10:00:00Z\"")
            assertThat(body).contains("\"end_time\":\"2026-08-20T10:00:00.001Z\"")
            assertThat(body).contains("\"duration_ms\":1")
        }
    }

    @Test
    fun `server failure surfaces as Failure with the http code`() {
        StubServer().use { stub ->
            stub.start()
            stub.playSessionsStatus = 500
            stub.playSessionsBody = "boom"
            val gateway = RommSyncApiGateway(OkHttpClient())

            val result = gateway.ingestPlaySessions(stub.origin, request())

            assertThat(result).isInstanceOf(PlaySessionIngestResult.Failure::class.java)
            assertThat((result as PlaySessionIngestResult.Failure).httpCode).isEqualTo(500)
        }
    }

    @Test
    fun `blank origin fails without any network call`() {
        StubServer().use { stub ->
            stub.start()
            val gateway = RommSyncApiGateway(OkHttpClient())

            val result = gateway.ingestPlaySessions("", request())

            assertThat(result).isEqualTo(
                PlaySessionIngestResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED),
            )
            assertThat(stub.lastPlaySessionsBody).isNull()
        }
    }
}

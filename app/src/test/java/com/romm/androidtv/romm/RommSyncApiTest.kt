package com.romm.androidtv.romm

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("RommSyncApi — device registration and negotiated-sync models/network calls")
class RommSyncApiTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start(0)
        client = OkHttpClient.Builder().build()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString().removeSuffix("/")

    @Nested
    @DisplayName("device registration JSON parsing")
    inner class DeviceParsing {
        @Test
        fun `parses a newly-created device response`() {
            val info = RommSyncApi.parseDeviceCreateResponse(
                """{"device_id": "abc-123", "name": "My TV", "created_at": "2026-01-01T00:00:00Z"}"""
            )

            assertThat(info).isNotNull
            assertThat(info!!.deviceId).isEqualTo("abc-123")
            assertThat(info.name).isEqualTo("My TV")
            assertThat(info.createdAt).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"))
        }

        @Test
        fun `returns null when device_id is blank`() {
            assertThat(RommSyncApi.parseDeviceCreateResponse("""{"device_id": ""}""")).isNull()
        }

        @Test
        fun `returns null for malformed json`() {
            assertThat(RommSyncApi.parseDeviceCreateResponse("not json")).isNull()
        }
    }

    @Nested
    @DisplayName("registerDevice — network classification")
    inner class RegisterDevice {
        @Test
        fun `201 is a newly-created device`() {
            server.enqueue(
                MockResponse().setResponseCode(201)
                    .setBody("""{"device_id": "new-1", "name": null, "created_at": "2026-01-01T00:00:00Z"}""")
            )

            val result = RommSyncApi.registerDevice(
                client, baseUrl(),
                DeviceRegisterRequest(clientDeviceIdentifier = "install-uuid-1"),
            )

            assertThat(result).isInstanceOf(DeviceRegisterResult.Success::class.java)
            val success = result as DeviceRegisterResult.Success
            assertThat(success.device.deviceId).isEqualTo("new-1")
            assertThat(success.alreadyExisted).isFalse()
        }

        @Test
        fun `200 is a reused, already-existing device`() {
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"device_id": "existing-1", "name": null, "created_at": "2026-01-01T00:00:00Z"}""")
            )

            val result = RommSyncApi.registerDevice(client, baseUrl(), DeviceRegisterRequest())

            assertThat((result as DeviceRegisterResult.Success).alreadyExisted).isTrue()
        }

        @Test
        fun `401 classifies as AUTH_EXPIRED`() {
            server.enqueue(MockResponse().setResponseCode(401))

            val result = RommSyncApi.registerDevice(client, baseUrl(), DeviceRegisterRequest())

            assertThat((result as DeviceRegisterResult.Failure).error).isEqualTo(RommApiError.AUTH_EXPIRED)
        }

        @Test
        fun `blank origin fails without a network call`() {
            val result = RommSyncApi.registerDevice(client, "", DeviceRegisterRequest())

            assertThat((result as DeviceRegisterResult.Failure).error).isEqualTo(RommApiError.ORIGIN_NOT_CONFIGURED)
            assertThat(server.requestCount).isEqualTo(0)
        }

        @Test
        fun `request body carries the stable client device identifier for fingerprint dedup`() {
            server.enqueue(
                MockResponse().setResponseCode(201)
                    .setBody("""{"device_id": "new-1", "created_at": "2026-01-01T00:00:00Z"}""")
            )

            RommSyncApi.registerDevice(
                client, baseUrl(),
                DeviceRegisterRequest(clientDeviceIdentifier = "install-uuid-1", client = "android-tv"),
            )

            val recorded = server.takeRequest()
            assertThat(recorded.body.readUtf8()).contains("install-uuid-1").contains("android-tv")
        }
    }

    @Nested
    @DisplayName("negotiateSync — JSON parsing and network classification")
    inner class NegotiateSync {
        private val negotiateResponseJson = """
            {
              "session_id": 7,
              "operations": [
                {"action": "download", "rom_id": 1, "save_id": 10, "file_name": "autosave.srm", "slot": "autosave", "emulator": "sameboy", "reason": "newer on server", "server_updated_at": "2026-01-02T00:00:00Z", "server_content_hash": "hash1"},
                {"action": "upload", "rom_id": 2, "file_name": "autosave.srm", "slot": "autosave", "reason": "not on server"},
                {"action": "conflict", "rom_id": 3, "save_id": 11, "file_name": "autosave.srm", "slot": "autosave", "reason": "both changed"},
                {"action": "no_op", "rom_id": 4, "save_id": 12, "file_name": "autosave.srm", "slot": "autosave", "reason": "identical"}
              ],
              "total_upload": 1, "total_download": 1, "total_conflict": 1, "total_no_op": 1
            }
        """.trimIndent()

        @Test
        fun `parses all four operation outcomes`() {
            val info = RommSyncApi.parseSyncNegotiateResponse(negotiateResponseJson)

            assertThat(info).isNotNull
            assertThat(info!!.sessionId).isEqualTo(7)
            assertThat(info.operations).hasSize(4)
            assertThat(info.operations.map { it.action }).containsExactly(
                SyncAction.DOWNLOAD, SyncAction.UPLOAD, SyncAction.CONFLICT, SyncAction.NO_OP
            )
            val download = info.operations.first { it.action == SyncAction.DOWNLOAD }
            assertThat(download.saveId).isEqualTo(10)
            assertThat(download.serverContentHash).isEqualTo("hash1")
        }

        @Test
        fun `success returns parsed negotiation and posts the client save list`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody(negotiateResponseJson))

            val result = RommSyncApi.negotiateSync(
                client, baseUrl(),
                SyncNegotiateRequest(
                    deviceId = "device-1",
                    saves = listOf(
                        ClientSaveState(
                            romId = 1,
                            fileName = "autosave.srm",
                            slot = "autosave",
                            emulator = "sameboy",
                            contentHash = "localhash",
                            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
                            fileSizeBytes = 8192,
                        )
                    ),
                ),
            )

            assertThat(result).isInstanceOf(SyncNegotiateResult.Success::class.java)
            assertThat((result as SyncNegotiateResult.Success).negotiation.sessionId).isEqualTo(7)
            val recorded = server.takeRequest()
            assertThat(recorded.path).isEqualTo("/api/sync/negotiate")
            assertThat(recorded.body.readUtf8()).contains("device-1").contains("localhash")
        }

        @Test
        fun `401 classifies as AUTH_EXPIRED`() {
            server.enqueue(MockResponse().setResponseCode(401))

            val result = RommSyncApi.negotiateSync(client, baseUrl(), SyncNegotiateRequest("device-1", emptyList()))

            assertThat((result as SyncNegotiateResult.Failure).error).isEqualTo(RommApiError.AUTH_EXPIRED)
        }
    }

    @Nested
    @DisplayName("completeSyncSession")
    inner class CompleteSyncSession {
        @Test
        fun `success parses the completed session status`() {
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"session": {"id": 7, "status": "COMPLETED"}}""")
            )

            val result = RommSyncApi.completeSyncSession(
                client, baseUrl(), 7,
                SyncCompleteRequest(operationsCompleted = 3, operationsFailed = 0),
            )

            assertThat(result).isInstanceOf(SyncCompleteResult.Success::class.java)
            assertThat((result as SyncCompleteResult.Success).sessionStatus).isEqualTo("COMPLETED")
            val recorded = server.takeRequest()
            assertThat(recorded.path).isEqualTo("/api/sync/sessions/7/complete")
        }

        @Test
        fun `404 classifies as NOT_FOUND`() {
            server.enqueue(MockResponse().setResponseCode(404))

            val result = RommSyncApi.completeSyncSession(client, baseUrl(), 999, SyncCompleteRequest(0, 0))

            assertThat((result as SyncCompleteResult.Failure).error).isEqualTo(RommApiError.NOT_FOUND)
        }
    }

    @Nested
    @DisplayName("uploadSave — multipart upload and conflict handling")
    inner class UploadSave {
        @Test
        fun `success parses the returned save schema and sends multipart with query params`() {
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody(
                        """{"id": 55, "rom_id": 1, "file_name": "autosave.srm", "slot": "autosave",
                            "emulator": "sameboy", "content_hash": "hash1",
                            "updated_at": "2026-01-01T00:00:00Z", "file_size_bytes": 8192}"""
                    )
            )

            val result = RommSyncApi.uploadSave(
                client, baseUrl(),
                SaveUploadRequest(
                    romId = 1, slot = "autosave", emulator = "sameboy",
                    deviceId = "device-1", sessionId = 7, overwrite = false,
                    fileName = "autosave.srm", bytes = byteArrayOf(1, 2, 3, 4),
                ),
            )

            assertThat(result).isInstanceOf(SaveUploadResult.Success::class.java)
            assertThat((result as SaveUploadResult.Success).save.saveId).isEqualTo(55)
            val recorded = server.takeRequest()
            assertThat(recorded.path).contains("rom_id=1").contains("slot=autosave").contains("device_id=device-1")
            assertThat(recorded.body.readUtf8()).contains("saveFile").contains("autosave.srm")
        }

        @Test
        fun `409 is reported as an explicit Conflict, not a generic failure`() {
            server.enqueue(MockResponse().setResponseCode(409))

            val result = RommSyncApi.uploadSave(
                client, baseUrl(),
                SaveUploadRequest(1, "autosave", "sameboy", "device-1", null, overwrite = false, "autosave.srm", byteArrayOf(1)),
            )

            assertThat(result).isInstanceOf(SaveUploadResult.Conflict::class.java)
            assertThat((result as SaveUploadResult.Conflict).httpCode).isEqualTo(409)
        }
    }

    @Nested
    @DisplayName("downloadSaveContent and confirmDownload")
    inner class DownloadAndConfirm {
        @Test
        fun `download returns the raw bytes on success`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(byteArrayOf(9, 8, 7))))

            val result = RommSyncApi.downloadSaveContent(client, baseUrl(), 55, "device-1")

            assertThat(result).isInstanceOf(SaveDownloadResult.Success::class.java)
            assertThat((result as SaveDownloadResult.Success).bytes).isEqualTo(byteArrayOf(9, 8, 7))
            val recorded = server.takeRequest()
            assertThat(recorded.path).isEqualTo("/api/saves/55/content?device_id=device-1")
        }

        @Test
        fun `download 404 classifies as NOT_FOUND`() {
            server.enqueue(MockResponse().setResponseCode(404))

            val result = RommSyncApi.downloadSaveContent(client, baseUrl(), 999, "device-1")

            assertThat((result as SaveDownloadResult.Failure).error).isEqualTo(RommApiError.NOT_FOUND)
        }

        @Test
        fun `downloadSaveContentBackup uses optimistic=false and omits session_id`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))

            val result = RommSyncApi.downloadSaveContentBackup(client, baseUrl(), 55, "device-1")

            assertThat(result).isInstanceOf(SaveDownloadResult.Success::class.java)
            assertThat((result as SaveDownloadResult.Success).bytes).isEqualTo(byteArrayOf(1, 2, 3))
            val recorded = server.takeRequest()
            assertThat(recorded.path).isEqualTo("/api/saves/55/content?device_id=device-1&optimistic=false")
        }

        @Test
        fun `confirmDownload posts the device id and succeeds on 200`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": 55}"""))

            val result = RommSyncApi.confirmDownload(client, baseUrl(), 55, "device-1")

            assertThat(result).isInstanceOf(SaveConfirmResult.Success::class.java)
            val recorded = server.takeRequest()
            assertThat(recorded.path).isEqualTo("/api/saves/55/downloaded")
            assertThat(recorded.body.readUtf8()).contains("device-1")
        }

        @Test
        fun `confirmDownload 401 classifies as AUTH_EXPIRED`() {
            server.enqueue(MockResponse().setResponseCode(401))

            val result = RommSyncApi.confirmDownload(client, baseUrl(), 55, "device-1")

            assertThat((result as SaveConfirmResult.Failure).error).isEqualTo(RommApiError.AUTH_EXPIRED)
        }
    }

    @Nested
    @DisplayName("client-token parsing and acquisition")
    inner class ClientTokenTests {

        @Test
        fun `parseClientTokenResponse parses valid response`() {
            val info = RommSyncApi.parseClientTokenResponse(
                """{"id": 1, "name": "romm-android-tv", "scopes": ["assets","device"],
                    "raw_token": "rmm_abc123", "expires_at": "2025-01-01T00:00:00Z", "created_at": "2026-01-01T00:00:00Z"}"""
            )

            assertThat(info).isNotNull
            assertThat(info!!.token.raw).isEqualTo("rmm_abc123")
            assertThat(info.expiresAtEpochSeconds).isEqualTo(Instant.parse("2025-01-01T00:00:00Z").epochSecond)
        }

        @Test
        fun `parseClientTokenResponse handles null expires_at`() {
            val info = RommSyncApi.parseClientTokenResponse(
                """{"id": 2, "name": "test", "scopes": ["assets"], "raw_token": "rmm_xyz"}"""
            )

            assertThat(info).isNotNull
            assertThat(info!!.token.raw).isEqualTo("rmm_xyz")
            assertThat(info.expiresAtEpochSeconds).isNull()
        }

        @Test
        fun `parseClientTokenResponse returns null for blank raw_token`() {
            assertThat(RommSyncApi.parseClientTokenResponse(
                """{"raw_token": ""}"""
            )).isNull()
        }

        @Test
        fun `parseClientTokenResponse returns null for malformed json`() {
            assertThat(RommSyncApi.parseClientTokenResponse("not json")).isNull()
        }

        @Test
        fun `parseClientTokenResponse returns null for malformed expires_at`() {
            val info = RommSyncApi.parseClientTokenResponse(
                """{"id": 1, "name": "test", "scopes": ["assets"],
                    "raw_token": "rmm_valid", "expires_at": "not-a-date"}"""
            )

            // Malformed ISO-8601 in expires_at causes Instant.parse to throw,
            // which the outer catch swallows and returns null.
            assertThat(info).isNull()
        }

        @Test
        fun `acquireClientToken 201 returns Success with parsed token`() {
            server.enqueue(
                MockResponse().setResponseCode(201).setBody(
                    """{"id": 1, "name": "romm-android-tv", "scopes": ["assets","device"],
                        "raw_token": "rmm_live_token", "expires_at": null}"""
                )
            )

            val result = RommSyncApi.acquireClientToken(
                client, baseUrl(), listOf("assets", "device"),
            )

            assertThat(result).isInstanceOf(ClientTokenAcquireResult.Success::class.java)
            val success = result as ClientTokenAcquireResult.Success
            assertThat(success.info.token.raw).isEqualTo("rmm_live_token")

            val recorded = server.takeRequest()
            assertThat(recorded.path).isEqualTo("/api/client-tokens")
            assertThat(recorded.body.readUtf8()).contains("assets").contains("device")
        }

        @Test
        fun `acquireClientToken 401 classifies as AUTH_EXPIRED`() {
            server.enqueue(MockResponse().setResponseCode(401))

            val result = RommSyncApi.acquireClientToken(client, baseUrl(), listOf("assets"))

            assertThat((result as ClientTokenAcquireResult.Failure).error).isEqualTo(RommApiError.AUTH_EXPIRED)
        }

        @Test
        fun `acquireClientToken blank origin fails without network call`() {
            val result = RommSyncApi.acquireClientToken(client, "", listOf("assets"))

            assertThat((result as ClientTokenAcquireResult.Failure).error).isEqualTo(RommApiError.ORIGIN_NOT_CONFIGURED)
            assertThat(server.requestCount).isEqualTo(0)
        }
    }
}

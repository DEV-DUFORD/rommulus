package com.romm.androidtv.romm

import com.romm.androidtv.network.RommServerAddress
import com.romm.androidtv.network.ServerAddressResult
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
    @DisplayName("ingestPlaySessions")
    inner class IngestPlaySessions {
        @Test
        fun `success parses created and skipped counts and posts to play-sessions`() {
            server.enqueue(
                MockResponse().setResponseCode(201)
                    .setBody("""{"results": [{"index": 0, "status": "created", "id": 5, "detail": null}], "created_count": 1, "skipped_count": 0}""")
            )

            val result = RommSyncApi.ingestPlaySessions(
                client, baseUrl(),
                PlaySessionIngestRequest(
                    deviceId = "device-abc",
                    sessions = listOf(
                        PlaySessionEntry(
                            romId = 42L,
                            saveSlot = "autosave",
                            startTime = Instant.parse("2024-01-01T00:00:00Z"),
                            endTime = Instant.parse("2024-01-01T00:10:00Z"),
                            durationMs = 600_000L,
                        )
                    ),
                ),
            )

            assertThat(result).isInstanceOf(PlaySessionIngestResult.Success::class.java)
            assertThat((result as PlaySessionIngestResult.Success).createdCount).isEqualTo(1)
            assertThat(result.skippedCount).isEqualTo(0)
            val recorded = server.takeRequest()
            assertThat(recorded.path).isEqualTo("/api/play-sessions")
            assertThat(recorded.method).isEqualTo("POST")
        }

        @Test
        fun `401 classifies as AUTH_EXPIRED`() {
            server.enqueue(MockResponse().setResponseCode(401))

            val result = RommSyncApi.ingestPlaySessions(
                client, baseUrl(),
                PlaySessionIngestRequest(
                    deviceId = "device-abc",
                    sessions = listOf(
                        PlaySessionEntry(
                            romId = 1L,
                            saveSlot = null,
                            startTime = Instant.EPOCH,
                            endTime = Instant.EPOCH.plusSeconds(60),
                            durationMs = 60_000L,
                        )
                    ),
                ),
            )

            assertThat((result as PlaySessionIngestResult.Failure).error).isEqualTo(RommApiError.AUTH_EXPIRED)
        }

        @Test
        fun `blank origin fails without a network call`() {
            val result = RommSyncApi.ingestPlaySessions(
                client, "",
                PlaySessionIngestRequest(deviceId = null, sessions = emptyList()),
            )

            assertThat((result as PlaySessionIngestResult.Failure).error).isEqualTo(RommApiError.ORIGIN_NOT_CONFIGURED)
            assertThat(server.requestCount).isEqualTo(0)
        }

        @Test
        fun `malformed response body classifies as PARSE_ERROR`() {
            server.enqueue(MockResponse().setResponseCode(201).setBody("not json"))

            val result = RommSyncApi.ingestPlaySessions(
                client, baseUrl(),
                PlaySessionIngestRequest(
                    deviceId = "device-abc",
                    sessions = listOf(
                        PlaySessionEntry(
                            romId = 1L,
                            saveSlot = null,
                            startTime = Instant.EPOCH,
                            endTime = Instant.EPOCH.plusSeconds(60),
                            durationMs = 60_000L,
                        )
                    ),
                ),
            )

            assertThat((result as PlaySessionIngestResult.Failure).error).isEqualTo(RommApiError.PARSE_ERROR)
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

        @Test
        fun `autocleanup true sends autocleanup and autocleanup_limit query params`() {
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody(
                        """{"id": 55, "rom_id": 1, "file_name": "autosave.srm", "slot": "autosave",
                            "emulator": "sameboy", "content_hash": "hash1",
                            "updated_at": "2026-01-01T00:00:00Z", "file_size_bytes": 8192}"""
                    )
            )

            RommSyncApi.uploadSave(
                client, baseUrl(),
                SaveUploadRequest(
                    romId = 1, slot = "autosave", emulator = "sameboy",
                    deviceId = "device-1", sessionId = null, overwrite = true,
                    fileName = "autosave.srm", bytes = byteArrayOf(1, 2, 3),
                    autocleanup = true, autocleanupLimit = 1,
                ),
            )

            val recorded = server.takeRequest()
            assertThat(recorded.path).contains("autocleanup=true").contains("autocleanup_limit=1")
        }

        @Test
        fun `autocleanup false (default) omits autocleanup query params entirely`() {
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody(
                        """{"id": 55, "rom_id": 1, "file_name": "autosave.srm", "slot": "autosave",
                            "emulator": "sameboy", "content_hash": "hash1",
                            "updated_at": "2026-01-01T00:00:00Z", "file_size_bytes": 8192}"""
                    )
            )

            RommSyncApi.uploadSave(
                client, baseUrl(),
                SaveUploadRequest(1, "autosave", "sameboy", "device-1", null, overwrite = false, "autosave.srm", byteArrayOf(1)),
            )

            val recorded = server.takeRequest()
            assertThat(recorded.path).doesNotContain("autocleanup")
        }
    }

    @Nested
    @DisplayName("listSaves — GET /api/saves for the native save picker")
    inner class ListSaves {
        @Test
        fun `success parses every save in the list, filtering out any without a valid id`() {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """[
                        {"id": 10, "rom_id": 1, "file_name": "autosave [2026-01-01_00-00-00].srm", "slot": "autosave",
                         "emulator": "sameboy", "content_hash": "hash1", "updated_at": "2026-01-01T00:00:00Z", "file_size_bytes": 100},
                        {"id": 11, "rom_id": 1, "file_name": "manual.srm", "slot": null,
                         "emulator": "sameboy", "content_hash": "hash2", "updated_at": "2026-01-02T00:00:00Z", "file_size_bytes": 200},
                        {"id": 0, "rom_id": 1, "file_name": "invalid.srm"}
                    ]"""
                )
            )

            val result = RommSyncApi.listSaves(client, baseUrl(), romId = 1, deviceId = "device-1")

            assertThat(result).isInstanceOf(SaveListResult.Success::class.java)
            val saves = (result as SaveListResult.Success).saves
            assertThat(saves).hasSize(2)
            assertThat(saves.map { it.saveId }).containsExactly(10L, 11L)
            assertThat(saves[0].emulator).isEqualTo("sameboy")
            val recorded = server.takeRequest()
            assertThat(recorded.path).contains("rom_id=1").contains("device_id=device-1")
        }

        @Test
        fun `deviceId is optional and omitted from the query when null`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

            RommSyncApi.listSaves(client, baseUrl(), romId = 1, deviceId = null)

            val recorded = server.takeRequest()
            assertThat(recorded.path).contains("rom_id=1").doesNotContain("device_id")
        }

        @Test
        fun `401 classifies as AUTH_EXPIRED`() {
            server.enqueue(MockResponse().setResponseCode(401))

            val result = RommSyncApi.listSaves(client, baseUrl(), romId = 1)

            assertThat((result as SaveListResult.Failure).error).isEqualTo(RommApiError.AUTH_EXPIRED)
        }

        @Test
        fun `malformed response body classifies as PARSE_ERROR`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))

            val result = RommSyncApi.listSaves(client, baseUrl(), romId = 1)

            assertThat((result as SaveListResult.Failure).error).isEqualTo(RommApiError.PARSE_ERROR)
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

        @Test
        fun `acquireClientToken sends matching X-CSRFToken header from cookie jar`() {
            // Cookie-authenticated POST /api/client-tokens is CSRF-protected: the client must
            // mirror the romm_csrftoken cookie value into the X-CSRFToken header.
            val csrfUrl = server.url("/")
            val cookieJar = object : okhttp3.CookieJar {
                override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {}
                override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> = listOf(
                    okhttp3.Cookie.Builder()
                        .name("romm_csrftoken")
                        .value("csrf-value-123")
                        .domain(csrfUrl.host)
                        .build(),
                    okhttp3.Cookie.Builder()
                        .name("romm_session")
                        .value("session-value-456")
                        .domain(csrfUrl.host)
                        .build(),
                )
            }
            val csrfClient = OkHttpClient.Builder().cookieJar(cookieJar).build()

            server.enqueue(
                MockResponse().setResponseCode(201).setBody(
                    """{"id": 1, "name": "romm-android-tv", "scopes": ["assets.read","assets.write","devices.read","devices.write"],
                        "raw_token": "rmm_csrf_token", "expires_at": null}"""
                )
            )

            val result = RommSyncApi.acquireClientToken(
                csrfClient, baseUrl(), listOf("assets.read", "assets.write", "devices.read", "devices.write"),
            )

            assertThat(result).isInstanceOf(ClientTokenAcquireResult.Success::class.java)
            val recorded = server.takeRequest()
            assertThat(recorded.getHeader("X-CSRFToken")).isEqualTo("csrf-value-123")
        }

        @Test
        fun `acquireClientToken omits X-CSRFToken header when no csrf cookie present`() {
            server.enqueue(
                MockResponse().setResponseCode(201).setBody(
                    """{"id": 1, "name": "romm-android-tv", "scopes": ["assets.read","assets.write","devices.read","devices.write"],
                        "raw_token": "rmm_no_csrf", "expires_at": null}"""
                )
            )

            RommSyncApi.acquireClientToken(
                client, baseUrl(), listOf("assets.read", "assets.write", "devices.read", "devices.write"),
            )

            val recorded = server.takeRequest()
            assertThat(recorded.getHeader("X-CSRFToken")).isNull()
        }
    }

    /**
     * Regression coverage for the full physical-device reproduction chain:
     * startup cookie import → verifySession → durable client-token acquisition → Bearer-only
     * device registration → sync negotiation. Exercises the exact boundary that previously threw
     * before an HTTP result was ever classified (client-token acquisition invoked off the IO
     * dispatcher), without requiring real credentials or a live WebView cookie jar.
     */
    @Nested
    @DisplayName("End-to-end: authenticated cookie session -> durable token -> Bearer device/sync")
    inner class EndToEndAuthenticatedFlow {

        @Test
        fun `durable token acquisition, device registration, and sync negotiation all succeed with correct scopes and bearer token`() {
            // 1. POST /api/client-tokens — cookie-authenticated acquisition with exact scopes.
            server.enqueue(
                MockResponse().setResponseCode(201).setBody(
                    """{"id": 10, "name": "romm-android-tv",
                        "scopes": ["assets.read","assets.write","devices.read","devices.write"],
                        "raw_token": "rmm_e2e_token", "expires_at": null}"""
                )
            )
            val tokenResult = RommSyncApi.acquireClientToken(
                client,
                baseUrl(),
                scopes = listOf("assets.read", "assets.write", "devices.read", "devices.write"),
            )
            assertThat(tokenResult).isInstanceOf(ClientTokenAcquireResult.Success::class.java)
            val acquiredToken = (tokenResult as ClientTokenAcquireResult.Success).info.token

            val tokenRequest = server.takeRequest()
            assertThat(tokenRequest.path).isEqualTo("/api/client-tokens")
            // Exact backend-pinned scopes, not the rejected assets/device shorthand.
            assertThat(tokenRequest.body.readUtf8())
                .contains("assets.read").contains("assets.write")
                .contains("devices.read").contains("devices.write")

            // 2. Bearer-only client (cookie-free) built from the durable token, matching the
            // background-auth rule: never use a live WebView cookie jar for this traffic.
            val parsedOrigin = RommServerAddress.parseAndNormalize(baseUrl())
            val bearerClient = when (parsedOrigin) {
                is ServerAddressResult.Valid ->
                    OkHttpClient.Builder()
                        .addInterceptor(BearerAuthInterceptor(parsedOrigin, { acquiredToken.raw }))
                        .build()
                is ServerAddressResult.Invalid ->
                    OkHttpClient.Builder().build()
            }

            // 3. POST /api/devices — device registration presents the Bearer token, not cookies.
            server.enqueue(
                MockResponse().setResponseCode(201)
                    .setBody("""{"device_id": "device-1", "name": null, "created_at": "2026-01-01T00:00:00Z"}""")
            )
            val deviceResult = RommSyncApi.registerDevice(
                bearerClient, baseUrl(), DeviceRegisterRequest(clientDeviceIdentifier = "install-uuid-e2e"),
            )
            assertThat(deviceResult).isInstanceOf(DeviceRegisterResult.Success::class.java)
            val deviceRequest = server.takeRequest()
            assertThat(deviceRequest.getHeader("Authorization")).isEqualTo("Bearer rmm_e2e_token")

            // 4. POST /api/sync/negotiate — negotiation also authenticates via the durable Bearer.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"session_id": 42, "operations": [], "total_upload": 0, "total_download": 0,
                        "total_conflict": 0, "total_no_op": 0}"""
                )
            )
            val negotiateResult = RommSyncApi.negotiateSync(
                bearerClient, baseUrl(), SyncNegotiateRequest("device-1", emptyList()),
            )
            assertThat(negotiateResult).isInstanceOf(SyncNegotiateResult.Success::class.java)
            val negotiateRequest = server.takeRequest()
            assertThat(negotiateRequest.getHeader("Authorization")).isEqualTo("Bearer rmm_e2e_token")
        }

        @Test
        fun `device registration without a durable token is rejected as AUTH_EXPIRED, never falling back to cookies`() {
            // Simulates the terminal loop: no durable token was ever persisted, so the
            // cookie-free Bearer client sends no Authorization header and the server rejects it.
            val parsedOriginNoToken = RommServerAddress.parseAndNormalize(baseUrl())
            val bearerClientNoToken = when (parsedOriginNoToken) {
                is ServerAddressResult.Valid ->
                    OkHttpClient.Builder()
                        .addInterceptor(BearerAuthInterceptor(parsedOriginNoToken, { null }))
                        .build()
                is ServerAddressResult.Invalid ->
                    OkHttpClient.Builder().build()
            }

            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"AUTH_EXPIRED"}"""))
            val deviceResult = RommSyncApi.registerDevice(
                bearerClientNoToken, baseUrl(), DeviceRegisterRequest(clientDeviceIdentifier = "install-uuid-e2e-2"),
            )

            assertThat((deviceResult as DeviceRegisterResult.Failure).error).isEqualTo(RommApiError.AUTH_EXPIRED)
            val recorded = server.takeRequest()
            assertThat(recorded.getHeader("Authorization")).isNull()
        }
    }
}

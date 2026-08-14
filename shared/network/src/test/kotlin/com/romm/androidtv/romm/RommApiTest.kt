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

@DisplayName("RommApi — typed ROM/firmware models and network calls")
class RommApiTest {

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

    private val singleFileRomJson = """
        {
          "id": 42,
          "fs_name": "Super Game.gb",
          "fs_size_bytes": 32768,
          "platform_slug": "gb",
          "has_multiple_files": false,
          "files": [
            {
              "id": 7,
              "file_name": "Super Game.gb",
              "file_size_bytes": 32768,
              "is_top_level": true,
              "sha1_hash": "abc123",
              "md5_hash": "def456",
              "crc_hash": "ghi789"
            }
          ]
        }
    """.trimIndent()

    private val multiFileRomJson = """
        {
          "id": 43,
          "fs_name": "Multi Disc Game.zip",
          "fs_size_bytes": 999,
          "platform_slug": "psx",
          "has_multiple_files": true,
          "files": [
            {"id": 1, "file_name": "disc1.bin", "file_size_bytes": 500, "is_top_level": true, "sha1_hash": "aa"},
            {"id": 2, "file_name": "disc2.bin", "file_size_bytes": 499, "is_top_level": true, "sha1_hash": "bb"}
          ]
        }
    """.trimIndent()

    private val firmwareListJson = """
        [
          {"id": 5, "file_name": "bios.bin", "file_size_bytes": 256, "sha1_hash": "ff00", "md5_hash": "", "crc_hash": ""}
        ]
    """.trimIndent()

    @Nested
    @DisplayName("JSON parsing — pure functions, no network")
    inner class Parsing {
        @Test
        fun `parses a single-file rom with all fields`() {
            val rom = RommApi.parseRomInfo(singleFileRomJson)

            assertThat(rom).isNotNull
            assertThat(rom!!.romId).isEqualTo(42)
            assertThat(rom.fsName).isEqualTo("Super Game.gb")
            assertThat(rom.platformSlug).isEqualTo("gb")
            assertThat(rom.hasMultipleFiles).isFalse()
            assertThat(rom.files).hasSize(1)
            assertThat(rom.files.single().sha1Hash).isEqualTo("abc123")
        }

        @Test
        fun `parses a multi-file rom's file list in full`() {
            val rom = RommApi.parseRomInfo(multiFileRomJson)

            assertThat(rom).isNotNull
            assertThat(rom!!.hasMultipleFiles).isTrue()
            assertThat(rom.files).hasSize(2)
        }

        @Test
        fun `returns null for malformed json`() {
            assertThat(RommApi.parseRomInfo("not json")).isNull()
        }

        @Test
        fun `returns null when the id is missing or non-positive`() {
            assertThat(RommApi.parseRomInfo("""{"id": 0, "fs_name": "x"}""")).isNull()
        }

        @Test
        fun `parses a firmware list`() {
            val list = RommApi.parseFirmwareList(firmwareListJson)

            assertThat(list).isNotNull
            assertThat(list!!.single().fileName).isEqualTo("bios.bin")
            assertThat(list.single().sha1Hash).isEqualTo("ff00")
        }

        @Test
        fun `excludes firmware missing from server storage`() {
            val list = RommApi.parseFirmwareList(
                """
                [
                  {"id": 5, "file_name": "present.bin", "missing_from_fs": false},
                  {"id": 6, "file_name": "missing.bin", "missing_from_fs": true}
                ]
                """.trimIndent(),
            )

            assertThat(list!!.map { it.fileName }).containsExactly("present.bin")
        }

        @Test
        fun `returns null for a malformed firmware list`() {
            assertThat(RommApi.parseFirmwareList("{not a list}")).isNull()
        }
    }

    @Nested
    @DisplayName("fetchRomInfo — network classification")
    inner class FetchRomInfo {
        @Test
        fun `success returns parsed RomInfo`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody(singleFileRomJson))

            val result = RommApi.fetchRomInfo(client, baseUrl(), 42)

            assertThat(result).isInstanceOf(RomInfoResult.Success::class.java)
            assertThat((result as RomInfoResult.Success).rom.romId).isEqualTo(42)
        }

        @Test
        fun `404 classifies as NOT_FOUND`() {
            server.enqueue(MockResponse().setResponseCode(404))

            val result = RommApi.fetchRomInfo(client, baseUrl(), 999)

            assertThat(result).isInstanceOf(RomInfoResult.Failure::class.java)
            assertThat((result as RomInfoResult.Failure).error).isEqualTo(RommApiError.NOT_FOUND)
        }

        @Test
        fun `401 classifies as AUTH_EXPIRED`() {
            server.enqueue(MockResponse().setResponseCode(401))

            val result = RommApi.fetchRomInfo(client, baseUrl(), 42)

            assertThat((result as RomInfoResult.Failure).error).isEqualTo(RommApiError.AUTH_EXPIRED)
        }

        @Test
        fun `403 classifies as AUTH_EXPIRED`() {
            server.enqueue(MockResponse().setResponseCode(403))

            val result = RommApi.fetchRomInfo(client, baseUrl(), 42)

            assertThat((result as RomInfoResult.Failure).error).isEqualTo(RommApiError.AUTH_EXPIRED)
        }

        @Test
        fun `500 classifies as SERVER_ERROR`() {
            server.enqueue(MockResponse().setResponseCode(500))

            val result = RommApi.fetchRomInfo(client, baseUrl(), 42)

            assertThat((result as RomInfoResult.Failure).error).isEqualTo(RommApiError.SERVER_ERROR)
        }

        @Test
        fun `malformed body classifies as PARSE_ERROR`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))

            val result = RommApi.fetchRomInfo(client, baseUrl(), 42)

            assertThat((result as RomInfoResult.Failure).error).isEqualTo(RommApiError.PARSE_ERROR)
        }

        @Test
        fun `blank origin classifies as ORIGIN_NOT_CONFIGURED without a network call`() {
            val result = RommApi.fetchRomInfo(client, "", 42)

            assertThat((result as RomInfoResult.Failure).error).isEqualTo(RommApiError.ORIGIN_NOT_CONFIGURED)
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Nested
    @DisplayName("fetchFirmwareList — network classification")
    inner class FetchFirmwareList {
        @Test
        fun `success returns parsed list and includes the platform_id filter`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody(firmwareListJson))

            val result = RommApi.fetchFirmwareList(client, baseUrl(), platformId = 7)

            assertThat(result).isInstanceOf(FirmwareListResult.Success::class.java)
            assertThat((result as FirmwareListResult.Success).firmware).hasSize(1)
            val recorded = server.takeRequest()
            assertThat(recorded.path).isEqualTo("/api/firmware?platform_id=7")
        }

        @Test
        fun `401 classifies as AUTH_EXPIRED`() {
            server.enqueue(MockResponse().setResponseCode(401))

            val result = RommApi.fetchFirmwareList(client, baseUrl())

            assertThat((result as FirmwareListResult.Failure).error).isEqualTo(RommApiError.AUTH_EXPIRED)
        }
    }

    @Nested
    @DisplayName("content URL builders")
    inner class ContentUrls {
        @Test
        fun `rom content url encodes the file name and includes selected file ids`() {
            val url = RommApi.romContentUrl("https://romm.example.com", 42, "Super Game (USA).gb", fileIds = listOf(7L, 8L))

            assertThat(url).isEqualTo("https://romm.example.com/api/roms/42/content/Super%20Game%20(USA).gb?file_ids=7%2C8")
        }

        @Test
        fun `rom content url omits the query string when no file ids are given`() {
            val url = RommApi.romContentUrl("https://romm.example.com", 42, "game.gb")

            assertThat(url).isEqualTo("https://romm.example.com/api/roms/42/content/game.gb")
        }

        @Test
        fun `firmware content url is well formed`() {
            val url = RommApi.firmwareContentUrl("https://romm.example.com", 5, "bios.bin")

            assertThat(url).isEqualTo("https://romm.example.com/api/firmware/5/content/bios.bin")
        }
    }
}

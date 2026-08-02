package com.romm.androidtv.romm

import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.cache.CacheDatabase
import com.romm.androidtv.cache.ContentCache
import com.romm.androidtv.config.FakeSharedPreferences
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

@DisplayName("FirmwareRepositoryImpl — firmware availability and staging")
class FirmwareRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var cacheRoot: File
    private lateinit var sessionStore: SessionStore

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start(0)
        client = OkHttpClient.Builder().callTimeout(java.time.Duration.ofSeconds(5)).build()
        cacheRoot = Files.createTempDirectory("firmware-repository-test").toFile()
        sessionStore = SessionStore(FakeSharedPreferences())
        sessionStore.save(server.url("/").toString().removeSuffix("/"), "alice")
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        cacheRoot.deleteRecursively()
    }

    private fun newCache() = ContentCache(cacheRoot, CacheDatabase(File(cacheRoot, "index.json")))

    private fun repo(cache: ContentCache = newCache()) = FirmwareRepositoryImpl(client, sessionStore, cache)

    private val firmwareListJson = """
        [{"id": 5, "file_name": "bios.bin", "file_size_bytes": 4, "sha1_hash": "", "md5_hash": "", "crc_hash": ""}]
    """.trimIndent()

    @Nested
    @DisplayName("checkAvailability")
    inner class CheckAvailability {
        @Test
        fun `a file RomM does not know about is missing`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

            val result = runBlocking { repo().checkAvailability(platformId = 1, requiredFileNames = listOf("bios.bin")) }

            assertThat(result.missing).containsExactly("bios.bin")
            assertThat(result.isReady).isFalse()
        }

        @Test
        fun `a file RomM knows about but was never staged locally is missing`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody(firmwareListJson))

            val result = runBlocking { repo().checkAvailability(platformId = 1, requiredFileNames = listOf("bios.bin")) }

            assertThat(result.missing).containsExactly("bios.bin")
        }

        @Test
        fun `a file already staged and cached is present`() {
            val cache = newCache()
            server.enqueue(MockResponse().setResponseCode(200).setBody(firmwareListJson))
            server.enqueue(MockResponse().setResponseCode(200).setBody("bios"))
            val staged = runBlocking { repo(cache).ensureStaged(platformId = 1, requiredFileNames = listOf("bios.bin")) }
            assertThat(staged).isInstanceOf(FirmwareStagingOutcome.Success::class.java)

            server.enqueue(MockResponse().setResponseCode(200).setBody(firmwareListJson))
            val result = runBlocking { repo(cache).checkAvailability(platformId = 1, requiredFileNames = listOf("bios.bin")) }

            assertThat(result.present).containsExactly("bios.bin")
            assertThat(result.isReady).isTrue()
        }

        @Test
        fun `no session on record reports everything missing without a network call`() {
            val result = runBlocking {
                FirmwareRepositoryImpl(client, SessionStore(FakeSharedPreferences()), newCache())
                    .checkAvailability(1, listOf("bios.bin"))
            }

            assertThat(result.missing).containsExactly("bios.bin")
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Nested
    @DisplayName("ensureStaged")
    inner class EnsureStaged {
        @Test
        fun `downloads and verifies a firmware file not yet cached`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody(firmwareListJson))
            server.enqueue(MockResponse().setResponseCode(200).setBody("bios"))

            val outcome = runBlocking { repo().ensureStaged(1, listOf("bios.bin")) }

            assertThat(outcome).isInstanceOf(FirmwareStagingOutcome.Success::class.java)
            val path = (outcome as FirmwareStagingOutcome.Success).stagedPaths.getValue("bios.bin")
            assertThat(File(path)).exists().hasContent("bios")
        }

        @Test
        fun `reuses a cached firmware file without a second content download`() {
            val cache = newCache()
            server.enqueue(MockResponse().setResponseCode(200).setBody(firmwareListJson))
            server.enqueue(MockResponse().setResponseCode(200).setBody("bios"))
            val first = runBlocking { repo(cache).ensureStaged(1, listOf("bios.bin")) }
            assertThat(first).isInstanceOf(FirmwareStagingOutcome.Success::class.java)

            server.enqueue(MockResponse().setResponseCode(200).setBody(firmwareListJson))
            val second = runBlocking { repo(cache).ensureStaged(1, listOf("bios.bin")) }

            assertThat(second).isInstanceOf(FirmwareStagingOutcome.Success::class.java)
            assertThat(server.requestCount).isEqualTo(3) // 2 list calls + exactly 1 content download
        }

        @Test
        fun `firmware cache is isolated by account and server identity`() {
            val cache = newCache()
            server.enqueue(MockResponse().setResponseCode(200).setBody(firmwareListJson))
            server.enqueue(MockResponse().setResponseCode(200).setBody("bios"))
            assertThat(runBlocking { repo(cache).ensureStaged(1, listOf("bios.bin")) })
                .isInstanceOf(FirmwareStagingOutcome.Success::class.java)

            sessionStore.save(server.url("/").toString().removeSuffix("/"), "bob")
            server.enqueue(MockResponse().setResponseCode(200).setBody(firmwareListJson))
            server.enqueue(MockResponse().setResponseCode(200).setBody("bios"))
            assertThat(runBlocking { repo(cache).ensureStaged(1, listOf("bios.bin")) })
                .isInstanceOf(FirmwareStagingOutcome.Success::class.java)

            val alternateHostOrigin = server.url("/").toString()
                .replace("localhost", "127.0.0.1")
                .removeSuffix("/")
            sessionStore.save(alternateHostOrigin, "alice")
            server.enqueue(MockResponse().setResponseCode(200).setBody(firmwareListJson))
            server.enqueue(MockResponse().setResponseCode(200).setBody("bios"))
            assertThat(runBlocking { repo(cache).ensureStaged(1, listOf("bios.bin")) })
                .isInstanceOf(FirmwareStagingOutcome.Success::class.java)

            assertThat(server.requestCount).isEqualTo(6)
        }

        @Test
        fun `a required file RomM does not have is reported as Missing`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

            val outcome = runBlocking { repo().ensureStaged(1, listOf("missing.bin")) }

            assertThat(outcome).isInstanceOf(FirmwareStagingOutcome.Missing::class.java)
            assertThat((outcome as FirmwareStagingOutcome.Missing).fileNames).containsExactly("missing.bin")
        }

        @Test
        fun `expired auth surfaces distinctly`() {
            server.enqueue(MockResponse().setResponseCode(401))

            val outcome = runBlocking { repo().ensureStaged(1, listOf("bios.bin")) }

            assertThat(outcome).isEqualTo(FirmwareStagingOutcome.AuthExpired)
        }

        @Test
        fun `a hash mismatch during download is reported as CorruptedDownload for that file`() {
            val badHashJson = """
                [{"id": 5, "file_name": "bios.bin", "file_size_bytes": 4, "sha1_hash": "deadbeef0000", "md5_hash": "", "crc_hash": ""}]
            """.trimIndent()
            server.enqueue(MockResponse().setResponseCode(200).setBody(badHashJson))
            server.enqueue(MockResponse().setResponseCode(200).setBody("bios"))

            val outcome = runBlocking { repo().ensureStaged(1, listOf("bios.bin")) }

            assertThat(outcome).isInstanceOf(FirmwareStagingOutcome.CorruptedDownload::class.java)
            assertThat((outcome as FirmwareStagingOutcome.CorruptedDownload).fileName).isEqualTo("bios.bin")
        }
    }
}

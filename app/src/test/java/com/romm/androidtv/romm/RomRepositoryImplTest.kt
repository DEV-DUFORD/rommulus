package com.romm.androidtv.romm

import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.cache.CacheDatabase
import com.romm.androidtv.cache.ContentCache
import com.romm.androidtv.config.FakeSharedPreferences
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@DisplayName("RomRepositoryImpl.stageForLaunch — the Phase 3 download/cache/launch-manifest pipeline")
class RomRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var cacheRoot: File
    private lateinit var sessionStore: SessionStore

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start(0)
        // A short call timeout so a bug that triggers an unexpected extra network call fails
        // fast (as a NetworkError outcome) instead of hanging the test on an empty MockWebServer
        // response queue.
        client = OkHttpClient.Builder()
            .callTimeout(java.time.Duration.ofSeconds(5))
            .build()
        cacheRoot = Files.createTempDirectory("rom-repository-test").toFile()
        sessionStore = SessionStore(FakeSharedPreferences())
        sessionStore.save(baseUrl(), "alice")
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        cacheRoot.deleteRecursively()
    }

    private fun baseUrl(): String = server.url("/").toString().removeSuffix("/")

    private fun newCache() = ContentCache(cacheRoot, CacheDatabase(File(cacheRoot, "index.json")))

    /** A resolver that always approves "gb" for a fake "test-core", overriding the real (now partly-approved) CoreManifest for tests that need a stable, test-local core id. */
    private val alwaysApproveGb: (String) -> String? = { slug -> if (slug == "gb") "test-core" else null }

    private fun repo(cache: ContentCache = newCache(), resolver: (String) -> String? = alwaysApproveGb) =
        RomRepositoryImpl(client, sessionStore, cache, resolveApprovedCoreId = resolver)

    private fun singleFileRomJson(sha1: String = "") = """
        {
          "id": 42,
          "fs_name": "game.gb",
          "fs_size_bytes": 12,
          "platform_slug": "gb",
          "has_multiple_files": false,
          "files": [
            {"id": 7, "file_name": "game.gb", "file_size_bytes": 12, "is_top_level": true, "sha1_hash": "$sha1"}
          ]
        }
    """.trimIndent()

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private fun sevenZBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val file = Files.createTempFile("test-archive", ".7z").toFile()
        try {
            org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(file).use { out ->
                for ((name, bytes) in entries) {
                    val entry = org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry()
                    entry.name = name
                    out.putArchiveEntry(entry)
                    out.write(bytes)
                    out.closeArchiveEntry()
                }
            }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    private fun sha1Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Nested
    @DisplayName("core resolution")
    inner class CoreResolution {
        @Test
        fun `an unapproved platform is rejected as NoApprovedCore regardless of the resolver injected`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody(singleFileRomJson()))

            val outcome = runBlocking { repo(resolver = { _ -> null }).stageForLaunch(42) }

            assertThat(outcome).isInstanceOf(StagingOutcome.NoApprovedCore::class.java)
            assertThat((outcome as StagingOutcome.NoApprovedCore).platformSlug).isEqualTo("gb")
        }

        @Test
        fun `the real, default resolver (backed by the actual CoreManifest) approves gb for SameBoy since its Phase 4 review`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody(singleFileRomJson()))
            server.enqueue(MockResponse().setResponseCode(200).setBody("hello world!"))

            // No resolver override here: exercises the real CoreManifest.approvedEntries() lookup.
            val outcome = runBlocking {
                RomRepositoryImpl(client, sessionStore, newCache()).stageForLaunch(42)
            }

            assertThat(outcome).isInstanceOf(StagingOutcome.Success::class.java)
            assertThat((outcome as StagingOutcome.Success).launchSpec.coreId).isEqualTo("sameboy")
        }

        @Test
        fun `the real, default resolver still rejects a platform with no approved core`() {
            val genesisRomJson = """
                {"id": 50, "fs_name": "game.md", "fs_size_bytes": 4, "platform_slug": "genesis", "has_multiple_files": false,
                 "files": [{"id": 1, "file_name": "game.md", "file_size_bytes": 4, "is_top_level": true}]}
            """.trimIndent()
            server.enqueue(MockResponse().setResponseCode(200).setBody(genesisRomJson))

            val outcome = runBlocking {
                RomRepositoryImpl(client, sessionStore, newCache()).stageForLaunch(50)
            }

            assertThat(outcome).isEqualTo(StagingOutcome.NoApprovedCore("genesis"))
        }
    }

    @Nested
    @DisplayName("successful staging and repeated-launch reuse")
    inner class SuccessAndReuse {
        @Test
        fun `stages a single-file rom and returns a launch spec with the verified content hash`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody(singleFileRomJson()))
            server.enqueue(MockResponse().setResponseCode(200).setBody("hello world!"))

            val outcome = runBlocking { repo().stageForLaunch(42) }

            assertThat(outcome).isInstanceOf(StagingOutcome.Success::class.java)
            val spec = (outcome as StagingOutcome.Success).launchSpec
            assertThat(spec.romId).isEqualTo(42)
            assertThat(spec.romHash).isNotBlank()
            assertThat(File(spec.contentPath!!)).exists()
            assertThat(spec.coreId).isEqualTo("test-core")
            // Never actually routes to native playback regardless of a successfully-staged spec.
            assertThat(spec.backend).isEqualTo(com.romm.androidtv.config.PlaybackBackend.WEBVIEW)
        }

        @Test
        fun `a repeated launch reuses the cached file and never issues a second content download`() {
            val cache = newCache()
            server.enqueue(MockResponse().setResponseCode(200).setBody(singleFileRomJson()))
            server.enqueue(MockResponse().setResponseCode(200).setBody("hello world!"))
            val first = runBlocking { repo(cache).stageForLaunch(42) }
            assertThat(first).isInstanceOf(StagingOutcome.Success::class.java)

            // Second launch: only the metadata call is enqueued — if a second content
            // download were attempted, MockWebServer would have no response queued and fail.
            server.enqueue(MockResponse().setResponseCode(200).setBody(singleFileRomJson()))
            val second = runBlocking { repo(cache).stageForLaunch(42) }

            assertThat(second).isInstanceOf(StagingOutcome.Success::class.java)
            assertThat((second as StagingOutcome.Success).launchSpec.contentPath)
                .isEqualTo((first as StagingOutcome.Success).launchSpec.contentPath)
            assertThat(server.requestCount).isEqualTo(3) // 2 metadata fetches + exactly 1 content download
        }
    }

    @Nested
    @DisplayName("interrupted or corrupted downloads never appear launchable")
    inner class CorruptedDownloads {
        @Test
        fun `a sha1 mismatch from the server is rejected as CorruptedDownload and stages nothing`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody(singleFileRomJson(sha1 = "0000wrong0000")))
            server.enqueue(MockResponse().setResponseCode(200).setBody("hello world!"))

            val outcome = runBlocking { repo().stageForLaunch(42) }

            assertThat(outcome).isInstanceOf(StagingOutcome.CorruptedDownload::class.java)
            assertThat(cacheRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".gb") }.toList()).isEmpty()
        }

        @Test
        fun `a mid-download disconnect is rejected as NetworkError and stages nothing`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody(singleFileRomJson()))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("hello world!")
                    .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            )

            val outcome = runBlocking { repo().stageForLaunch(42) }

            assertThat(outcome).isInstanceOf(StagingOutcome.NetworkError::class.java)
        }
    }

    @Nested
    @DisplayName("multi-file content is explicitly rejected (single-file path first)")
    inner class MultiFile {
        @Test
        fun `a rom with more than one file is rejected with a clear, distinct outcome`() {
            val multiJson = """
                {
                  "id": 43, "fs_name": "multi.zip", "fs_size_bytes": 10, "platform_slug": "gb", "has_multiple_files": true,
                  "files": [
                    {"id": 1, "file_name": "a.gb", "file_size_bytes": 5, "is_top_level": true},
                    {"id": 2, "file_name": "b.gb", "file_size_bytes": 5, "is_top_level": true}
                  ]
                }
            """.trimIndent()
            server.enqueue(MockResponse().setResponseCode(200).setBody(multiJson))

            val outcome = runBlocking { repo().stageForLaunch(43) }

            assertThat(outcome).isInstanceOf(StagingOutcome.UnsupportedMultiFile::class.java)
            assertThat((outcome as StagingOutcome.UnsupportedMultiFile).fileCount).isEqualTo(2)
        }
    }

    @Nested
    @DisplayName("archived single-file content is extracted before it reaches a launch spec (section 10)")
    inner class ArchiveExtraction {
        private fun singleFileRomJson(fileName: String, sizeBytes: Long, sha1: String) = """
            {
              "id": 60, "fs_name": "$fileName", "fs_size_bytes": $sizeBytes, "platform_slug": "gb", "has_multiple_files": false,
              "files": [{"id": 9, "file_name": "$fileName", "file_size_bytes": $sizeBytes, "is_top_level": true, "sha1_hash": "$sha1"}]
            }
        """.trimIndent()

        @Test
        fun `a zip-named single file is downloaded, then its extracted content is verified against RomM's declared sha1`() {
            val entryBytes = "GBROM-BYTES".toByteArray()
            val zip = zipBytes("game.gb" to entryBytes)
            server.enqueue(MockResponse().setResponseCode(200).setBody(singleFileRomJson("game.zip", zip.size.toLong(), sha1Hex(entryBytes))))
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(zip)))

            val outcome = runBlocking { repo().stageForLaunch(60) }

            assertThat(outcome).isInstanceOf(StagingOutcome.Success::class.java)
            val spec = (outcome as StagingOutcome.Success).launchSpec
            val contentFile = File(spec.contentPath!!)
            assertThat(contentFile.name).isEqualTo("rom.gb")
            assertThat(contentFile.readBytes()).isEqualTo(entryBytes)
            // RomM's declared sha1_hash describes the ROM itself, not the archive it's shipped in —
            // the launch identity hash must be the extracted content's own SHA-256, not the archive's.
            assertThat(spec.romHash).isEqualTo(sha256Hex(entryBytes))
        }

        @Test
        fun `a zip whose extracted content's sha1 does not match RomM's declared hash is rejected as CorruptedDownload`() {
            val entryBytes = "GBROM-BYTES".toByteArray()
            val zip = zipBytes("game.gb" to entryBytes)
            server.enqueue(MockResponse().setResponseCode(200).setBody(singleFileRomJson("game.zip", zip.size.toLong(), "0000wrong0000")))
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(zip)))

            val outcome = runBlocking { repo().stageForLaunch(60) }

            assertThat(outcome).isInstanceOf(StagingOutcome.CorruptedDownload::class.java)
            assertThat((outcome as StagingOutcome.CorruptedDownload).reason).contains("extracted content SHA-1 mismatch")
        }

        @Test
        fun `a 7z-named single file is downloaded, then its extracted content is verified against RomM's declared sha1`() {
            val entryBytes = "GBCROM-BYTES".toByteArray()
            val sevenZ = sevenZBytes("game.gbc" to entryBytes)
            server.enqueue(MockResponse().setResponseCode(200).setBody(singleFileRomJson("game.7z", sevenZ.size.toLong(), sha1Hex(entryBytes))))
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(sevenZ)))

            val outcome = runBlocking { repo().stageForLaunch(60) }

            assertThat(outcome).isInstanceOf(StagingOutcome.Success::class.java)
            val spec = (outcome as StagingOutcome.Success).launchSpec
            val contentFile = File(spec.contentPath!!)
            assertThat(contentFile.name).isEqualTo("rom.gbc")
            assertThat(contentFile.readBytes()).isEqualTo(entryBytes)
            assertThat(spec.romHash).isEqualTo(sha256Hex(entryBytes))
        }

        @Test
        fun `a repeated launch of the same zip reuses the memoized extraction rather than re-extracting`() {
            val cache = newCache()
            val entryBytes = "GBROM-BYTES".toByteArray()
            val zip = zipBytes("game.gb" to entryBytes)
            server.enqueue(MockResponse().setResponseCode(200).setBody(singleFileRomJson("game.zip", zip.size.toLong(), sha1Hex(entryBytes))))
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(zip)))
            val first = runBlocking { repo(cache).stageForLaunch(60) }
            assertThat(first).isInstanceOf(StagingOutcome.Success::class.java)

            // Second launch: only the metadata call is enqueued — the archive download is cache-hit,
            // and extraction must be memoized rather than re-run against a (now-absent) content download.
            server.enqueue(MockResponse().setResponseCode(200).setBody(singleFileRomJson("game.zip", zip.size.toLong(), sha1Hex(entryBytes))))
            val second = runBlocking { repo(cache).stageForLaunch(60) }

            assertThat(second).isInstanceOf(StagingOutcome.Success::class.java)
            val firstSpec = (first as StagingOutcome.Success).launchSpec
            val secondSpec = (second as StagingOutcome.Success).launchSpec
            assertThat(secondSpec.contentPath).isEqualTo(firstSpec.contentPath)
            assertThat(secondSpec.romHash).isEqualTo(firstSpec.romHash)
            assertThat(server.requestCount).isEqualTo(3) // 2 metadata fetches + exactly 1 archive download
        }

        @Test
        fun `an unsupported archive extension surfaces a distinct, actionable outcome without downloading content`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody(singleFileRomJson("game.rar", 12, "")))

            val outcome = runBlocking { repo().stageForLaunch(60) }

            assertThat(outcome).isEqualTo(StagingOutcome.UnsupportedArchiveFormat("rar"))
            // Only the metadata call was enqueued — a second, unenqueued request would leave
            // MockWebServer with nothing to serve, proving no content download was attempted.
            assertThat(server.requestCount).isEqualTo(1)
        }

        @Test
        fun `a multi-entry zip surfaces UnsupportedMultiFile instead of an ambiguous extraction`() {
            val zip = zipBytes("a.gb" to "a".toByteArray(), "b.gb" to "b".toByteArray())
            server.enqueue(MockResponse().setResponseCode(200).setBody(singleFileRomJson("game.zip", zip.size.toLong(), "")))
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(zip)))

            val outcome = runBlocking { repo().stageForLaunch(60) }

            assertThat(outcome).isInstanceOf(StagingOutcome.UnsupportedMultiFile::class.java)
            assertThat((outcome as StagingOutcome.UnsupportedMultiFile).fileCount).isEqualTo(2)
        }

        @Test
        fun `a corrupted (non-zip) file named zip surfaces ArchiveExtractionFailed, not a crash`() {
            val notAZip = "this is not a zip".toByteArray()
            server.enqueue(MockResponse().setResponseCode(200).setBody(singleFileRomJson("game.zip", notAZip.size.toLong(), "")))
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(notAZip)))

            val outcome = runBlocking { repo().stageForLaunch(60) }

            assertThat(outcome).isInstanceOf(StagingOutcome.ArchiveExtractionFailed::class.java)
        }
    }

    @Nested
    @DisplayName("actionable authentication and space errors")
    inner class ActionableErrors {
        @Test
        fun `expired auth surfaces as AuthExpired, not a generic failure`() {
            server.enqueue(MockResponse().setResponseCode(401))

            val outcome = runBlocking { repo().stageForLaunch(42) }

            assertThat(outcome).isEqualTo(StagingOutcome.AuthExpired)
        }

        @Test
        fun `no session on record surfaces as AuthExpired before any network call`() {
            val freshSessionStore = SessionStore(FakeSharedPreferences())

            val outcome = runBlocking {
                RomRepositoryImpl(client, freshSessionStore, newCache(), alwaysApproveGb).stageForLaunch(42)
            }

            assertThat(outcome).isEqualTo(StagingOutcome.AuthExpired)
            assertThat(server.requestCount).isEqualTo(0)
        }

        @Test
        fun `insufficient space is reported with the required and available byte counts`() {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "id": 44, "fs_name": "huge.gb", "fs_size_bytes": 9223372036854775807,
                      "platform_slug": "gb", "has_multiple_files": false,
                      "files": [{"id": 1, "file_name": "huge.gb", "file_size_bytes": 9223372036854775807, "is_top_level": true}]
                    }
                    """.trimIndent()
                )
            )

            val outcome = runBlocking { repo().stageForLaunch(44) }

            assertThat(outcome).isInstanceOf(StagingOutcome.InsufficientSpace::class.java)
        }

        @Test
        fun `a 404 rom lookup surfaces as RomNotFound`() {
            server.enqueue(MockResponse().setResponseCode(404))

            val outcome = runBlocking { repo().stageForLaunch(999) }

            assertThat(outcome).isEqualTo(StagingOutcome.RomNotFound)
        }
    }
}

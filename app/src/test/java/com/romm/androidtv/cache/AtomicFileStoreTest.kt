package com.romm.androidtv.cache

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

@DisplayName("AtomicFileStore — streamed download, hashing, atomic install")
class AtomicFileStoreTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var destinationDir: File

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start(0)
        client = OkHttpClient.Builder().build()
        destinationDir = Files.createTempDirectory("atomic-file-store-test").toFile()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        destinationDir.deleteRecursively()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    @Nested
    @DisplayName("successful downloads")
    inner class Success {
        @Test
        fun `streams body to the final file and computes the requested digest`() {
            val content = "hello world".toByteArray()
            server.enqueue(MockResponse().setResponseCode(200).setBody(String(content)))

            val outcome = AtomicFileStore.download(
                AtomicFileStore.DownloadRequest(
                    client = client,
                    url = server.url("/x").toString(),
                    destinationDir = destinationDir,
                    finalFileName = "final.bin",
                )
            )

            assertThat(outcome).isInstanceOf(AtomicFileStore.DownloadOutcome.Success::class.java)
            val success = outcome as AtomicFileStore.DownloadOutcome.Success
            assertThat(success.file.readBytes()).isEqualTo(content)
            assertThat(success.sizeBytes).isEqualTo(content.size.toLong())
            assertThat(success.digests[AtomicFileStore.SHA256]).isEqualTo(sha256(content))
            // No leftover temp file after a successful install.
            assertThat(File(destinationDir, "final.bin${AtomicFileStore.TEMP_SUFFIX}")).doesNotExist()
        }

        @Test
        fun `matching expected digest passes verification`() {
            val content = "verify me".toByteArray()
            server.enqueue(MockResponse().setResponseCode(200).setBody(String(content)))

            val outcome = AtomicFileStore.download(
                AtomicFileStore.DownloadRequest(
                    client = client,
                    url = server.url("/x").toString(),
                    destinationDir = destinationDir,
                    finalFileName = "final.bin",
                    expectedDigests = mapOf(AtomicFileStore.SHA1 to sha1(content)),
                )
            )

            assertThat(outcome).isInstanceOf(AtomicFileStore.DownloadOutcome.Success::class.java)
        }

        @Test
        fun `matching expected size passes verification`() {
            val content = "exact size".toByteArray()
            server.enqueue(MockResponse().setResponseCode(200).setBody(String(content)))

            val outcome = AtomicFileStore.download(
                AtomicFileStore.DownloadRequest(
                    client = client,
                    url = server.url("/x").toString(),
                    destinationDir = destinationDir,
                    finalFileName = "final.bin",
                    expectedSizeBytes = content.size.toLong(),
                )
            )

            assertThat(outcome).isInstanceOf(AtomicFileStore.DownloadOutcome.Success::class.java)
        }
    }

    @Nested
    @DisplayName("interrupted or corrupted downloads never appear launchable")
    inner class Corruption {
        @Test
        fun `hash mismatch is rejected and leaves no file under the final name`() {
            val content = "corrupted content".toByteArray()
            server.enqueue(MockResponse().setResponseCode(200).setBody(String(content)))

            val outcome = AtomicFileStore.download(
                AtomicFileStore.DownloadRequest(
                    client = client,
                    url = server.url("/x").toString(),
                    destinationDir = destinationDir,
                    finalFileName = "final.bin",
                    expectedDigests = mapOf(AtomicFileStore.SHA1 to "0000000000000000000000000000000000000000"),
                )
            )

            assertThat(outcome).isInstanceOf(AtomicFileStore.DownloadOutcome.HashMismatch::class.java)
            assertThat(File(destinationDir, "final.bin")).doesNotExist()
            assertThat(File(destinationDir, "final.bin${AtomicFileStore.TEMP_SUFFIX}")).doesNotExist()
        }

        @Test
        fun `wrong expected size is rejected and leaves no file under the final name`() {
            val content = "some bytes".toByteArray()
            server.enqueue(MockResponse().setResponseCode(200).setBody(String(content)))

            val outcome = AtomicFileStore.download(
                AtomicFileStore.DownloadRequest(
                    client = client,
                    url = server.url("/x").toString(),
                    destinationDir = destinationDir,
                    finalFileName = "final.bin",
                    expectedSizeBytes = (content.size + 5).toLong(),
                )
            )

            assertThat(outcome).isInstanceOf(AtomicFileStore.DownloadOutcome.SizeMismatch::class.java)
            assertThat(File(destinationDir, "final.bin")).doesNotExist()
            assertThat(File(destinationDir, "final.bin${AtomicFileStore.TEMP_SUFFIX}")).doesNotExist()
        }

        @Test
        fun `a mid-stream network failure is reported and leaves no file under the final name`() {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("partial")
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            )

            val outcome = AtomicFileStore.download(
                AtomicFileStore.DownloadRequest(
                    client = client,
                    url = server.url("/x").toString(),
                    destinationDir = destinationDir,
                    finalFileName = "final.bin",
                )
            )

            assertThat(outcome).isInstanceOf(AtomicFileStore.DownloadOutcome.NetworkError::class.java)
            assertThat(File(destinationDir, "final.bin")).doesNotExist()
            assertThat(File(destinationDir, "final.bin${AtomicFileStore.TEMP_SUFFIX}")).doesNotExist()
        }

        @Test
        fun `a non-2xx response is reported as HttpError with no file left behind`() {
            server.enqueue(MockResponse().setResponseCode(404))

            val outcome = AtomicFileStore.download(
                AtomicFileStore.DownloadRequest(
                    client = client,
                    url = server.url("/x").toString(),
                    destinationDir = destinationDir,
                    finalFileName = "final.bin",
                )
            )

            assertThat(outcome).isInstanceOf(AtomicFileStore.DownloadOutcome.HttpError::class.java)
            assertThat((outcome as AtomicFileStore.DownloadOutcome.HttpError).code).isEqualTo(404)
            assertThat(File(destinationDir, "final.bin")).doesNotExist()
        }

        @Test
        fun `a declared content-length over the cap is rejected before streaming the body`() {
            val content = "x".repeat(1000)
            server.enqueue(MockResponse().setResponseCode(200).setBody(content))

            val outcome = AtomicFileStore.download(
                AtomicFileStore.DownloadRequest(
                    client = client,
                    url = server.url("/x").toString(),
                    destinationDir = destinationDir,
                    finalFileName = "final.bin",
                    maxExpectedSizeBytes = 10,
                )
            )

            assertThat(outcome).isInstanceOf(AtomicFileStore.DownloadOutcome.SizeMismatch::class.java)
            assertThat(File(destinationDir, "final.bin")).doesNotExist()
        }
    }

    @Nested
    @DisplayName("insufficient space")
    inner class Space {
        @Test
        fun `an absurdly large expected size with no content-length header is rejected as insufficient space`() {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setChunkedBody("small chunked body", 4)
            )

            val outcome = AtomicFileStore.download(
                AtomicFileStore.DownloadRequest(
                    client = client,
                    url = server.url("/x").toString(),
                    destinationDir = destinationDir,
                    finalFileName = "final.bin",
                    expectedSizeBytes = Long.MAX_VALUE / 2,
                )
            )

            assertThat(outcome).isInstanceOf(AtomicFileStore.DownloadOutcome.InsufficientSpace::class.java)
        }

        @Test
        fun `hasSufficientSpace is true for a tiny requirement on a real filesystem`() {
            assertThat(AtomicFileStore.hasSufficientSpace(destinationDir, 16)).isTrue()
        }

        @Test
        fun `hasSufficientSpace is false for an impossibly large requirement`() {
            assertThat(AtomicFileStore.hasSufficientSpace(destinationDir, Long.MAX_VALUE / 2)).isFalse()
        }
    }

    @Nested
    @DisplayName("orphan temp-file recovery")
    inner class OrphanSweep {
        @Test
        fun `sweeps part files left behind by a killed process, in nested directories`() {
            File(destinationDir, "a.bin${AtomicFileStore.TEMP_SUFFIX}").writeText("stale")
            val nested = File(destinationDir, "nested").apply { mkdirs() }
            File(nested, "b.bin${AtomicFileStore.TEMP_SUFFIX}").writeText("stale")
            val realFile = File(destinationDir, "keep-me.bin").apply { writeText("keep") }

            val deleted = AtomicFileStore.sweepOrphanTempFiles(destinationDir)

            assertThat(deleted).isEqualTo(2)
            assertThat(realFile).exists()
            assertThat(File(destinationDir, "a.bin${AtomicFileStore.TEMP_SUFFIX}")).doesNotExist()
            assertThat(File(nested, "b.bin${AtomicFileStore.TEMP_SUFFIX}")).doesNotExist()
        }

        @Test
        fun `sweeping a directory with nothing to sweep is a no-op`() {
            File(destinationDir, "keep-me.bin").writeText("keep")

            assertThat(AtomicFileStore.sweepOrphanTempFiles(destinationDir)).isEqualTo(0)
        }

        @Test
        fun `sweeping a nonexistent root returns zero rather than throwing`() {
            val missing = File(destinationDir, "does-not-exist")

            assertThat(AtomicFileStore.sweepOrphanTempFiles(missing)).isEqualTo(0)
        }
    }
}

package com.romm.desktop.player

import com.romm.androidtv.emulation.model.sha256Hex
import com.romm.androidtv.romm.RommApi
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests [OkHttpRomContentStager] against a stubbed HTTP layer: an OkHttp interceptor answers with
 * canned responses and records requests — no real network, and no MockWebServer dependency (not in
 * desktop's test deps; this seam keeps the real client + URL-building + file plumbing exercised).
 */
@DisplayName("OkHttpRomContentStager — real-content staging")
class RomContentStagerTest {

    private companion object {
        const val ORIGIN = "https://romm.example.com"
        val ROM_BYTES = "rom-bytes-0123456789".toByteArray()
    }

    /** Records every request and answers with canned responses (never touches the network). */
    private class StubHttp(private val responder: (Request) -> Response) {
        val requests = mutableListOf<Request>()

        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                requests += request
                responder(request)
            })
            .build()

        companion object {
            fun response(url: String, code: Int = 200, body: ByteArray = ROM_BYTES): Response =
                Response.Builder()
                    .request(Request.Builder().url(url).build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code in 200..299) "OK" else "stub error")
                    .body(body.toResponseBody("application/octet-stream".toMediaType()))
                    .build()
        }
    }

    private fun stager(http: StubHttp, cacheRoot: Path) = OkHttpRomContentStager(
        client = http.client,
        originProvider = { ORIGIN },
        romCacheDir = cacheRoot.resolve("roms"),
    )

    @Test
    fun `stage downloads the ROM to the roms cache dir and returns its sha256`(@TempDir dir: Path) {
        val http = StubHttp { req -> StubHttp.response(req.url.toString()) }

        val staged = stager(http, dir).stage(romId = 7L, fileName = "zelda.gb", expectedSizeBytes = ROM_BYTES.size.toLong())

        // Exactly one GET against the exact RomM content URL.
        assertThat(http.requests).hasSize(1)
        assertThat(http.requests.single().url.toString()).isEqualTo(RommApi.romContentUrl(ORIGIN, 7L, "zelda.gb"))
        // File written under <cacheRoot>/roms/<fileName> with the served bytes and matching hash.
        val expected = dir.resolve("roms").resolve("zelda.gb")
        assertThat(staged.path).isEqualTo(expected)
        assertThat(Files.readAllBytes(staged.path)).containsExactly(*ROM_BYTES)
        assertThat(staged.sha256).isEqualTo(sha256Hex(ROM_BYTES))
    }

    @Test
    fun `stage extracts the single core-supported ROM from a 7z download`(@TempDir dir: Path) {
        val archivedRom = byteArrayOf(1, 2, 3, 4, 5)
        val archivePath = dir.resolve("fixture.7z")
        SevenZOutputFile(archivePath.toFile()).use { archive ->
            val entry = SevenZArchiveEntry().apply {
                name = "Kirby's Dream Land (USA, Europe).gb"
                size = archivedRom.size.toLong()
            }

            @Test
            fun `stage extracts the single core-supported ROM from a ZIP download`(@TempDir dir: Path) {
                val archivedRom = byteArrayOf(6, 7, 8, 9)
                val archivePath = dir.resolve("fixture.zip")
                ZipOutputStream(Files.newOutputStream(archivePath)).use { archive ->
                    archive.putNextEntry(ZipEntry("Pokemon Red.gb"))
                    archive.write(archivedRom)
                    archive.closeEntry()
                }
                val archiveBytes = Files.readAllBytes(archivePath)
                val http = StubHttp { req -> StubHttp.response(req.url.toString(), body = archiveBytes) }

                val staged = stager(http, dir).stage(
                    romId = 8L,
                    fileName = "Pokemon Red",
                    expectedSizeBytes = archiveBytes.size.toLong(),
                    supportedExtensions = setOf(".gb", ".gbc"),
                )

                assertThat(staged.path.fileName.toString()).endsWith(".gb")
                assertThat(Files.readAllBytes(staged.path)).containsExactly(*archivedRom)
                assertThat(staged.sha256).isEqualTo(sha256Hex(archivedRom))
            }
            archive.putArchiveEntry(entry)
            archive.write(archivedRom)
            archive.closeArchiveEntry()
        }
        val archiveBytes = Files.readAllBytes(archivePath)
        val http = StubHttp { req -> StubHttp.response(req.url.toString(), body = archiveBytes) }

        val staged = stager(http, dir).stage(
            romId = 7L,
            fileName = "Kirby's Dream Land (USA, Europe)",
            expectedSizeBytes = archiveBytes.size.toLong(),
            supportedExtensions = setOf(".gb", ".gbc"),
        )

        assertThat(staged.path.fileName.toString()).endsWith(".gb")
        assertThat(Files.readAllBytes(staged.path)).containsExactly(*archivedRom)
        assertThat(staged.sha256).isEqualTo(sha256Hex(archivedRom))
    }

    @Test
    fun `stage rejects a 7z download with multiple supported ROMs`(@TempDir dir: Path) {
        val archivePath = dir.resolve("fixture.7z")
        SevenZOutputFile(archivePath.toFile()).use { archive ->
            listOf("one.gb", "two.gbc").forEach { name ->
                val entry = SevenZArchiveEntry().apply {
                    this.name = name
                    size = 1
                }
                archive.putArchiveEntry(entry)
                archive.write(byteArrayOf(1))
                archive.closeArchiveEntry()
            }
        }
        val archiveBytes = Files.readAllBytes(archivePath)
        val http = StubHttp { req -> StubHttp.response(req.url.toString(), body = archiveBytes) }

        assertThatThrownBy {
            stager(http, dir).stage(7L, "game", archiveBytes.size.toLong(), setOf(".gb", ".gbc"))
        }
            .isInstanceOf(RomContentStagingException::class.java)
            .hasMessageContaining("exactly one")
    }

    @Test
    fun `stage reuses the cached file when its size matches and does not hit the network`(@TempDir dir: Path) {
        val roms = dir.resolve("roms")
        Files.createDirectories(roms)
        val existing = "existing-bytes".toByteArray()
        Files.write(roms.resolve("zelda.gb"), existing)

        val http = StubHttp { req -> StubHttp.response(req.url.toString()) }

        val staged = stager(http, dir).stage(7L, "zelda.gb", expectedSizeBytes = existing.size.toLong())

        assertThat(http.requests).isEmpty()
        assertThat(staged.sha256).isEqualTo(sha256Hex(existing))
    }

    @Test
    fun `stage reuses the cached file when the expected size is unknown`(@TempDir dir: Path) {
        val roms = dir.resolve("roms")
        Files.createDirectories(roms)
        val existing = "existing-bytes".toByteArray()
        Files.write(roms.resolve("zelda.gb"), existing)

        val http = StubHttp { req -> StubHttp.response(req.url.toString()) }

        val staged = stager(http, dir).stage(7L, "zelda.gb", expectedSizeBytes = 0L)

        assertThat(http.requests).isEmpty()
        assertThat(staged.sha256).isEqualTo(sha256Hex(existing))
    }

    @Test
    fun `stage re-downloads when the cached file size does not match`(@TempDir dir: Path) {
        val roms = dir.resolve("roms")
        Files.createDirectories(roms)
        Files.write(roms.resolve("zelda.gb"), "short".toByteArray())

        val http = StubHttp { req -> StubHttp.response(req.url.toString()) }

        val staged = stager(http, dir).stage(7L, "zelda.gb", expectedSizeBytes = ROM_BYTES.size.toLong())

        assertThat(http.requests).hasSize(1)
        assertThat(Files.readAllBytes(staged.path)).containsExactly(*ROM_BYTES)
        assertThat(staged.sha256).isEqualTo(sha256Hex(ROM_BYTES))
    }

    @Test
    fun `stage fails closed on an HTTP error and leaves no destination or part file`(@TempDir dir: Path) {
        val http = StubHttp { req -> StubHttp.response(req.url.toString(), code = 500) }

        assertThatThrownBy { stager(http, dir).stage(7L, "zelda.gb", expectedSizeBytes = ROM_BYTES.size.toLong()) }
            .isInstanceOf(RomContentStagingException::class.java)
            .hasMessageContaining("HTTP 500")

        val roms = dir.resolve("roms")
        assertThat(Files.exists(roms.resolve("zelda.gb"))).isFalse()
        if (Files.exists(roms)) {
            assertThat(Files.list(roms).count()).isZero() // no leftover .part file
        }
    }

    @Test
    fun `stage fails closed on an empty body`(@TempDir dir: Path) {
        val http = StubHttp { req -> StubHttp.response(req.url.toString(), body = ByteArray(0)) }

        assertThatThrownBy { stager(http, dir).stage(7L, "zelda.gb", expectedSizeBytes = 0L) }
            .isInstanceOf(RomContentStagingException::class.java)
            .hasMessageContaining("empty")

        assertThat(Files.exists(dir.resolve("roms").resolve("zelda.gb"))).isFalse()
    }

    @Test
    fun `stage fails closed when the served size does not match the expected size`(@TempDir dir: Path) {
        val http = StubHttp { req -> StubHttp.response(req.url.toString(), body = "short".toByteArray()) }

        assertThatThrownBy { stager(http, dir).stage(7L, "zelda.gb", expectedSizeBytes = ROM_BYTES.size.toLong()) }
            .isInstanceOf(RomContentStagingException::class.java)
            .hasMessageContaining("size mismatch")

        assertThat(Files.exists(dir.resolve("roms").resolve("zelda.gb"))).isFalse()
    }

    @Test
    fun `stage rejects a blank file name without touching the network`(@TempDir dir: Path) {
        val http = StubHttp { req -> StubHttp.response(req.url.toString()) }

        assertThatThrownBy { stager(http, dir).stage(7L, "  ", expectedSizeBytes = ROM_BYTES.size.toLong()) }
            .isInstanceOf(RomContentStagingException::class.java)
            .hasMessageContaining("blank")

        assertThat(http.requests).isEmpty()
    }
}

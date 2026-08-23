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

    private fun stager(http: StubHttp, cacheRoot: Path, origin: String = ORIGIN) = OkHttpRomContentStager(
        client = http.client,
        originProvider = { origin },
        romCacheDir = cacheRoot.resolve("roms"),
    )

    /** Mirrors [OkHttpRomContentStager]'s origin-key sanitization (non-alphanumerics → `_`). */
    private fun originKey(origin: String) = origin.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

    /** The per-ROM cache directory: `roms/<origin-key>/<romId>`. */
    private fun romDir(dir: Path, romId: Long, origin: String = ORIGIN) =
        dir.resolve("roms").resolve(originKey(origin)).resolve(romId.toString())

    @Test
    fun `stage downloads the ROM to the roms cache dir and returns its sha256`(@TempDir dir: Path) {
        val http = StubHttp { req -> StubHttp.response(req.url.toString()) }

        val staged = stager(http, dir).stage(romId = 7L, fileName = "zelda.gb", expectedSizeBytes = ROM_BYTES.size.toLong())

        // Exactly one GET against the exact RomM content URL.
        assertThat(http.requests).hasSize(1)
        assertThat(http.requests.single().url.toString()).isEqualTo(RommApi.romContentUrl(ORIGIN, 7L, "zelda.gb"))
        // File written under <cacheRoot>/roms/<origin-key>/<romId>/<fileName> with the served bytes.
        val expected = romDir(dir, 7L).resolve("zelda.gb")
        assertThat(staged.path).isEqualTo(expected)
        assertThat(Files.readAllBytes(staged.path)).containsExactly(*ROM_BYTES)
        assertThat(staged.sha256).isEqualTo(sha256Hex(ROM_BYTES))
    }

    @Test
    fun `stage adds a CHD suffix when RomM metadata omits it`(@TempDir dir: Path) {
        val chd = "MComprHD".toByteArray() + ByteArray(64)
        val http = StubHttp { req -> StubHttp.response(req.url.toString(), body = chd) }

        val staged = stager(http, dir).stage(
            romId = 7L,
            fileName = "Sonic CD (USA)",
            expectedSizeBytes = chd.size.toLong(),
            supportedExtensions = setOf(".bin", ".chd"),
        )

        assertThat(staged.path.fileName.toString()).isEqualTo("Sonic CD (USA).chd")
        assertThat(Files.readAllBytes(staged.path)).containsExactly(*chd)
        assertThat(Files.readAllBytes(romDir(dir, 7L).resolve("Sonic CD (USA)"))).containsExactly(*chd)
    }

    @Test
    fun `stage rejects a chd-named file without the MComprHD signature and leaves no cache behind`(@TempDir dir: Path) {
        val notChd = "definitely-not-a-chd".toByteArray()
        val http = StubHttp { req -> StubHttp.response(req.url.toString(), body = notChd) }

        assertThatThrownBy {
            stager(http, dir).stage(7L, "Sonic CD (USA).chd", notChd.size.toLong(), setOf(".bin", ".chd"))
        }
            .isInstanceOf(RomContentStagingException::class.java)
            .hasMessageContaining("not a valid CHD file")
            .matches { it is RomContentStagingException && it.failure == RomContentStagingFailure.InvalidChdSignature }

        // Fail closed: no destination or part file is left behind.
        val romsDir = romDir(dir, 7L)
        assertThat(Files.exists(romsDir.resolve("Sonic CD (USA).chd"))).isFalse()
        if (Files.exists(romsDir)) {
            assertThat(Files.list(romsDir).count()).isZero() // no leftover .part file
        }
    }

    @Test
    fun `stage rejects a cached chd file without the MComprHD signature on reuse`(@TempDir dir: Path) {
        val notChd = "cached-but-malformed".toByteArray()
        Files.createDirectories(romDir(dir, 7L))
        Files.write(romDir(dir, 7L).resolve("Sonic CD (USA).chd"), notChd)

        val http = StubHttp { req -> StubHttp.response(req.url.toString(), body = notChd) }

        assertThatThrownBy {
            stager(http, dir).stage(7L, "Sonic CD (USA).chd", notChd.size.toLong(), setOf(".bin", ".chd"))
        }
            .isInstanceOf(RomContentStagingException::class.java)
            .matches { it is RomContentStagingException && it.failure == RomContentStagingFailure.InvalidChdSignature }

        // The size-matching cache file is not re-downloaded (the server would serve the same bytes)…
        assertThat(http.requests).isEmpty()
        // …but the poisoned entry is discarded, so a fixed ROM re-uploaded to RomM fetches fresh.
        assertThat(Files.exists(romDir(dir, 7L).resolve("Sonic CD (USA).chd"))).isFalse()
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

    @Test
    fun `archive limits allow optical disc images larger than the cartridge cap`() {
        val cartridge = archiveExtractionLimitsFor(".gb")
        val gameCube = archiveExtractionLimitsFor(".iso")
        val playStation = archiveExtractionLimitsFor(".bin")
        val compressedDisc = archiveExtractionLimitsFor(".rvz")

        assertThat(cartridge.maxBytes).isEqualTo(512L * 1024 * 1024)
        assertThat(gameCube.maxBytes).isGreaterThan(1_459_978_240L)
        assertThat(playStation.maxBytes).isEqualTo(gameCube.maxBytes)
        assertThat(compressedDisc.maxBytes).isEqualTo(gameCube.maxBytes)
        assertThat(gameCube.maxCompressionRatio).isGreaterThan(cartridge.maxCompressionRatio)
    }

    @Test
    fun `stage rejects a ZIP entry with a CHD name without the MComprHD signature and leaves no staged CHD behind`(@TempDir dir: Path) {
        val notChd = "definitely-not-a-chd".toByteArray()
        val archivePath = dir.resolve("fixture.zip")
        ZipOutputStream(Files.newOutputStream(archivePath)).use { archive ->
            archive.putNextEntry(ZipEntry("Sonic CD (USA).chd"))
            archive.write(notChd)
            archive.closeEntry()
        }
        val archiveBytes = Files.readAllBytes(archivePath)
        val http = StubHttp { req -> StubHttp.response(req.url.toString(), body = archiveBytes) }

        assertThatThrownBy {
            stager(http, dir).stage(7L, "Sonic CD (USA)", archiveBytes.size.toLong(), setOf(".bin", ".chd"))
        }
            .isInstanceOf(RomContentStagingException::class.java)
            .hasMessageContaining("not a valid CHD file")
            .matches { it is RomContentStagingException && it.failure == RomContentStagingFailure.InvalidChdSignature }

        // Fail closed: no staged .chd and no leftover extraction part remain in the ROM cache.
        val romsDir = romDir(dir, 7L)
        assertThat(Files.exists(romsDir.resolve("Sonic CD (USA).chd"))).isFalse()
        if (Files.exists(romsDir)) {
            assertThat(Files.list(romsDir).count()).isZero() // no leftover .extract.part file
        }
    }

    @Test
    fun `stage rejects a ZIP entry with a path traversal name and writes no file outside the cache`(@TempDir dir: Path) {
        val archivePath = dir.resolve("fixture.zip")
        ZipOutputStream(Files.newOutputStream(archivePath)).use { archive ->
            archive.putNextEntry(ZipEntry("../evil.gb"))
            archive.write(byteArrayOf(1, 2, 3))
            archive.closeEntry()
        }
        val archiveBytes = Files.readAllBytes(archivePath)
        val http = StubHttp { req -> StubHttp.response(req.url.toString(), body = archiveBytes) }

        assertThatThrownBy {
            stager(http, dir).stage(9L, "game", archiveBytes.size.toLong(), setOf(".gb"))
        }
            .isInstanceOf(RomContentStagingException::class.java)
            .hasMessageContaining("unsafe entry path")

        // Fail closed: nothing may be written outside the ROM cache root, and no extracted file or
        // leftover extraction part may appear inside it (the per-ROM subdirectories included).
        assertThat(Files.exists(dir.resolve("evil.gb"))).isFalse()
        val roms = dir.resolve("roms")
        if (Files.exists(roms)) {
            val names = Files.walk(roms).use { stream ->
                stream.filter { Files.isRegularFile(it) }.map { it.fileName.toString() }.toList()
            }
            assertThat(names.none { it.endsWith(".extract.part") }).isTrue()
            assertThat(names).doesNotContain("evil.gb")
        }
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
        val existing = "existing-bytes".toByteArray()
        Files.createDirectories(romDir(dir, 7L))
        Files.write(romDir(dir, 7L).resolve("zelda.gb"), existing)

        val http = StubHttp { req -> StubHttp.response(req.url.toString()) }

        val staged = stager(http, dir).stage(7L, "zelda.gb", expectedSizeBytes = existing.size.toLong())

        assertThat(http.requests).isEmpty()
        assertThat(staged.sha256).isEqualTo(sha256Hex(existing))
    }

    @Test
    fun `stage reuses the cached file when the expected size is unknown`(@TempDir dir: Path) {
        val existing = "existing-bytes".toByteArray()
        Files.createDirectories(romDir(dir, 7L))
        Files.write(romDir(dir, 7L).resolve("zelda.gb"), existing)

        val http = StubHttp { req -> StubHttp.response(req.url.toString()) }

        val staged = stager(http, dir).stage(7L, "zelda.gb", expectedSizeBytes = 0L)

        assertThat(http.requests).isEmpty()
        assertThat(staged.sha256).isEqualTo(sha256Hex(existing))
    }

    @Test
    fun `stage re-downloads when the cached file size does not match`(@TempDir dir: Path) {
        Files.createDirectories(romDir(dir, 7L))
        Files.write(romDir(dir, 7L).resolve("zelda.gb"), "short".toByteArray())

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

        val romsDir = romDir(dir, 7L)
        assertThat(Files.exists(romsDir.resolve("zelda.gb"))).isFalse()
        if (Files.exists(romsDir)) {
            assertThat(Files.list(romsDir).count()).isZero() // no leftover .part file
        }
    }

    @Test
    fun `stage fails closed on an empty body`(@TempDir dir: Path) {
        val http = StubHttp { req -> StubHttp.response(req.url.toString(), body = ByteArray(0)) }

        assertThatThrownBy { stager(http, dir).stage(7L, "zelda.gb", expectedSizeBytes = 0L) }
            .isInstanceOf(RomContentStagingException::class.java)
            .hasMessageContaining("empty")

        assertThat(Files.exists(romDir(dir, 7L).resolve("zelda.gb"))).isFalse()
    }

    @Test
    fun `stage fails closed when the served size does not match the expected size`(@TempDir dir: Path) {
        val http = StubHttp { req -> StubHttp.response(req.url.toString(), body = "short".toByteArray()) }

        assertThatThrownBy { stager(http, dir).stage(7L, "zelda.gb", expectedSizeBytes = ROM_BYTES.size.toLong()) }
            .isInstanceOf(RomContentStagingException::class.java)
            .hasMessageContaining("size mismatch")
            .matches { it is RomContentStagingException && it.failure == RomContentStagingFailure.SizeMismatch }

        assertThat(Files.exists(romDir(dir, 7L).resolve("zelda.gb"))).isFalse()
    }

    @Test
    fun `stage rejects a blank file name without touching the network`(@TempDir dir: Path) {
        val http = StubHttp { req -> StubHttp.response(req.url.toString()) }

        assertThatThrownBy { stager(http, dir).stage(7L, "  ", expectedSizeBytes = ROM_BYTES.size.toLong()) }
            .isInstanceOf(RomContentStagingException::class.java)
            .hasMessageContaining("blank")

        assertThat(http.requests).isEmpty()
    }

    // ---------------------------------------------------------------- cache identity (Phase 11 work item 5)

    @Test
    fun `CHD staging is stable across launches and reuses the cache without a network round trip`(@TempDir dir: Path) {
        val chd = "MComprHD".toByteArray() + ByteArray(64)
        val httpFirst = StubHttp { req -> StubHttp.response(req.url.toString(), body = chd) }
        val first = stager(httpFirst, dir).stage(7L, "Sonic CD (USA)", chd.size.toLong(), setOf(".bin", ".chd"))

        // A second launch: a fresh stager instance over the same cache root must not re-download.
        val httpSecond = StubHttp { req -> StubHttp.response(req.url.toString(), body = chd) }
        val second = stager(httpSecond, dir).stage(7L, "Sonic CD (USA)", chd.size.toLong(), setOf(".bin", ".chd"))

        assertThat(httpSecond.requests).isEmpty()
        // Same ROM → same staged path and same content hash across launches.
        assertThat(second.path).isEqualTo(first.path)
        assertThat(second.sha256).isEqualTo(first.sha256)
        // The .chd sibling is stable and holds the served bytes.
        val romsDir = romDir(dir, 7L)
        assertThat(Files.isRegularFile(romsDir.resolve("Sonic CD (USA).chd"))).isTrue()
        assertThat(Files.readAllBytes(second.path)).containsExactly(*chd)
    }

    @Test
    fun `different roms sharing a file name stage to isolated paths and never reuse each other's bytes`(@TempDir dir: Path) {
        // Same file name, same size, different content — the size-only reuse gate cannot tell them
        // apart, so the per-ROM cache directory is what keeps the identities apart (CHD included).
        val rom7Bytes = "MComprHD".toByteArray() + ByteArray(32) { 1 }
        val rom8Bytes = "MComprHD".toByteArray() + ByteArray(32) { 2 }
        val http = StubHttp { req ->
            if (req.url.toString().contains("/api/roms/7/content/")) {
                StubHttp.response(req.url.toString(), body = rom7Bytes)
            } else {
                StubHttp.response(req.url.toString(), body = rom8Bytes)
            }
        }

        val first = stager(http, dir).stage(7L, "Sonic CD (USA)", rom7Bytes.size.toLong(), setOf(".bin", ".chd"))
        val second = stager(http, dir).stage(8L, "Sonic CD (USA)", rom8Bytes.size.toLong(), setOf(".bin", ".chd"))

        assertThat(second.path).isNotEqualTo(first.path)
        assertThat(first.path).isEqualTo(romDir(dir, 7L).resolve("Sonic CD (USA).chd"))
        assertThat(second.path).isEqualTo(romDir(dir, 8L).resolve("Sonic CD (USA).chd"))
        assertThat(Files.readAllBytes(first.path)).containsExactly(*rom7Bytes)
        assertThat(Files.readAllBytes(second.path)).containsExactly(*rom8Bytes)
    }

    @Test
    fun `the same rom id on different server origins stages to isolated paths`(@TempDir dir: Path) {
        val otherOrigin = "https://other.example.com"
        val bytesA = "MComprHD".toByteArray() + ByteArray(16) { 1 }
        val bytesB = "MComprHD".toByteArray() + ByteArray(16) { 2 }
        val httpA = StubHttp { req -> StubHttp.response(req.url.toString(), body = bytesA) }
        val httpB = StubHttp { req -> StubHttp.response(req.url.toString(), body = bytesB) }

        val a = stager(httpA, dir, origin = ORIGIN).stage(7L, "Sonic CD (USA)", bytesA.size.toLong(), setOf(".bin", ".chd"))
        val b = stager(httpB, dir, origin = otherOrigin).stage(7L, "Sonic CD (USA)", bytesB.size.toLong(), setOf(".bin", ".chd"))

        assertThat(a.path).isEqualTo(romDir(dir, 7L, ORIGIN).resolve("Sonic CD (USA).chd"))
        assertThat(b.path).isEqualTo(romDir(dir, 7L, otherOrigin).resolve("Sonic CD (USA).chd"))
        assertThat(b.path).isNotEqualTo(a.path)
        assertThat(Files.readAllBytes(a.path)).containsExactly(*bytesA)
        assertThat(Files.readAllBytes(b.path)).containsExactly(*bytesB)
    }
}

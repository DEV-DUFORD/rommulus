package com.romm.desktop.player

import com.romm.androidtv.emulation.model.sha256Hex
import com.romm.androidtv.romm.RommApi
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import okio.source
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
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

            /**
             * Serves [file] as a streaming response body — GB-class fixtures must never be read
             * whole into the test JVM's heap.
             */
            fun streamingResponse(url: String, file: Path): Response =
                Response.Builder()
                    .request(Request.Builder().url(url).build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        ResponseBody.create(
                            "application/octet-stream".toMediaType(),
                            Files.size(file),
                            file.toFile().source().buffer(),
                        ),
                    )
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
    fun `game downloads have no whole-call deadline`() {
        val shared = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()

        val download = gameDownloadClient(shared)

        assertThat(download.callTimeoutMillis).isZero()
        assertThat(download.connectTimeoutMillis).isEqualTo(10_000)
        assertThat(download.readTimeoutMillis).isEqualTo(30_000)
    }

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
        // The disc-image ceiling is 5 GiB: it must cover a full-capacity DVD-5
        // (4,700,372,992 bytes) — e.g. the ~4.1 GiB "Kingdom Hearts - Re-Chain of
        // Memories (USA)" PS2 ISO — while staying bounded.
        assertThat(gameCube.maxBytes).isEqualTo(5L * 1024 * 1024 * 1024)
        assertThat(gameCube.maxBytes).isGreaterThanOrEqualTo(4_700_372_992L)
        assertThat(playStation.maxBytes).isEqualTo(gameCube.maxBytes)
        assertThat(compressedDisc.maxBytes).isEqualTo(gameCube.maxBytes)
        assertThat(gameCube.maxCompressionRatio).isGreaterThan(cartridge.maxCompressionRatio)
    }

    @Test
    fun `stage accepts a disc image between the old 4 GiB ceiling and the new cap`(@TempDir dir: Path) {
        // The ~4.1 GiB "Kingdom Hearts - Re-Chain of Memories (USA)" PS2 ISO was rejected by the
        // old 4 GiB ceiling; the 5 GiB cap must stage it. Fixture: a STORED (uncompressed,
        // ratio 1) ZIP entry just above the old ceiling, served from disk (never read whole into
        // the test JVM's heap).
        val isoBytes = 4L * 1024 * 1024 * 1024 + 1
        val archivePath = dir.resolve("fixture.zip")
        writeStoredZip(archivePath, "Kingdom Hearts - Re-Chain of Memories (USA).iso", isoBytes, isoBytes)
        val http = StubHttp { req -> StubHttp.streamingResponse(req.url.toString(), archivePath) }

        val staged = stager(http, dir).stage(
            romId = 27304L,
            fileName = "Kingdom Hearts - Re-Chain of Memories (USA)",
            expectedSizeBytes = Files.size(archivePath),
            supportedExtensions = setOf(".iso"),
        )

        assertThat(staged.path.fileName.toString()).isEqualTo("Kingdom Hearts - Re-Chain of Memories (USA).iso")
        assertThat(Files.size(staged.path)).isEqualTo(isoBytes)
    }

    @Test
    fun `stage rejects a disc image above the new 5 GiB cap`(@TempDir dir: Path) {
        // The central directory declares a 5 GiB + 1 entry (zip64); the archive itself is tiny —
        // the pre-extraction size gate must reject it from the declared size alone, fail-closed.
        val declared = 5L * 1024 * 1024 * 1024 + 1
        val archivePath = dir.resolve("fixture.zip")
        writeStoredZip(archivePath, "too-big.iso", declared, actualSize = 3)
        val archiveBytes = Files.readAllBytes(archivePath)
        val http = StubHttp { req -> StubHttp.response(req.url.toString(), body = archiveBytes) }

        assertThatThrownBy {
            stager(http, dir).stage(7L, "game", archiveBytes.size.toLong(), setOf(".iso"))
        }
            .isInstanceOf(RomContentStagingException::class.java)
            .hasMessageContaining("exceeds the extraction size limit of 5368709120 bytes")
            .matches { it is RomContentStagingException && it.failure == RomContentStagingFailure.UnsafeContent }

        assertThat(Files.exists(romDir(dir, 7L).resolve("game"))).isFalse()
    }

    @Test
    fun `stage rejects an archive entry whose compression ratio exceeds the limit`(@TempDir dir: Path) {
        // Cartridge limits: 512 MiB cap, ratio 200. A 4 MiB run of zeros deflates to a few KiB,
        // so the ratio gate (copied > compressedSize * 200) trips long before the 512 MiB size
        // cap would. A real DEFLATE entry is required: for STORED entries the JDK bounds the
        // entry stream by the declared compressed size, so the ratio gate could never observe
        // more copied bytes than compressed bytes.
        val archivePath = dir.resolve("fixture.zip")
        ZipOutputStream(Files.newOutputStream(archivePath)).use { archive ->
            archive.putNextEntry(ZipEntry("bomb.gb"))
            val zeros = ByteArray(1024 * 1024)
            repeat(4) { archive.write(zeros) }
            archive.closeEntry()
        }
        val archiveBytes = Files.readAllBytes(archivePath)
        val http = StubHttp { req -> StubHttp.response(req.url.toString(), body = archiveBytes) }

        assertThatThrownBy {
            stager(http, dir).stage(7L, "game", archiveBytes.size.toLong(), setOf(".gb"))
        }
            .isInstanceOf(RomContentStagingException::class.java)
            .hasMessageContaining("exceeds the compression ratio limit")
            .matches { it is RomContentStagingException && it.failure == RomContentStagingFailure.UnsafeContent }

        assertThat(Files.exists(romDir(dir, 7L).resolve("game"))).isFalse()
    }

    @Test
    fun `stage rejects an archive with more entries than the limit`(@TempDir dir: Path) {
        val archivePath = dir.resolve("fixture.zip")
        ZipOutputStream(Files.newOutputStream(archivePath)).use { archive ->
            repeat(4097) { i ->
                archive.putNextEntry(ZipEntry("entry-$i.txt"))
                archive.write(byteArrayOf(1))
                archive.closeEntry()
            }
        }
        val archiveBytes = Files.readAllBytes(archivePath)
        val http = StubHttp { req -> StubHttp.response(req.url.toString(), body = archiveBytes) }

        assertThatThrownBy {
            stager(http, dir).stage(7L, "game", archiveBytes.size.toLong(), setOf(".gb"))
        }
            .isInstanceOf(RomContentStagingException::class.java)
            .hasMessageContaining("has 4097 entries; limit is 4096")
            .matches { it is RomContentStagingException && it.failure == RomContentStagingFailure.UnsafeContent }
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
    fun `stage rejects a 7z download with multiple supported ROMs as multi-file content`(@TempDir dir: Path) {
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
            .hasMessageContaining("multiple files")
            .matches { it is RomContentStagingException && it.failure == RomContentStagingFailure.MultiFileContent }

        // Fail closed: the multi-file package is not usable, so no cache entry is kept.
        assertThat(Files.exists(romDir(dir, 7L).resolve("game"))).isFalse()
    }

    @Test
    fun `stage rejects a multi-disc ZIP package with a focused MultiFileContent failure`(@TempDir dir: Path) {
        // A two-disc game uploaded as one RomM folder: the server serves every file as a ZIP
        // (plus a synthesized .m3u when none exists). The archive is intact — the failure must
        // be "multiple files", not "corrupt".
        val archivePath = dir.resolve("fixture.zip")
        ZipOutputStream(Files.newOutputStream(archivePath)).use { archive ->
            archive.putNextEntry(ZipEntry("Kingdom Hearts Re Chain of Memories (USA) (Disc 1).iso"))
            archive.write(byteArrayOf(1, 1, 1))
            archive.closeEntry()
            archive.putNextEntry(ZipEntry("Kingdom Hearts Re Chain of Memories (USA) (Disc 2).iso"))
            archive.write(byteArrayOf(2, 2, 2))
            archive.closeEntry()
            archive.putNextEntry(ZipEntry("Kingdom Hearts Re Chain of Memories (USA).m3u"))
            archive.write("Disc 1.iso\nDisc 2.iso\n".toByteArray())
            archive.closeEntry()
        }
        val archiveBytes = Files.readAllBytes(archivePath)
        val http = StubHttp { req -> StubHttp.response(req.url.toString(), body = archiveBytes) }

        assertThatThrownBy {
            stager(http, dir).stage(
                romId = 7L,
                fileName = "Kingdom Hearts Re Chain of Memories (USA)",
                expectedSizeBytes = archiveBytes.size.toLong(),
                supportedExtensions = setOf(".iso", ".chd", ".cso"),
            )
        }
            .isInstanceOf(RomContentStagingException::class.java)
            .hasMessageContaining("multiple files")
            .matches { it is RomContentStagingException && it.failure == RomContentStagingFailure.MultiFileContent }

        // No cache entry is kept for the unusable package.
        assertThat(Files.exists(romDir(dir, 7L).resolve("Kingdom Hearts Re Chain of Memories (USA)"))).isFalse()
    }

    @Test
    fun `stage classifies a size-mismatched multi-file ZIP as MultiFileContent, not a corrupt download`(@TempDir dir: Path) {
        // RomM's declared size for a multi-file ROM is the SUM of its files; the served ZIP is
        // compressed, so the size gate fires first. The bytes must still be diagnosed as a
        // multi-file package (multiple playable files), not an "incomplete or corrupt" transfer.
        val archivePath = dir.resolve("fixture.zip")
        ZipOutputStream(Files.newOutputStream(archivePath)).use { archive ->
            archive.putNextEntry(ZipEntry("game (Disc 1).iso"))
            archive.write(ByteArray(64) { 1 })
            archive.closeEntry()
            archive.putNextEntry(ZipEntry("game (Disc 2).iso"))
            archive.write(ByteArray(64) { 2 })
            archive.closeEntry()
        }
        val archiveBytes = Files.readAllBytes(archivePath)
        // What the server reports as fs_size_bytes: the sum of the files' sizes, which can never
        // equal the compressed package's size (guaranteed mismatch, no coincidence).
        val declaredSum = 64L + 64L + 4096
        val http = StubHttp { req -> StubHttp.response(req.url.toString(), body = archiveBytes) }

        assertThatThrownBy {
            stager(http, dir).stage(7L, "game", declaredSum, setOf(".iso"))
        }
            .isInstanceOf(RomContentStagingException::class.java)
            .hasMessageContaining("multiple files")
            .matches { it is RomContentStagingException && it.failure == RomContentStagingFailure.MultiFileContent }

        assertThat(Files.exists(romDir(dir, 7L).resolve("game"))).isFalse()
    }

    @Test
    fun `stage extracts the sole playable file from a size-mismatched package with extra non-playable files`(@TempDir dir: Path) {
        // The single-disc case: one ROM image plus non-playable extras (e.g. a manual) in the
        // RomM folder, and the server-synthesized .m3u playlist. The declared size is the sum of
        // the members, which never equals the compressed package — but exactly ONE member is
        // playable for the core (.m3u is metadata, not content), so staging must succeed with
        // that file instead of rejecting the intact single-disc ROM.
        val isoBytes = ByteArray(128) { 0x45 }
        val manualBytes = "manual-bytes".toByteArray()
        val m3uBytes = "Kingdom Hearts Re Chain of Memories (USA).iso".toByteArray()
        val archivePath = dir.resolve("fixture.zip")
        ZipOutputStream(Files.newOutputStream(archivePath)).use { archive ->
            archive.putNextEntry(ZipEntry("Kingdom Hearts Re Chain of Memories (USA).iso"))
            archive.write(isoBytes)
            archive.closeEntry()
            archive.putNextEntry(ZipEntry("manual.pdf"))
            archive.write(manualBytes)
            archive.closeEntry()
            archive.putNextEntry(ZipEntry("Kingdom Hearts Re Chain of Memories (USA).m3u"))
            archive.write(m3uBytes)
            archive.closeEntry()
        }
        val archiveBytes = Files.readAllBytes(archivePath)
        // Declared size: the sum of the members' sizes (the server's fs_size_bytes), which the
        // compressed package can never equal.
        val declaredSum = isoBytes.size.toLong() + manualBytes.size + m3uBytes.size
        val http = StubHttp { req -> StubHttp.response(req.url.toString(), body = archiveBytes) }

        val staged = stager(http, dir).stage(
            romId = 7L,
            fileName = "Kingdom Hearts Re Chain of Memories (USA)",
            expectedSizeBytes = declaredSum,
            // .m3u is core-supported but must NOT count as a playable candidate.
            supportedExtensions = setOf(".iso", ".chd", ".cso", ".m3u"),
        )

        assertThat(staged.path.fileName.toString()).isEqualTo("Kingdom Hearts Re Chain of Memories (USA).iso")
        assertThat(Files.readAllBytes(staged.path)).containsExactly(*isoBytes)
        assertThat(staged.sha256).isEqualTo(sha256Hex(isoBytes))
    }

    @Test
    fun `stage rejects a package with no core-supported file as corrupt content`(@TempDir dir: Path) {
        // A multi-entry package whose members are all non-playable (e.g. only a manual and the
        // synthesized .m3u): there is no usable ROM in it — CorruptContent, not MultiFileContent.
        val archivePath = dir.resolve("fixture.zip")
        ZipOutputStream(Files.newOutputStream(archivePath)).use { archive ->
            archive.putNextEntry(ZipEntry("manual.pdf"))
            archive.write("pdf-bytes".toByteArray())
            archive.closeEntry()
            archive.putNextEntry(ZipEntry("game.m3u"))
            archive.write("track.bin\n".toByteArray())
            archive.closeEntry()
        }
        val archiveBytes = Files.readAllBytes(archivePath)
        val http = StubHttp { req -> StubHttp.response(req.url.toString(), body = archiveBytes) }

        assertThatThrownBy {
            stager(http, dir).stage(7L, "game", expectedSizeBytes = 999_999L, supportedExtensions = setOf(".iso", ".bin"))
        }
            .isInstanceOf(RomContentStagingException::class.java)
            .hasMessageContaining("no file supported")
            .matches { it is RomContentStagingException && it.failure == RomContentStagingFailure.CorruptContent }

        assertThat(Files.exists(romDir(dir, 7L).resolve("game"))).isFalse()
    }

    @Test
    fun `stage keeps SizeMismatch for a truncated archive whose size does not match`(@TempDir dir: Path) {
        // A truncated download of a single-file ZIP ROM: the PK header is present (so the
        // signature says "archive") but the central directory is gone (unreadable) — it must
        // stay a plain size mismatch, not be misdiagnosed as multi-file.
        val archivePath = dir.resolve("fixture.zip")
        ZipOutputStream(Files.newOutputStream(archivePath)).use { archive ->
            archive.putNextEntry(ZipEntry("Pokemon Red.gb"))
            archive.write(ByteArray(512) { 7 })
            archive.closeEntry()
        }
        val full = Files.readAllBytes(archivePath)
        val truncated = full.copyOf(16) // PK\x03\x04 header + a few bytes, no central directory
        val http = StubHttp { req -> StubHttp.response(req.url.toString(), body = truncated) }

        assertThatThrownBy {
            stager(http, dir).stage(7L, "Pokemon Red", full.size.toLong(), setOf(".gb"))
        }
            .isInstanceOf(RomContentStagingException::class.java)
            .hasMessageContaining("size mismatch")
            .matches { it is RomContentStagingException && it.failure == RomContentStagingFailure.SizeMismatch }
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

    // ---------------------------------------------------------------- archive fixtures

    /**
     * Streams a minimal single-entry STORED (uncompressed) ZIP to [archivePath]: the central
     * directory claims [declaredSize] uncompressed bytes (and [declaredCompressedSize] compressed
     * bytes when given) while the archive actually carries [actualSize] bytes of pattern data.
     * The stager reads entry sizes from the central directory, so this exercises the size / ratio
     * gates end to end — with only [actualSize] bytes on disk, and no GB-class heap allocation.
     *
     * CRCs are zero: the JDK's [java.util.zip.ZipFile] does not verify them on read, so a
     * zero-CRC stored fixture is fully readable — and a real CRC would need a pass over the
     * (GB-class) content plus a [ZipOutputStream] that refuses stored entries without one.
     *
     * Sizes above 4 GiB use a zip64 extra field: 32-bit fields set to the 0xFFFFFFFF sentinel,
     * 64-bit UNCOMPRESSED size first, then compressed (the PKWARE order the JDK expects), and
     * the field's declared length is 20 (id + size header + 16 data bytes).
     */
    private fun writeStoredZip(
        archivePath: Path,
        entryName: String,
        declaredSize: Long,
        actualSize: Long,
        declaredCompressedSize: Long? = null,
    ) {
        val name = entryName.toByteArray(Charsets.UTF_8)
        val compressed = declaredCompressedSize ?: actualSize
        val zip64 = declaredSize > 0xFFFFFFFFL || compressed > 0xFFFFFFFFL
        val pattern = ByteArray(1024 * 1024) { i -> (i % 251).toByte() }

        val counting = CountingOutputStream(Files.newOutputStream(archivePath))
        counting.use { out ->
            fun u16(v: Int) {
                out.write(v and 0xFF)
                out.write((v ushr 8) and 0xFF)
            }

            fun u32(v: Long) {
                repeat(4) { i -> out.write(((v ushr (8 * i)) and 0xFF).toInt()) }
            }

            fun u64(v: Long) {
                repeat(8) { i -> out.write(((v ushr (8 * i)) and 0xFF).toInt()) }
            }

            // Local file header. The JDK locates the data right after it (no local extra field);
            // the 32-bit size fields carry the zip64 sentinel when needed.
            u32(0x04034B50) // local file header signature
            u16(45)         // version needed to extract (zip64)
            u16(0)          // general purpose flags
            u16(0)          // method: stored
            u16(0)          // mod time
            u16(0)          // mod date
            u32(0)          // crc-32 (not verified on read)
            u32(if (zip64) -1L else actualSize)
            u32(if (zip64) -1L else actualSize)
            u16(name.size)
            u16(0)          // extra field length
            out.write(name)
            val centralDirectoryOffset = counting.position + actualSize

            var written = 0L
            while (written < actualSize) {
                val n = minOf(pattern.size.toLong(), actualSize - written).toInt()
                out.write(pattern, 0, n)
                written += n
            }

            // Central directory file header.
            u32(0x02014B50) // central directory signature
            u16(45)         // version made by
            u16(45)         // version needed to extract
            u16(0)          // general purpose flags
            u16(0)          // method: stored
            u16(0)          // mod time
            u16(0)          // mod date
            u32(0)          // crc-32
            u32(if (zip64) -1L else compressed)
            u32(if (zip64) -1L else declaredSize)
            u16(name.size)
            u16(if (zip64) 20 else 0) // extra field length
            u16(0)                    // comment length
            u16(0)                    // disk number start
            u16(0)                    // internal attributes
            u32(0)                    // external attributes
            u32(0)                    // local header offset
            out.write(name)
            if (zip64) {
                u16(0x0001) // zip64 extra field id
                u16(16)     // data length
                u64(declaredSize)
                u64(compressed)
            }
            val centralDirectorySize = counting.position - centralDirectoryOffset

            // End of central directory. When the CEN offset (or size) does not fit in 32 bits —
            // true for fixtures whose data itself exceeds 4 GiB — the EOCD carries the
            // 0xFFFFFFFF sentinels and the real values live in a zip64 EOCD record + locator
            // immediately before it (the layout the JDK's findEND expects).
            val zip64End = centralDirectoryOffset > 0xFFFFFFFFL || centralDirectorySize > 0xFFFFFFFFL
            if (zip64End) {
                val zip64EocdOffset = counting.position
                u32(0x06064B50) // zip64 end of central directory signature
                u64(44)         // size of the remaining record
                u16(45)         // version made by
                u16(45)         // version needed to extract
                u32(0)          // disk number
                u32(0)          // disk with central directory
                u64(1)          // entries on this disk
                u64(1)          // total entries
                u64(centralDirectorySize)
                u64(centralDirectoryOffset)
                u32(0x07064B50) // zip64 end of central directory locator signature
                u32(0)          // disk with zip64 EOCD
                u64(zip64EocdOffset) // offset of the zip64 EOCD record
                u32(1)          // total number of disks
            }
            u32(0x06054B50) // end of central directory signature
            u16(0)          // disk number
            u16(0)          // disk with central directory
            u16(if (zip64End) -1 else 1) // entries on this disk
            u16(if (zip64End) -1 else 1) // total entries
            u32(if (zip64End) -1L else centralDirectorySize)
            u32(if (zip64End) -1L else centralDirectoryOffset)
            u16(0)          // comment length
        }
    }
}

/** Tracks the bytes written so the fixture can address its central directory. */
private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
    var position: Long = 0
        private set

    override fun write(b: Int) {
        delegate.write(b)
        position++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        position += len
    }
}

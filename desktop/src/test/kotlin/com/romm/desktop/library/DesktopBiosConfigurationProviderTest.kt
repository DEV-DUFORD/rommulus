/*
 * Copyright (c) 2025 Romm Android TV contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.romm.desktop.library

import com.romm.androidtv.library.BiosConfigurationCatalog
import com.romm.androidtv.romm.FirmwareInfo
import com.romm.androidtv.romm.FirmwareStagingOutcome
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tests for [DesktopBiosConfigurationProvider] against a local in-JVM HTTP stub.
 *
 * NOTE: `:desktop` does not declare `mockwebserver` (and the module's build file is
 * pinned by task constraints), so the stub below uses the JDK's built-in
 * [HttpServer] instead of OkHttp's MockWebServer; it plays the exact same role:
 * a local, in-process HTTP server with per-test response configuration.
 */
@DisplayName("DesktopBiosConfigurationProvider")
class DesktopBiosConfigurationProviderTest {

    @TempDir
    lateinit var root: Path

    private val client = OkHttpClient()
    private lateinit var server: StubServer

    @BeforeEach
    fun startServer() {
        server = StubServer().apply { start() }
    }

    @AfterEach
    fun stopServer() {
        server.close()
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private val biosContents = "SEGA-CD-BIOS-FIXTURE-DATA".toByteArray()
    private val biosSha1 = sha1Hex(biosContents)
    private val otherContents = "DIFFERENT-BIOS-CONTENT".toByteArray()

    private fun firmware(
        id: Long = 7L,
        fileName: String = "bios_CD_Europe.bin",
        size: Long = biosContents.size.toLong(),
        sha1: String = biosSha1,
    ): FirmwareInfo = FirmwareInfo(
        firmwareId = id,
        fileName = fileName,
        sizeBytes = size,
        sha1Hash = sha1,
        md5Hash = "",
        crcHash = "",
        isVerified = true,
    )

    private val firmwareDir: Path get() = root.resolve("data").resolve("firmware")

    private fun provider(origin: String? = server.origin, slug: String = "sega_cd") =
        DesktopBiosConfigurationProvider(client, { origin }, firmwareDir, quietLogger(), slug)

    private fun quietLogger(): Logger {
        val logger = Logger.getLogger("desktop-bios-config-test")
        logger.useParentHandlers = false
        logger.level = Level.OFF
        return logger
    }

    private fun stagedDestination(fw: FirmwareInfo): Path =
        firmwareDir.resolve("${fw.firmwareId}_${fw.fileName}")

    private fun assertNoPartFiles() {
        if (!Files.isDirectory(firmwareDir)) return
        Files.list(firmwareDir).use { stream ->
            assertThat(stream.map { it.fileName.toString() }.toList()).noneMatch { it.endsWith(".part") }
        }
    }

    // ── fetchCatalog ─────────────────────────────────────────────────────────

    @Test
    fun `valid 200 catalog maps to Success with file-name options`() {
        server.platformsJson(7L, "sega_cd")
        server.firmwareJson(
            json(41L, "bios_CD_Europe.bin", biosContents.size.toLong(), biosSha1),
            json(42L, "bios_CD_USA.bin", biosSha1.length.toLong(), biosSha1),
        )

        val catalog = runBlocking { provider().fetchCatalog() } as BiosConfigurationCatalog.Success

        assertThat(catalog.options.map { it.displayName }).containsExactlyInAnyOrder(
            "bios_CD_Europe.bin", "bios_CD_USA.bin",
        )
        assertThat(catalog.options.map { it.firmware.firmwareId }).containsExactly(41L, 42L)
        assertThat(catalog.selectedFirmwareId).isNull()
        assertThat(server.lastFirmwarePath).isEqualTo("/api/firmware?platform_id=7")
    }

    @Test
    fun `401 on platform lookup maps to AuthExpired`() {
        server.platformsStatus = 401

        assertThat(runBlocking { provider().fetchCatalog() })
            .isEqualTo(BiosConfigurationCatalog.AuthExpired)
    }

    @Test
    fun `404 on firmware list maps to Error NOT_FOUND`() {
        server.platformsJson(7L, "sega_cd")
        server.firmwareStatus = 404

        val catalog = runBlocking { provider().fetchCatalog() } as BiosConfigurationCatalog.Error
        assertThat(catalog.message).isEqualTo("NOT_FOUND")
    }

    @Test
    fun `empty firmware list maps to Success(empty)`() {
        server.platformsJson(7L, "sega_cd")
        server.firmwareBody = "[]"

        val catalog = runBlocking { provider().fetchCatalog() } as BiosConfigurationCatalog.Success
        assertThat(catalog.options).isEmpty()
        assertThat(catalog.selectedFirmwareId).isNull()
    }

    @Test
    fun `malformed firmware JSON maps to Error PARSE_ERROR`() {
        server.platformsJson(7L, "sega_cd")
        server.firmwareBody = "{this is not a list"

        val catalog = runBlocking { provider().fetchCatalog() } as BiosConfigurationCatalog.Error
        assertThat(catalog.message).isEqualTo("PARSE_ERROR")
    }

    @Test
    fun `missing origin maps to Error ORIGIN_NOT_CONFIGURED`() {
        val catalog = runBlocking { provider(origin = null).fetchCatalog() } as BiosConfigurationCatalog.Error
        assertThat(catalog.message).isEqualTo("ORIGIN_NOT_CONFIGURED")
    }

    @Test
    fun `a previously staged file is reported as selected`() {
        server.platformsJson(7L, "sega_cd")
        server.firmwareJson(
            json(41L, "bios_CD_Europe.bin", biosContents.size.toLong(), biosSha1),
            json(42L, "bios_CD_USA.bin", 16L, biosSha1),
        )
        // Pre-stage the European BIOS (id 41); the USA one (id 42) has no file on disk.
        Files.createDirectories(firmwareDir)
        Files.write(stagedDestination(firmware(41L, "bios_CD_Europe.bin")), biosContents)

        val catalog = runBlocking { provider().fetchCatalog() } as BiosConfigurationCatalog.Success
        assertThat(catalog.selectedFirmwareId).isEqualTo(41L)
    }

    // ── select: success and replacement ──────────────────────────────────────

    @Test
    fun `successful download stages a verified file and returns Success`() {
        server.content(biosContents) // 200, matching declared size + sha1

        val outcome = runBlocking { provider().select(firmware()) } as FirmwareStagingOutcome.Success

        val expected = stagedDestination(firmware()).toAbsolutePath()
        assertThat(outcome.stagedPaths).containsEntry("bios_CD_Europe.bin", expected.toString())
        assertThat(Files.exists(firmwareDir)).isTrue()
        assertThat(Files.readAllBytes(expected)).isEqualTo(biosContents)
        assertNoPartFiles()
        assertThat(server.lastContentPath).isEqualTo("/api/firmware/7/content/bios_CD_Europe.bin")

        // §9 rule 4: user-only permissions on the firmware dir (0700) and file (0600).
        assumeTrue(Files.getFileAttributeView(firmwareDir, PosixFileAttributeView::class.java) != null)
        assertThat(Files.getPosixFilePermissions(firmwareDir))
            .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
        assertThat(Files.getPosixFilePermissions(expected))
            .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    }

    @Test
    fun `an existing staged file is atomically replaced on re-stage`() {
        Files.createDirectories(firmwareDir)
        val destination = stagedDestination(firmware())
        Files.write(destination, "OLD SENTINEL BYTES".toByteArray())
        server.content(biosContents)

        runBlocking { provider().select(firmware()) }

        assertThat(Files.readAllBytes(destination)).isEqualTo(biosContents)
        assertNoPartFiles()
    }

    // ── select: failure mapping ──────────────────────────────────────────────

    @Test
    fun `SHA-1 mismatch maps to CorruptedDownload and cleans up the part file`() {
        // Serve the wrong bytes (size still matches) with an unmatchable declared hash.
        server.content(otherContents)

        val outcome = runBlocking {
            provider().select(
                firmware(size = otherContents.size.toLong(), sha1 = "0".repeat(40)),
            )
        }

        assertThat(outcome).isEqualTo(
            FirmwareStagingOutcome.CorruptedDownload("bios_CD_Europe.bin", "SHA-1 mismatch"),
        )
        assertNoPartFiles()
        assertThat(Files.exists(stagedDestination(firmware()))).isFalse()
    }

    @Test
    fun `size mismatch maps to CorruptedDownload`() {
        server.content(biosContents) // 17 bytes, but the catalog declares 1000.

        val outcome = runBlocking { provider().select(firmware(size = 1000L, sha1 = "")) }

        assertThat(outcome).isEqualTo(
            FirmwareStagingOutcome.CorruptedDownload("bios_CD_Europe.bin", "size mismatch"),
        )
        assertNoPartFiles()
    }

    @Test
    fun `HTTP 401 on content maps to AuthExpired`() {
        server.content(ByteArray(0), 401)

        assertThat(runBlocking { provider().select(firmware()) })
            .isEqualTo(FirmwareStagingOutcome.AuthExpired)
    }

    @Test
    fun `HTTP 404 on content maps to NetworkError BIOS not found`() {
        server.content(ByteArray(0), 404)

        assertThat(runBlocking { provider().select(firmware()) })
            .isEqualTo(FirmwareStagingOutcome.NetworkError("BIOS not found on server"))
    }

    @Test
    fun `HTTP 500 on content maps to NetworkError with the status code`() {
        server.content(ByteArray(0), 503)

        assertThat(runBlocking { provider().select(firmware()) })
            .isEqualTo(FirmwareStagingOutcome.NetworkError("HTTP 503"))
    }

    @Test
    fun `missing origin maps to NetworkError without touching the server`() {
        val firmware = firmware()
        val outcome = runBlocking { provider(origin = null).select(firmware) }

        assertThat(outcome).isEqualTo(FirmwareStagingOutcome.NetworkError("RomM origin not configured"))
        assertThat(server.lastContentPath).isNull() // no download was attempted
    }

    @Test
    fun `a symlink at the destination maps to CorruptedDownload and is removed`() {
        Files.createDirectories(firmwareDir)
        val externals = root.resolve("externals")
        Files.createDirectories(externals)
        val target = externals.resolve("real-target.bin")
        Files.write(target, "do not touch".toByteArray())
        val destination = stagedDestination(firmware())
        Files.createSymbolicLink(destination, target)

        server.content(biosContents) // must never be consumed

        val outcome = runBlocking { provider().select(firmware()) }

        assertThat(outcome).isEqualTo(
            FirmwareStagingOutcome.CorruptedDownload("bios_CD_Europe.bin", "symlink rejected"),
        )
        assertThat(Files.isSymbolicLink(destination)).isFalse() // the trap link was removed
        assertThat(Files.readAllBytes(target)).isEqualTo("do not touch".toByteArray())
        assertNoPartFiles()
    }

    @Test
    fun `a path-escaping file name maps to CorruptedDownload without writing outside the firmware dir`() {
        val evil = firmware(fileName = "../evil.bin", size = 0L, sha1 = "")
        server.content(ByteArray(0))

        val outcome = runBlocking { provider().select(evil) }

        assertThat(outcome).isEqualTo(
            FirmwareStagingOutcome.CorruptedDownload("../evil.bin", "path escape detected"),
        )
        assertThat(Files.exists(root.resolve("evil.bin"))).isFalse()
        assertThat(Files.exists(firmwareDir)).isFalse() // nothing was written at all
    }

    // ── stub server ──────────────────────────────────────────────────────────

    /** Per-endpoint, per-test-response stub (see class KDoc for the MockWebServer note). */
    private class StubServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        @Volatile var platformsStatus = 200
        @Volatile var platformsBody = "[]"
        @Volatile var firmwareStatus = 200
        @Volatile var firmwareBody = "[]"
        @Volatile var contentStatus = 200
        @Volatile var contentBytes = ByteArray(0)

        @Volatile var lastFirmwarePath: String? = null
        @Volatile var lastContentPath: String? = null

        val origin: String
            get() = "http://127.0.0.1:" + (server.address as InetSocketAddress).port

        fun start() {
            server.createContext("/api/platforms") { exchange ->
                respond(exchange, platformsStatus, platformsBody, json = true)
            }
            server.createContext("/api/firmware") { exchange ->
                val path = exchange.requestURI.toString()
                if (path == "/api/firmware" || path.startsWith("/api/firmware?")) {
                    lastFirmwarePath = path
                    respond(exchange, firmwareStatus, firmwareBody, json = true)
                } else {
                    lastContentPath = path
                    respond(exchange, contentStatus, contentBytes)
                }
            }
            server.start()
        }

        /** Convenience: 200 `[{id, slug}]` platform-list body. */
        fun platformsJson(id: Long, slug: String) {
            platformsBody = """[{"id": $id, "slug": "$slug", "fs_slug": "$slug"}]"""
            platformsStatus = 200
        }

        fun firmwareJson(vararg entries: String) {
            firmwareBody = "[${entries.joinToString(",")}]"
            firmwareStatus = 200
        }

        fun content(bytes: ByteArray, status: Int = 200) {
            contentBytes = bytes
            contentStatus = status
        }

        private fun respond(exchange: HttpExchange, status: Int, body: Any, json: Boolean = false) {
            val bytes = if (body is ByteArray) body else body.toString().toByteArray()
            try {
                exchange.responseHeaders.add("Content-Type", if (json) "application/json" else "application/octet-stream")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } catch (_: IOException) {
                // Client went away mid-test; the assertion failure (if any) is what matters.
                exchange.close()
            }
        }

        override fun close() = server.stop(0)
    }

    private fun json(id: Long, fileName: String, size: Long, sha1: String) =
        """{"id": $id, "file_name": "$fileName", "file_size_bytes": $size, "sha1_hash": "$sha1", "is_verified": true}"""

    private fun sha1Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
}

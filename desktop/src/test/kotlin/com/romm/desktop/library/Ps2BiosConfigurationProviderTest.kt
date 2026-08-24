/*
 * Copyright (c) 2025 Romm Android TV contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.romm.desktop.library

import com.romm.androidtv.romm.FirmwareInfo
import com.romm.androidtv.romm.FirmwareStagingOutcome
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.logging.Level
import java.util.logging.Logger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * End-to-end tests for the PS2 BIOS flow through [DesktopBiosConfigurationProvider] against
 * the local [StubServer]: automatic US-region selection by `created_at`, ZIP payload
 * extraction, and placement of `bios_PS2.bin` under the system directory at launch time.
 */
@DisplayName("DesktopBiosConfigurationProvider — PS2")
class Ps2BiosConfigurationProviderTest {

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

    private val firmwareDir: Path get() = root.resolve("data").resolve("firmware")
    private val systemDir: Path get() = root.resolve("data").resolve("system")

    private fun provider(origin: String? = server.origin) =
        DesktopBiosConfigurationProvider(client, { origin }, firmwareDir, quietLogger(), "ps2")

    private fun quietLogger(): Logger {
        val logger = Logger.getLogger("desktop-ps2-bios-test")
        logger.useParentHandlers = false
        logger.level = Level.OFF
        return logger
    }

    private fun stagedDestination(fw: FirmwareInfo): Path =
        firmwareDir.resolve("${fw.firmwareId}_${fw.fileName}")

    // 600 KiB of deterministic bytes — inside the PS2 payload guard (512 KiB .. 16 MiB).
    private val biosBytes = ByteArray(600 * 1024) { it.toByte() }
    private val biosSha1 = sha1Hex(biosBytes)

    private fun firmware(
        id: Long,
        fileName: String,
        createdAt: String?,
    ): FirmwareInfo = FirmwareInfo(
        firmwareId = id,
        fileName = fileName,
        sizeBytes = biosBytes.size.toLong(),
        sha1Hash = biosSha1,
        md5Hash = "",
        crcHash = "",
        isVerified = true,
        createdAt = createdAt,
    )

    private fun json(id: Long, fileName: String, size: Long, sha1: String, createdAt: String?) =
        """{"id": $id, "file_name": "$fileName", "file_size_bytes": $size, "sha1_hash": "$sha1", "is_verified": true, "created_at": ${if (createdAt == null) "null" else "\"$createdAt\""}}"""

    private fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha1Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    // ── display ──────────────────────────────────────────────────────────────

    @Test
    fun `ps2 provider uses the friendly PlayStation 2 system name`() {
        assertThat(provider().title).isEqualTo("PlayStation 2 BIOS")
        assertThat(provider().emptyMessage).contains("PlayStation 2")
    }

    // ── (e) prepareForLaunch places bios_PS2.bin ─────────────────────────────

    @Test
    fun `launch preparation auto-selects the newest US BIOS and places the canonical file`() {
        server.platformsJson(11L, "ps2")
        server.firmwareJson(
            json(1L, "scph79001-old-us.bin", biosBytes.size.toLong(), biosSha1, "2024-01-01T00:00:00Z"),
            json(2L, "scph10000-newer-jp.bin", biosBytes.size.toLong(), biosSha1, "2025-06-01T00:00:00Z"),
            json(3L, "SCPH-70012 (USA).bin", biosBytes.size.toLong(), biosSha1, "2023-01-01T00:00:00Z"),
        )
        server.content(biosBytes)

        val outcome = runBlocking { provider().prepareForLaunch(systemDir) }

        // The newest US entry (id 1) wins over the newer Japanese entry (id 2).
        assertThat(server.lastContentPath).isEqualTo("/api/firmware/1/content/scph79001-old-us.bin")
        assertThat(outcome).isEqualTo(
            FirmwareStagingOutcome.Success(
                mapOf("bios_PS2.bin" to systemDir.resolve("bios_PS2.bin").toAbsolutePath().toString()),
            ),
        )
        assertThat(Files.readAllBytes(systemDir.resolve("bios_PS2.bin"))).isEqualTo(biosBytes)
    }

    @Test
    fun `launch preparation falls back to the newest non-US BIOS when no US candidate exists`() {
        server.platformsJson(11L, "ps2")
        server.firmwareJson(
            json(1L, "scph50001-eu-old.bin", biosBytes.size.toLong(), biosSha1, "2024-01-01T00:00:00Z"),
            json(2L, "scph10000-jp-newest.bin", biosBytes.size.toLong(), biosSha1, "2025-01-01T00:00:00Z"),
        )
        server.content(biosBytes)

        val outcome = runBlocking { provider().prepareForLaunch(systemDir) }

        assertThat(server.lastContentPath).isEqualTo("/api/firmware/2/content/scph10000-jp-newest.bin")
        assertThat(outcome).isInstanceOf(FirmwareStagingOutcome.Success::class.java)
        assertThat(Files.readAllBytes(systemDir.resolve("bios_PS2.bin"))).isEqualTo(biosBytes)
    }

    @Test
    fun `an explicit staged selection wins over the automatic ranking`() {
        val eu = firmware(2L, "scph50001-eu.bin", "2024-01-01T00:00:00Z")
        Files.createDirectories(firmwareDir)
        Files.write(stagedDestination(eu), biosBytes)
        server.platformsJson(11L, "ps2")
        server.firmwareJson(
            json(1L, "scph79001-us-newer.bin", biosBytes.size.toLong(), biosSha1, "2025-01-01T00:00:00Z"),
            json(2L, "scph50001-eu.bin", biosBytes.size.toLong(), biosSha1, "2024-01-01T00:00:00Z"),
        )
        // If the provider downloaded anything it would be the US file (id 1).
        server.content(biosBytes)

        val outcome = runBlocking { provider().prepareForLaunch(systemDir) }

        assertThat(outcome).isInstanceOf(FirmwareStagingOutcome.Success::class.java)
        assertThat(server.lastContentPath).isNull() // the staged EU file was used; no download
        assertThat(Files.readAllBytes(systemDir.resolve("bios_PS2.bin"))).isEqualTo(biosBytes)
    }

    @Test
    fun `a zip BIOS payload is extracted and placed under the canonical name`() {
        val payload = zipBytes(mapOf("readme.txt" to "see inside".toByteArray(), "SCPH-79001.BIN" to biosBytes))
        val payloadSha1 = sha1Hex(payload)
        server.platformsJson(11L, "ps2")
        server.firmwareJson(
            json(5L, "ps2-bios-usa.zip", payload.size.toLong(), payloadSha1, "2025-03-01T00:00:00Z"),
        )
        server.content(payload)

        val outcome = runBlocking { provider().prepareForLaunch(systemDir) }

        assertThat(server.lastContentPath).isEqualTo("/api/firmware/5/content/ps2-bios-usa.zip")
        assertThat(outcome).isInstanceOf(FirmwareStagingOutcome.Success::class.java)
        assertThat(Files.readAllBytes(systemDir.resolve("bios_PS2.bin"))).isEqualTo(biosBytes)
    }

    @Test
    fun `an empty catalog surfaces Missing with the canonical PS2 file name`() {
        server.platformsJson(11L, "ps2")
        server.firmwareBody = "[]"

        val outcome = runBlocking { provider().prepareForLaunch(systemDir) }

        assertThat(outcome).isEqualTo(FirmwareStagingOutcome.Missing(listOf("bios_PS2.bin")))
        assertThat(Files.exists(systemDir.resolve("bios_PS2.bin"))).isFalse()
    }
}

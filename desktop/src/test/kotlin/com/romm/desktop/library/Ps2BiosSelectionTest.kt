/*
 * Copyright (c) 2025 Romm Android TV contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.romm.desktop.library

import com.romm.androidtv.romm.FirmwareInfo
import com.romm.androidtv.romm.FirmwareStagingOutcome
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Unit tests for [Ps2BiosSelection]: US-region detection on raw file names,
 * `created_at`-based newest-first ranking (with null/unparseable handling and the
 * file-name tie-break), the full selection fallback chain, and ZIP extraction +
 * size-guard payload preparation.
 */
@DisplayName("Ps2BiosSelection")
class Ps2BiosSelectionTest {

    @TempDir
    lateinit var root: Path

    private fun firmware(
        id: Long,
        fileName: String,
        createdAt: String? = null,
    ): FirmwareInfo = FirmwareInfo(
        firmwareId = id,
        fileName = fileName,
        sizeBytes = 4L * 1024 * 1024,
        sha1Hash = "",
        md5Hash = "",
        crcHash = "",
        isVerified = false,
        createdAt = createdAt,
    )

    // ── (a) US-region detection ──────────────────────────────────────────────

    @Test
    fun `region tags are detected case-insensitively`() {
        assertThat(Ps2BiosSelection.isUsRegion("SCPH-79001 (USA).bin")).isTrue()
        assertThat(Ps2BiosSelection.isUsRegion("bios[usa].bin")).isTrue()
        assertThat(Ps2BiosSelection.isUsRegion("PS2 BIOS (Us).bin")).isTrue()
        assertThat(Ps2BiosSelection.isUsRegion("bios[US].bin")).isTrue()
        assertThat(Ps2BiosSelection.isUsRegion("SCPH-70012 (USA) v2.1.bin")).isTrue()
    }

    @Test
    fun `known US SCPH model numbers are detected with or without dashes`() {
        for (model in listOf("30001", "35001", "39001", "70012", "77001", "79001")) {
            assertThat(Ps2BiosSelection.isUsRegion("scph$model.bin")).isTrue()
            assertThat(Ps2BiosSelection.isUsRegion("SCPH-$model.bin")).isTrue()
        }
    }

    @Test
    fun `non-US names are rejected`() {
        assertThat(Ps2BiosSelection.isUsRegion("SCPH-10000 (JAPAN).bin")).isFalse()
        assertThat(Ps2BiosSelection.isUsRegion("bios (Europe).bin")).isFalse()
        assertThat(Ps2BiosSelection.isUsRegion("bios[JPN].bin")).isFalse()
        assertThat(Ps2BiosSelection.isUsRegion("SCPH-50002.bin")).isFalse // EU model
        assertThat(Ps2BiosSelection.isUsRegion("scph10001.bin")).isFalse // JP model
        assertThat(Ps2BiosSelection.isUsRegion("bios.bin")).isFalse
    }

    // ── (b) createdAt sorting ────────────────────────────────────────────────

    @Test
    fun `sorts by created_at descending`() {
        val list = listOf(
            firmware(1, "old.bin", "2024-01-01T00:00:00Z"),
            firmware(2, "newest.bin", "2025-06-01T12:00:00Z"),
            firmware(3, "middle.bin", "2025-01-01T00:00:00Z"),
        )

        assertThat(Ps2BiosSelection.sortNewestFirst(list).map { it.fileName })
            .containsExactly("newest.bin", "middle.bin", "old.bin")
    }

    @Test
    fun `null and unparseable timestamps sort as oldest`() {
        val list = listOf(
            firmware(1, "valid.bin", "2025-01-01T00:00:00Z"),
            firmware(2, "null-date.bin", null),
            firmware(3, "garbage-date.bin", "not-a-timestamp"),
            firmware(4, "blank-date.bin", "   "),
        )

        val sorted = Ps2BiosSelection.sortNewestFirst(list).map { it.fileName }
        assertThat(sorted.first()).isEqualTo("valid.bin")
        // The three unknown dates tie at "oldest" and break by file name descending.
        assertThat(sorted.subList(1, 4)).containsExactly("null-date.bin", "garbage-date.bin", "blank-date.bin")
    }

    @Test
    fun `equal timestamps tie-break by file name descending`() {
        val list = listOf(
            firmware(1, "a.bin", "2025-01-01T00:00:00Z"),
            firmware(2, "c.bin", "2025-01-01T00:00:00Z"),
            firmware(3, "b.bin", "2025-01-01T00:00:00Z"),
        )

        assertThat(Ps2BiosSelection.sortNewestFirst(list).map { it.fileName })
            .containsExactly("c.bin", "b.bin", "a.bin")
    }

    @Test
    fun `offset forms compare by absolute instant`() {
        // 2025-01-01T05:00:00+05:00 == 2025-01-01T00:00:00Z — a tie, broken by name.
        val list = listOf(
            firmware(1, "z-offset.bin", "2025-01-01T05:00:00+05:00"),
            firmware(2, "a-utc.bin", "2025-01-01T00:00:00Z"),
        )

        assertThat(Ps2BiosSelection.sortNewestFirst(list).map { it.fileName })
            .containsExactly("z-offset.bin", "a-utc.bin")
    }

    // ── (c) full fallback chain ──────────────────────────────────────────────

    @Test
    fun `explicit selection wins over the ranking`() {
        val list = listOf(
            firmware(1, "scph79001-old.bin", "2024-01-01T00:00:00Z"),
            firmware(2, "scph79001-new.bin", "2025-01-01T00:00:00Z"),
        )

        assertThat(Ps2BiosSelection.select(list, selectedId = 1L)?.fileName).isEqualTo("scph79001-old.bin")
    }

    @Test
    fun `newest US candidate wins over a newer non-US candidate`() {
        val list = listOf(
            firmware(1, "scph79001-us.bin", "2024-06-01T00:00:00Z"),
            firmware(2, "scph10000-jp.bin", "2025-06-01T00:00:00Z"),
            firmware(3, "scph70012-us.bin", "2023-06-01T00:00:00Z"),
        )

        assertThat(Ps2BiosSelection.select(list, selectedId = null)?.fileName)
            .isEqualTo("scph79001-us.bin")
    }

    @Test
    fun `falls back to the newest candidate of any region when no US candidate exists`() {
        val list = listOf(
            firmware(1, "scph50001-eu-old.bin", "2024-01-01T00:00:00Z"),
            firmware(2, "scph10000-jp-newest.bin", "2025-01-01T00:00:00Z"),
        )

        assertThat(Ps2BiosSelection.select(list, selectedId = null)?.fileName)
            .isEqualTo("scph10000-jp-newest.bin")
    }

    @Test
    fun `unknown selected id falls through to the ranking`() {
        val list = listOf(firmware(1, "scph79001-us.bin", "2024-01-01T00:00:00Z"))

        assertThat(Ps2BiosSelection.select(list, selectedId = 99L)?.fileName)
            .isEqualTo("scph79001-us.bin")
    }

    @Test
    fun `empty catalog selects nothing`() {
        assertThat(Ps2BiosSelection.select(emptyList(), selectedId = null)).isNull()
        assertThat(Ps2BiosSelection.select(emptyList(), selectedId = 1L)).isNull()
    }

    // ── (d) payload preparation: zip extraction + size guard ─────────────────

    private val inRangeBytes = ByteArray(600 * 1024) { it.toByte() }

    private fun writeZip(entries: Map<String, ByteArray>): Path {
        val zipPath = root.resolve("payload.zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return zipPath
    }

    @Test
    fun `raw in-range payload is placed at the canonical name`() {
        val source = root.resolve("bios.bin")
        Files.write(source, inRangeBytes)
        val systemDir = root.resolve("system")

        val outcome = Ps2BiosSelection.preparePayload(source, systemDir) as FirmwareStagingOutcome.Success

        assertThat(outcome.stagedPaths).containsKey(Ps2BiosSelection.CANONICAL_FILENAME)
        assertThat(Files.readAllBytes(systemDir.resolve("bios_PS2.bin"))).isEqualTo(inRangeBytes)
    }

    @Test
    fun `raw payload below the 512 KiB minimum is rejected`() {
        val source = root.resolve("tiny.bin")
        Files.write(source, ByteArray(100))

        val outcome = Ps2BiosSelection.preparePayload(source, root.resolve("system"))

        assertThat(outcome).isInstanceOf(FirmwareStagingOutcome.CorruptedDownload::class.java)
        assertThat((outcome as FirmwareStagingOutcome.CorruptedDownload).reason).contains("below")
        assertThat(Files.exists(root.resolve("system").resolve("bios_PS2.bin"))).isFalse()
    }

    @Test
    fun `raw payload above the 16 MiB maximum is rejected`() {
        val source = root.resolve("huge.bin")
        Files.write(source, ByteArray(16 * 1024 * 1024 + 1))

        val outcome = Ps2BiosSelection.preparePayload(source, root.resolve("system"))

        assertThat(outcome).isInstanceOf(FirmwareStagingOutcome.CorruptedDownload::class.java)
        assertThat((outcome as FirmwareStagingOutcome.CorruptedDownload).reason).contains("above")
    }

    @Test
    fun `zip payload extracts the first bin entry into the canonical name`() {
        val zip = writeZip(mapOf("README.txt" to "docs".toByteArray(), "bios/binaries/SCPH-79001.BIN" to inRangeBytes))
        val systemDir = root.resolve("system")

        val outcome = Ps2BiosSelection.preparePayload(zip, systemDir) as FirmwareStagingOutcome.Success

        assertThat(outcome.stagedPaths).containsKey(Ps2BiosSelection.CANONICAL_FILENAME)
        assertThat(Files.readAllBytes(systemDir.resolve("bios_PS2.bin"))).isEqualTo(inRangeBytes)
        // No leftover temp extraction files in the system directory.
        Files.list(systemDir).use { stream ->
            assertThat(stream.map { it.fileName.toString() }).containsExactly("bios_PS2.bin")
        }
    }

    @Test
    fun `zip without a bin entry is rejected`() {
        val zip = writeZip(mapOf("readme.txt" to "no bios here".toByteArray()))

        val outcome = Ps2BiosSelection.preparePayload(zip, root.resolve("system"))

        assertThat(outcome).isEqualTo(
            FirmwareStagingOutcome.CorruptedDownload("payload.zip", "no .bin entry in archive"),
        )
    }

    @Test
    fun `corrupt zip data is rejected`() {
        val zip = root.resolve("payload.zip")
        val corrupt = "PK" + 0x03.toChar() + 0x04.toChar() + "garbage-not-a-zip"
        Files.write(zip, corrupt.toByteArray())

        val outcome = Ps2BiosSelection.preparePayload(zip, root.resolve("system"))

        assertThat(outcome).isInstanceOf(FirmwareStagingOutcome.CorruptedDownload::class.java)
        assertThat((outcome as FirmwareStagingOutcome.CorruptedDownload).reason).contains("unreadable")
    }

    @Test
    fun `zip entry under the minimum size is rejected after extraction`() {
        val zip = writeZip(mapOf("bios.bin" to ByteArray(1024)))

        val outcome = Ps2BiosSelection.preparePayload(zip, root.resolve("system"))

        assertThat(outcome).isInstanceOf(FirmwareStagingOutcome.CorruptedDownload::class.java)
        assertThat((outcome as FirmwareStagingOutcome.CorruptedDownload).reason).contains("below")
    }

}

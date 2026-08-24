/*
 * Copyright (c) 2025 Romm Android TV contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.romm.desktop.library

import com.romm.androidtv.romm.FirmwareInfo
import com.romm.androidtv.romm.FirmwareStagingOutcome
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Automatic PS2 BIOS selection and payload preparation for the `lrps2` core (platform slug
 * "ps2"). Unlike SEGA CD / PlayStation — whose BIOSes are recognized by fixed SHA-1s — PS2
 * BIOSes exist in many revisions, so the newest US-region upload is picked instead:
 *
 *  1. An explicit user selection (a staged file / [select]ed entry) always wins;
 *  2. otherwise the newest US-region candidate by `created_at` (ISO-8601, descending;
 *     null/unparseable timestamps count as oldest; ties break by file name descending);
 *  3. otherwise the newest candidate of any region;
 *  4. otherwise `null` — the caller surfaces the provider's existing Missing flow.
 *
 * Region inference runs on the RAW file name (RomM's `file_name_no_tags` strips `(USA)`-style
 * tags, so it is never usable here): a US-region tag `"(usa)"`/`"[usa]"`/`"(us)"`/`"[us]"`
 * (case-insensitive) or a known US SCPH model number.
 *
 * [preparePayload] places the staged download at the core's canonical name
 * [CANONICAL_FILENAME] under [systemDirectory]: ZIP payloads are opened with [java.util.zip]
 * and the first `.bin` entry (case-insensitive) is extracted; the resulting payload must be
 * between [MIN_PAYLOAD_BYTES] and [MAX_PAYLOAD_BYTES] (a guard against mislabeled uploads);
 * non-zip payloads are size-checked directly. The final copy is temp-file + atomic rename.
 */
object Ps2BiosSelection {

    /** The exact file name the `lrps2` core expects in the system directory root. */
    const val CANONICAL_FILENAME = "bios_PS2.bin"

    /** Plausible PS2 BIOS payload floor (512 KiB) — real BIOS images are ~4 MiB. */
    const val MIN_PAYLOAD_BYTES: Long = 512L * 1024

    /** Plausible PS2 BIOS payload ceiling (16 MiB). */
    const val MAX_PAYLOAD_BYTES: Long = 16L * 1024 * 1024

    private val US_REGION_TAGS = listOf("(usa)", "[usa]", "(us)", "[us]")

    private val US_SCPH_MODELS = listOf(
        "scph30001",
        "scph35001",
        "scph39001",
        "scph70012",
        "scph77001",
        "scph79001",
    )

    private val ZIP_MAGIC = byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x03.toByte(), 0x04.toByte())

    /**
     * Whether the RAW firmware [fileName] denotes a US-region PS2 BIOS.
     *
     * Matches a US region tag case-insensitively, or a known US SCPH model number after
     * stripping non-alphanumeric characters (so `SCPH-79001` matches `scph79001`).
     */
    fun isUsRegion(fileName: String): Boolean {
        val lower = fileName.lowercase()
        if (US_REGION_TAGS.any { lower.contains(it) }) return true
        val alnum = lower.filter { it.isLetterOrDigit() }
        return US_SCPH_MODELS.any { it in alnum }
    }

    /**
     * Defensively parses an ISO-8601 UTC timestamp (`created_at`). Accepts offset forms
     * (`2025-06-01T12:00:00Z`, `+02:00`) and instant forms; returns `null` for null, blank,
     * or unparseable values so callers can treat them as "oldest".
     */
    fun parseCreatedAt(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        return try {
            OffsetDateTime.parse(trimmed).toInstant()
        } catch (_: DateTimeParseException) {
            try {
                Instant.parse(trimmed)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    /**
     * [firmware] sorted newest-first by parsed `created_at` (null/unparseable = oldest), with
     * ties broken by file name descending.
     */
    fun sortNewestFirst(firmware: List<FirmwareInfo>): List<FirmwareInfo> = firmware.sortedWith(
        Comparator { a, b ->
            val timeA = parseCreatedAt(a.createdAt)
            val timeB = parseCreatedAt(b.createdAt)
            val byTime = when {
                timeA == null && timeB == null -> 0
                timeA == null -> 1 // a unparseable/absent: a is oldest, so a goes last
                timeB == null -> -1 // b is oldest, so a goes first
                else -> timeB.compareTo(timeA) // descending: later first
            }
            if (byTime != 0) byTime else b.fileName.compareTo(a.fileName)
        },
    )

    /**
     * Ranks the catalog per the selection chain: explicit [selectedId] first, then the newest
     * US-region candidate, then the newest candidate of any region, else `null`.
     */
    fun select(firmware: List<FirmwareInfo>, selectedId: Long?): FirmwareInfo? {
        if (selectedId != null) {
            firmware.firstOrNull { it.firmwareId == selectedId }?.let { return it }
        }
        val usCandidates = firmware.filter { isUsRegion(it.fileName) }
        return if (usCandidates.isNotEmpty()) {
            sortNewestFirst(usCandidates).first()
        } else {
            sortNewestFirst(firmware).firstOrNull()
        }
    }

    /**
     * Places the staged PS2 BIOS payload at `systemDirectory/[CANONICAL_FILENAME]`.
     *
     * ZIP payloads (detected by the `PK\x03\x04` magic) contribute the first non-directory
     * entry whose name ends `.bin` (case-insensitive); the extracted bytes — or the raw
     * payload for non-zip downloads — must be between [MIN_PAYLOAD_BYTES] and
     * [MAX_PAYLOAD_BYTES]. Returns [FirmwareStagingOutcome.Success] on success,
     * [FirmwareStagingOutcome.CorruptedDownload] for unusable payloads, or
     * [FirmwareStagingOutcome.NetworkError] for IO failures.
     */
    fun preparePayload(source: Path, systemDirectory: Path): FirmwareStagingOutcome {
        val sourceName = source.fileName.toString()
        return try {
            Files.createDirectories(systemDirectory)
            val destination = systemDirectory.resolve(CANONICAL_FILENAME)

            val payload = if (isZip(source)) {
                val temp = systemDirectory.resolve(".tmp-ps2-${UUID.randomUUID()}.bin")
                val extracted = try {
                    extractFirstBinEntry(source, temp)
                } catch (e: ZipException) {
                    Files.deleteIfExists(temp)
                    return FirmwareStagingOutcome.CorruptedDownload(sourceName, "unreadable archive")
                }
                if (extracted == null) {
                    Files.deleteIfExists(temp)
                    return FirmwareStagingOutcome.CorruptedDownload(sourceName, "no .bin entry in archive")
                }
                sizeViolation(extracted)?.let {
                    Files.deleteIfExists(temp)
                    return FirmwareStagingOutcome.CorruptedDownload(sourceName, it)
                }
                temp
            } else {
                sizeViolation(source)?.let { return FirmwareStagingOutcome.CorruptedDownload(sourceName, it) }
                source
            }

            atomicCopy(payload, destination)
            if (payload != source) Files.deleteIfExists(payload)
            FirmwareStagingOutcome.Success(
                mapOf(CANONICAL_FILENAME to destination.toAbsolutePath().toString()),
            )
        } catch (e: IOException) {
            FirmwareStagingOutcome.NetworkError(e.message ?: "could not stage BIOS")
        }
    }

    private fun isZip(file: Path): Boolean {
        if (Files.size(file) < ZIP_MAGIC.size) return false
        val header = ByteArray(ZIP_MAGIC.size)
        Files.newInputStream(file).use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read == -1) break
                offset += read
            }
        }
        return header.contentEquals(ZIP_MAGIC)
    }

    /**
     * Copies the first non-directory [ZipEntry] whose name ends `.bin` (case-insensitive)
     * from [zip] into [destination]. Returns `null` when the archive holds no such entry.
     */
    private fun extractFirstBinEntry(zip: Path, destination: Path): Path? {
        ZipFile(zip.toFile()).use { archive ->
            val entry: ZipEntry? = archive.entries().asSequence()
                .filter { !it.isDirectory }
                .firstOrNull { it.name.substringAfterLast('/').lowercase().endsWith(".bin") }
                ?: return null
            archive.getInputStream(entry).use { input ->
                Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return destination
    }

    /** A human-readable violation reason, or `null` when [file]'s size is within the guard. */
    private fun sizeViolation(file: Path): String? {
        val size = Files.size(file)
        return when {
            size < MIN_PAYLOAD_BYTES ->
                "size $size bytes is below the $MIN_PAYLOAD_BYTES byte minimum for a PS2 BIOS"
            size > MAX_PAYLOAD_BYTES ->
                "size $size bytes is above the $MAX_PAYLOAD_BYTES byte maximum for a PS2 BIOS"
            else -> null
        }
    }

    /** Copies [source] to [destination] via a sibling temp file + atomic rename. */
    private fun atomicCopy(source: Path, destination: Path) {
        val temp = destination.resolveSibling(".tmp-ps2-${UUID.randomUUID()}-${destination.fileName}")
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING)
            try {
                Files.move(
                    temp,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                // Same-directory rename should always be atomic; fall back defensively.
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}

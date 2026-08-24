/*
 * Copyright (c) 2025 Romm Android TV contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.romm.desktop.library

import com.romm.androidtv.library.BiosConfigurationCatalog
import com.romm.androidtv.library.BiosConfigurationOption
import com.romm.androidtv.library.BiosConfigurationProvider
import com.romm.androidtv.romm.FirmwareInfo
import com.romm.androidtv.romm.FirmwareListResult
import com.romm.androidtv.romm.FirmwareStagingOutcome
import com.romm.androidtv.romm.PlatformIdResult
import com.romm.androidtv.romm.RommApi
import com.romm.androidtv.romm.RommApiError
import com.romm.desktop.log.DesktopLogger
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Plain-JVM [BiosConfigurationProvider] for the desktop port (plans/LINUX_X64.md §9,
 * Phase 6). One instance serves one BIOS-required console (each gets its own
 * [platformSlug], e.g. "segacd" / "psx").
 *
 * Catalog: resolves the platform id by slug (`GET /api/platforms`), then lists the
 * platform's firmware (`GET /api/firmware?platform_id=...`). Each [FirmwareInfo] maps to a
 * [BiosConfigurationOption] whose display name is the firmware file name (`FirmwareInfo`
 * exposes no region, so no region-based naming is possible on this seam).
 *
 * Staging ([select]): downloads the firmware content
 * (`GET /api/firmware/{id}/content/{file_name}`) into the XDG data `firmware/` directory
 * as `{firmwareId}_{fileName}` by writing a `.part` temp file in the same directory and
 * atomically renaming it (temp + flush + atomic rename, §9 rule 3). The SHA-1 digest is
 * accumulated in a single pass over the stream and compared after the download; the body
 * must also match the declared byte size. Before writing, the destination path is
 * canonicalized and required to remain inside [firmwareDir] (path-escape rejection,
 * §9 rules 7–9) and any pre-existing symlink at the destination is rejected (§9 rule 8).
 * The firmware directory is created user-only (0700, §9 rule 4).
 *
 * Zip handling: SEGA CD / PlayStation payloads are staged verbatim (the desktop consumes
 * the raw server content). PS2 is the exception: uploads are commonly distributed as
 * archives, so [prepareForLaunch] routes the staged PS2 download through
 * [Ps2BiosSelection.preparePayload], which extracts the first `.bin` entry from a ZIP
 * payload (size-guarded) and places it as `bios_PS2.bin`.
 *
 * Selection state: the desktop has no separate settings seam in this constructor, so the
 * staged file on disk is the source of truth: [fetchCatalog] reports the last catalog
 * entry (in catalog order, i.e. the newest by firmware id) whose staged file exists
 * under [firmwareDir] as [BiosConfigurationCatalog.Success.selectedFirmwareId].
 *
 * All blocking work runs on [Dispatchers.IO]; the `RommApi` calls are synchronous
 * OkHttp and stream the body in 16 KiB chunks.
 */
class DesktopBiosConfigurationProvider(
    private val client: OkHttpClient,
    private val originProvider: () -> String?,
    private val firmwareDir: Path,
    private val logger: Logger = DesktopLogger.get(),
    private val platformSlug: String,
) : BiosConfigurationProvider {

    private val systemName: String = when (platformSlug) {
        "segacd" -> "SEGA CD"
        "psx" -> "PlayStation"
        PS2_PLATFORM_SLUG -> "PlayStation 2"
        else -> platformSlug.replaceFirstChar { it.uppercase() }
    }

    override val title: String = "$systemName BIOS"

    override val description: String =
        "Select the BIOS file $systemName needs to run games. The selected file is downloaded from your RomM server, hash-verified, and staged for the core."

    override val emptyMessage: String =
        "Your RomM server has no BIOS files for $systemName."

    // ── Catalog ──────────────────────────────────────────────────────────────

    override suspend fun fetchCatalog(): BiosConfigurationCatalog = withContext(Dispatchers.IO) {
        val origin = originProvider()
        if (origin.isNullOrBlank()) {
            return@withContext BiosConfigurationCatalog.Error(RommApiError.ORIGIN_NOT_CONFIGURED.name)
        }

        val platformId = when (val result = RommApi.fetchPlatformId(client, origin, platformSlug)) {
            is PlatformIdResult.Success -> result.platformId
                ?: return@withContext BiosConfigurationCatalog.Error(
                    "platform '$platformSlug' not found on server",
                )
            is PlatformIdResult.Failure -> when (result.error) {
                RommApiError.AUTH_EXPIRED -> return@withContext BiosConfigurationCatalog.AuthExpired
                else -> return@withContext BiosConfigurationCatalog.Error(result.error.name)
            }
        }

        when (val result = RommApi.fetchFirmwareList(client, origin, platformId)) {
            is FirmwareListResult.Success -> {
                logger.log(Level.INFO, "Fetched BIOS catalog for $systemName: {0} file(s)", listOf(result.firmware.size))
                return@withContext BiosConfigurationCatalog.Success(
                    options = result.firmware.map { BiosConfigurationOption(it, it.fileName) },
                    selectedFirmwareId = selectedStagedFirmwareId(result.firmware),
                )
            }
            is FirmwareListResult.Failure -> {
                logger.log(Level.WARNING, "BIOS catalog fetch failed for $systemName: {0}", result.error)
                return@withContext if (result.error == RommApiError.AUTH_EXPIRED) {
                    BiosConfigurationCatalog.AuthExpired
                } else {
                    BiosConfigurationCatalog.Error(result.error.name)
                }
            }
        }
    }

    // ── Staging ──────────────────────────────────────────────────────────────

    override suspend fun select(firmware: FirmwareInfo): FirmwareStagingOutcome = withContext(Dispatchers.IO) {
        val fileName = firmware.fileName
        if (fileName.isBlank()) {
            return@withContext FirmwareStagingOutcome.CorruptedDownload(fileName, "file name is empty")
        }

        val destination = stagedFileFor(firmware)
        if (destination == null) {
            logger.log(Level.WARNING, "Rejected BIOS staging outside firmware dir: {0}", firmware)
            return@withContext FirmwareStagingOutcome.CorruptedDownload(fileName, "path escape detected")
        }

        val origin = originProvider()
        if (origin.isNullOrBlank()) {
            return@withContext FirmwareStagingOutcome.NetworkError("RomM origin not configured")
        }

        try {
            ensureFirmwareDir()
        } catch (e: IOException) {
            return@withContext classifyIo(e, firmware)
        }

        if (Files.isSymbolicLink(destination)) {
            runCatching { Files.delete(destination) }
            logger.log(Level.WARNING, "Rejected symlink at staged BIOS destination: {0}", destination)
            return@withContext FirmwareStagingOutcome.CorruptedDownload(fileName, "symlink rejected")
        }

        val error = stageDownload(firmware, destination)
        if (error != null) {
            logger.log(Level.WARNING, "BIOS staging failed for {0}: {1}", listOf(firmware.fileName, error))
            return@withContext error
        }

        logger.log(Level.INFO, "Staged BIOS {0} -> {1}", listOf(firmware.fileName, destination))
        FirmwareStagingOutcome.Success(mapOf(fileName to destination.toAbsolutePath().toString()))
    }

    /**
     * Ensures launch-time firmware is available under the filenames the Libretro core requires.
     *
     * The configuration screen retains the original server filename under [firmwareDir], while
     * the player-facing copies use the Libretro core's fixed regional names. This matches the
     * Android launch preparation behavior and lets a BIOS-backed launch retrieve recognized
     * firmware on demand when the user has not configured one explicitly.
     */
    suspend fun prepareForLaunch(systemDirectory: Path): FirmwareStagingOutcome = withContext(Dispatchers.IO) {
        val requirements = firmwareRequirements()
            ?: return@withContext FirmwareStagingOutcome.Missing(emptyList())

        val catalog = when (val result = fetchCatalog()) {
            is BiosConfigurationCatalog.Success -> result
            BiosConfigurationCatalog.AuthExpired -> return@withContext FirmwareStagingOutcome.AuthExpired
            is BiosConfigurationCatalog.Error -> return@withContext FirmwareStagingOutcome.NetworkError(result.message)
        }

        val stagedSelection = catalog.selectedFirmwareId?.let { selectedId ->
            catalog.options.firstOrNull { it.firmware.firmwareId == selectedId }?.firmware
        }
        val firmware = stagedSelection
            ?: autoSelectableFirmware(catalog.options, requirements)
            ?: return@withContext FirmwareStagingOutcome.Missing(requirements.canonicalFileNames)

        val source = stagedFileFor(firmware)?.takeIf {
            Files.isRegularFile(it) && !Files.isSymbolicLink(it)
        } ?: when (val outcome = select(firmware)) {
            is FirmwareStagingOutcome.Success -> Path.of(outcome.stagedPaths.getValue(firmware.fileName))
            else -> return@withContext outcome
        }

        try {
            ensureFirmwareDir()
            if (platformSlug == PS2_PLATFORM_SLUG) {
                // PS2 BIOSes arrive in many revisions (and often as ZIPs): extract/size-guard
                // the payload and place it under the single canonical name the core expects.
                val outcome = Ps2BiosSelection.preparePayload(source, systemDirectory)
                if (outcome !is FirmwareStagingOutcome.Success) return@withContext outcome
            } else {
                for (fileName in requirements.canonicalFileNames) {
                    atomicCopy(source, systemDirectory.resolve(fileName))
                }
            }
        } catch (e: IOException) {
            return@withContext FirmwareStagingOutcome.NetworkError(
                e.message ?: "Could not stage BIOS",
            )
        }

        FirmwareStagingOutcome.Success(
            requirements.canonicalFileNames.associateWith {
                systemDirectory.resolve(it).toAbsolutePath().toString()
            },
        )
    }

    /**
     * Whether this catalog contains a BIOS that can be selected automatically.
     *
     * This mirrors Android's availability check: an explicit staged selection wins, but a known
     * USA (then Europe, then Japan) image also makes the system ready for launch preparation.
     * PS2 has no fixed hash set — any catalog entry is auto-selectable (the newest US-region
     * candidate wins; see [Ps2BiosSelection.select]).
     */
    fun hasAutoSelectableFirmware(catalog: BiosConfigurationCatalog.Success): Boolean =
        autoSelectableFirmware(catalog.options, firmwareRequirements()) != null

    private fun firmwareRequirements(): FirmwareRequirements? = when (platformSlug) {
            SEGA_CD_PLATFORM_SLUG -> FirmwareRequirements(
                preferredSha1 = listOf(SEGA_CD_US_SHA1, SEGA_CD_EU_SHA1, SEGA_CD_JP_SHA1),
                canonicalFileNames = CANONICAL_SEGA_CD_FILENAMES,
            )
            PSX_PLATFORM_SLUG -> FirmwareRequirements(
                preferredSha1 = listOf(PSX_US_SHA1, PSX_EU_SHA1, PSX_JP_SHA1),
                canonicalFileNames = CANONICAL_PSX_FILENAMES,
            )
            PS2_PLATFORM_SLUG -> FirmwareRequirements(
                // No fixed SHA-1 set: PS2 BIOS revisions are open-ended, so selection is
                // rank-based (newest US-region candidate) via [Ps2BiosSelection.select].
                preferredSha1 = emptyList(),
                canonicalFileNames = listOf(Ps2BiosSelection.CANONICAL_FILENAME),
            )
            else -> null
        }

    private fun autoSelectableFirmware(
        options: List<BiosConfigurationOption>,
        requirements: FirmwareRequirements?,
    ): FirmwareInfo? = when (platformSlug) {
        PS2_PLATFORM_SLUG -> Ps2BiosSelection.select(options.map { it.firmware }, null)
        else -> requirements?.preferredSha1?.firstNotNullOfOrNull { sha1 ->
            options.firstOrNull { it.firmware.sha1Hash.equals(sha1, ignoreCase = true) }?.firmware
        }
    }

    /**
     * Streams the firmware content into a `{id}_{name}.part` sibling of [destination] in a
     * single pass (SHA-1 accumulated per chunk), verifies declared size + SHA-1, then
     * atomically renames the part file to [destination]. Returns `null` on success, or the
     * mapped [FirmwareStagingOutcome] failure.
     */
    private fun stageDownload(firmware: FirmwareInfo, destination: Path): FirmwareStagingOutcome? {
        val fileName = firmware.fileName
        val part = destination.resolveSibling(destination.fileName.toString() + PART_SUFFIX)
        val sink = HashingSink()
        try {
            val request = Request.Builder()
                .url(RommApi.firmwareContentUrl(requireOriginOrDie(), firmware.firmwareId, fileName))
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> {
                        return FirmwareStagingOutcome.AuthExpired
                    }
                    response.code == 404 -> {
                        return FirmwareStagingOutcome.NetworkError("BIOS not found on server")
                    }
                    !response.isSuccessful -> {
                        return FirmwareStagingOutcome.NetworkError("HTTP ${response.code}")
                    }
                }

                val body = response.body ?: return FirmwareStagingOutcome.NetworkError("empty response body")
                body.byteStream().use { input ->
                    Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                        .use { output ->
                            writeStream(input, output, sink, firmware)
                        }
                }
            }
        } catch (e: StageWriteException) {
            dropPart(part)
            return e.outcome
        } catch (e: IOException) {
            dropPart(part)
            return classifyIo(e, firmware)
        }

        // Size first, then hash: a truncated body fails both; the size is the more specific.
        if (firmware.sizeBytes > 0 && sink.bytes != firmware.sizeBytes) {
            dropPart(part)
            return FirmwareStagingOutcome.CorruptedDownload(fileName, "size mismatch")
        }
        if (firmware.sha1Hash.isNotBlank()) {
            val actual = sink.digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(firmware.sha1Hash.trim(), ignoreCase = true)) {
                dropPart(part)
                return FirmwareStagingOutcome.CorruptedDownload(fileName, "SHA-1 mismatch")
            }
        }

        try {
            bestEffort0600(part)
            movePart(part, destination)
        } catch (e: IOException) {
            dropPart(part)
            return FirmwareStagingOutcome.CorruptedDownload(fileName, "write failed: ${e.message}")
        }
        return null
    }

    private fun requireOriginOrDie(): String =
        checkNotNull(originProvider()) { "RomM origin not configured" }

    private fun writeStream(input: InputStream, output: OutputStream, sink: HashingSink, firmware: FirmwareInfo) {
        val buffer = ByteArray(CHUNK_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            try {
                output.write(buffer, 0, read)
            } catch (e: IOException) {
                val outcome = if (hasSpaceSignal(e)) {
                    FirmwareStagingOutcome.InsufficientSpace(
                        if (firmware.sizeBytes > 0) firmware.sizeBytes else sink.bytes,
                        availableSpaceBytes(),
                    )
                } else {
                    FirmwareStagingOutcome.CorruptedDownload(firmware.fileName, "write failed: ${e.message}")
                }
                throw StageWriteException(e, outcome)
            }
            sink.digest.update(buffer, 0, read)
            sink.bytes += read
            if (firmware.sizeBytes > 0 && sink.bytes > firmware.sizeBytes) break // server over-delivered
        }
    }

    private fun movePart(part: Path, destination: Path) {
        try {
            Files.move(
                part,
                destination,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            // Same-directory rename should always be atomic; fall back defensively.
            Files.move(part, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun atomicCopy(source: Path, destination: Path) {
        Files.createDirectories(destination.parent)
        val temp = destination.resolveSibling(".tmp-${UUID.randomUUID()}-${destination.fileName}")
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING)
            movePart(temp, destination)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** Carries a pre-built staging outcome past the [stageDownload] catch without losing it. */
    private class StageWriteException(cause: IOException, val outcome: FirmwareStagingOutcome) :
        IOException(cause.message, cause)

    /** Single-pass accumulation of the SHA-1 digest and byte count. */
    private class HashingSink {
        val digest: MessageDigest = MessageDigest.getInstance("SHA-1")
        var bytes: Long = 0
    }

    private data class FirmwareRequirements(
        val preferredSha1: List<String>,
        val canonicalFileNames: List<String>,
    )

    /**
     * Canonical staging location: `{firmwareDir}/{firmwareId}_{fileName}`.
     *
     * Returns `null` when the derived leaf name cannot be a single in-directory file name
     * (hostile file names such as `../x` or names containing path separators) or when the
     * canonicalized path escapes [firmwareDir]. Callers map `null` to a
     * "path escape detected" [FirmwareStagingOutcome.CorruptedDownload].
     */
    private fun stagedFileFor(firmware: FirmwareInfo): Path? {
        val name = "${firmware.firmwareId}_${firmware.fileName}"
        if (name.isBlank() || name == ".." || name.startsWith(".") ||
            name.contains('/') || name.contains(File.separatorChar)
        ) {
            return null
        }
        val destination = firmwareDir.resolve(name)
        return if (destination.isWithin(firmwareDir)) destination else null
    }

    private fun Path.isWithin(root: Path): Boolean {
        val abs = toAbsolutePath().normalize()
        val rootAbs = root.toAbsolutePath().normalize()
        return abs == rootAbs || abs.startsWith(rootAbs)
    }

    /**
     * The staged file existing under [firmwareDir] is the desktop's selected-BIOS source of
     * truth (no separate settings seam in this constructor). Returns the firmware id of the
     * LAST catalog entry (in catalog order — newest first by firmware id among the server's
     * listing) whose staged file exists as a regular, non-symlink file, or `null`.
     */
    private fun selectedStagedFirmwareId(catalog: List<FirmwareInfo>): Long? {
        var selected: Long? = null
        for (firmware in catalog) {
            val staged = stagedFileFor(firmware) ?: continue
            if (Files.isRegularFile(staged) && !Files.isSymbolicLink(staged)) {
                selected = firmware.firmwareId
            }
        }
        return selected
    }

    /** Creates [firmwareDir] user-only (0700, §9 rule 4) if absent; rejects a symlink root. */
    private fun ensureFirmwareDir() {
        if (Files.exists(firmwareDir)) {
            if (Files.isSymbolicLink(firmwareDir)) {
                throw IOException("firmware directory is a symlink: $firmwareDir")
            }
            return
        }
        Files.createDirectories(firmwareDir)
        bestEffort0700(firmwareDir)
    }

    private fun bestEffort0700(directory: Path) {
        try {
            Files.setPosixFilePermissions(directory, USER_ONLY_DIR_PERMS)
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystems: permissions cannot be set; do not fail staging.
        }
    }

    private fun bestEffort0600(file: Path) {
        try {
            Files.setPosixFilePermissions(file, USER_ONLY_FILE_PERMS)
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystems: no-op.
        }
    }

    private fun dropPart(part: Path) {
        try {
            Files.deleteIfExists(part)
        } catch (_: IOException) {
            // Best effort: a leaked .part file is re-truncated on the next attempt.
        }
    }

    private fun availableSpaceBytes(): Long =
        runCatching { Files.getFileStore(firmwareDir).usableSpace }.getOrNull() ?: 0L

    /**
     * Maps a transport/free IO failure to the staging outcome per the Phase 6 table:
     * TLS → `NetworkError("TLS error: …")`, out-of-space → `InsufficientSpace`,
     * everything else → `NetworkError(message)`.
     */
    private fun classifyIo(e: IOException, firmware: FirmwareInfo): FirmwareStagingOutcome {
        var current: Throwable = e
        while (true) {
            val next = current.cause ?: break
            current = next
        }
        val cause = current
        return when {
            cause is SSLException || cause.javaClass.name.contains("SSL", ignoreCase = true) ->
                FirmwareStagingOutcome.NetworkError("TLS error: ${e.message ?: cause.message ?: "unknown failure"}")
            hasSpaceSignal(e) ->
                FirmwareStagingOutcome.InsufficientSpace(
                    if (firmware.sizeBytes > 0) firmware.sizeBytes else 0L,
                    availableSpaceBytes(),
                )
            else ->
                FirmwareStagingOutcome.NetworkError(e.message ?: "network request failed")
        }
    }

    private fun hasSpaceSignal(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            val message = cause.message?.lowercase()
            if (message != null &&
                (message.contains("no space left") ||
                    message.contains("storage full") ||
                    message.contains("out of space") ||
                    message.contains("disk full") ||
                    message.contains("unable to allocate"))
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

}

private const val PART_SUFFIX = ".part"
private const val CHUNK_SIZE = 16 * 1024
private const val SEGA_CD_PLATFORM_SLUG = "segacd"
private const val SEGA_CD_US_SHA1 = "f4f315adcef9b8feb0364c21ab7f0eaf5457f3ed"
private const val SEGA_CD_EU_SHA1 = "f891e0ea651e2232af0c5c4cb46a0cae2ee8f356"
private const val SEGA_CD_JP_SHA1 = "4846f448160059a7da0215a5df12ca160f26dd69"
private val CANONICAL_SEGA_CD_FILENAMES = listOf("bios_CD_U.bin", "bios_CD_E.bin", "bios_CD_J.bin")
private const val PSX_PLATFORM_SLUG = "psx"
private const val PS2_PLATFORM_SLUG = "ps2"
private const val PSX_US_SHA1 = "0555c6fae8906f3f09baf5988f00e55f88e9f30b"
private const val PSX_EU_SHA1 = "f6bc2d1f5eb6593de7d089c425ac681d6fffd3f0"
private const val PSX_JP_SHA1 = "b05def971d8ec59f346f2d9ac21fb742e3eb6917"
private val CANONICAL_PSX_FILENAMES = listOf(
    "scph5500.bin",
    "scph5501.bin",
    "scph5502.bin",
    "psxonpsp660.bin",
    "scph101.bin",
    "scph7001.bin",
    "scph1001.bin",
)

private val USER_ONLY_DIR_PERMS: Set<PosixFilePermission> = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
    PosixFilePermission.OWNER_EXECUTE,
) // 0700: user-only (§9 rule 4)

private val USER_ONLY_FILE_PERMS: Set<PosixFilePermission> = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
) // 0600: user-only

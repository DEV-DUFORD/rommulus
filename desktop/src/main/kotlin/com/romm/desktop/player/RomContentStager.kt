package com.romm.desktop.player

import com.romm.androidtv.romm.RommApi
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** Staged ROM content ready to be pinned in a player launch request. */
data class StagedContent(
    /** Path of the staged file (under the ROM cache root). */
    val path: Path,
    /** Lowercase hex SHA-256 of [path]'s current bytes. */
    val sha256: String,
)

/**
 * The distinct fail-closed ROM staging failures. Each maps to a focused, user-facing message in
 * the launch flow ([com.romm.desktop.DesktopAppCoordinator] — plans/LINUX_X64.md Phase 11 work
 * item 6), mirroring how [com.romm.androidtv.romm.FirmwareStagingOutcome] variants map for BIOS.
 */
enum class RomContentStagingFailure {
    /** A `.chd` file was expected but the MComprHD signature is missing (malformed CHD). */
    InvalidChdSignature,

    /** The served bytes are empty or do not match the server-declared size. */
    SizeMismatch,

    /** Content is corrupt or unusable: malformed archive, truncated entry, payload the core cannot load. */
    CorruptContent,

    /** Content was rejected for safety: unsafe archive entry path, extraction-limit trip. */
    UnsafeContent,

    /**
     * The ROM is multi-file (multi-disc / multi-part): the server packages it as an archive with
     * more than one playable file (RomM serves every file of a multi-file ROM as a ZIP, adding a
     * synthesized `.m3u` when none exists), which the single-file staging path cannot reduce to
     * one core-loadable file. Not a corruption — the content is intact, just not playable yet.
     */
    MultiFileContent,

    /** The download itself failed (HTTP error, transport failure). */
    DownloadFailed,

    /** A local filesystem write/staging failure. */
    WriteFailed,

    /** Configuration problem (blank file name, missing server origin, core without extensions). */
    Misconfigured,
}

/** Fail-closed ROM staging failure: HTTP error, empty body, size mismatch, or write failure. */
class RomContentStagingException(
    message: String,
    cause: Throwable? = null,
    /** The distinct failure reason; the launch flow maps it to a focused user-facing message. */
    val failure: RomContentStagingFailure,
) : IOException(message, cause)

internal data class ArchiveExtractionLimits(
    val maxBytes: Long,
    val maxCompressionRatio: Long,
)

/**
 * Optical-disc images are routinely larger than the conservative cartridge-ROM cap. They remain
 * bounded independently so an archive cannot expand without limit, while normal GameCube,
 * PlayStation, and Sega CD images can be staged.
 */
internal fun archiveExtractionLimitsFor(extension: String): ArchiveExtractionLimits =
    if (extension.lowercase(Locale.ROOT) in DISC_IMAGE_EXTENSIONS) {
        ArchiveExtractionLimits(
            maxBytes = MAX_EXTRACTED_DISC_IMAGE_BYTES,
            maxCompressionRatio = MAX_DISC_IMAGE_COMPRESSION_RATIO,
        )
    } else {
        ArchiveExtractionLimits(
            maxBytes = MAX_EXTRACTED_CARTRIDGE_ROM_BYTES,
            maxCompressionRatio = MAX_CARTRIDGE_ROM_COMPRESSION_RATIO,
        )
    }

/**
 * Seam for staging a ROM's content before player launch (Phase 8 real-content increment).
 *
 * Implementations download the ROM from the RomM server (`GET /api/roms/{id}/content/{file_name}`),
 * write it atomically under the ROM cache root, and return its path + SHA-256 so the launch request
 * can pin both (the player verifies the content hash at load time; the desktop adoption policy
 * re-verifies it at reconciliation). Fail-closed: any failure throws [RomContentStagingException] —
 * a real-content core is never launched without staged content.
 */
interface RomContentStager {
    /**
     * Stages the ROM named [fileName] belonging to [romId].
     *
     * @param expectedSizeBytes the server-declared size; `<= 0` means "unknown" (the size check is skipped).
     * @throws RomContentStagingException on any failure — there is no silent fallback.
     */
    fun stage(
        romId: Long,
        fileName: String,
        expectedSizeBytes: Long,
        supportedExtensions: Set<String> = emptySet(),
    ): StagedContent
}

/**
 * ROM transfers may legitimately run for many minutes. Remove only OkHttp's whole-call deadline;
 * the shared client's connect and read-inactivity timeouts still detect unreachable or stalled
 * servers, and its authentication/cookie interceptors are retained by [OkHttpClient.newBuilder].
 */
internal fun gameDownloadClient(client: OkHttpClient): OkHttpClient =
    client.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

/**
 * Production [RomContentStager]: downloads via the authenticated OkHttp client (the same Bearer +
 * cookie + CSRF stack as images and BIOS staging) and stages under
 * `cacheDir/roms/<origin-key>/<romId>/<fileName>` — the "roms" cache subdirectory Android maps for
 * ROM content, mirrored on desktop (§9: ROM content is rebuildable cache; saves never live here).
 * The origin key sanitizes the server-origin string for path safety (letters and digits are kept,
 * every other character maps to `_`), so cache identity is isolated per origin and per ROM: two
 * different ROMs that share a server file name — within or across servers — can never collide on one
 * cached file, because the size-only reuse gate below cannot tell same-size different content apart.
 * The layout is deterministic, so the same ROM always resolves to the same staged path across launches
 * (CHD content included: its `.chd` sibling lives in the same dir).
 *
 * Reuse rule: a non-symlink regular file already at the destination is reused WITHOUT a network
 * round trip when its size matches [expectedSizeBytes] (or the expected size is unknown). The
 * SHA-256 is always recomputed from disk — correctness first; persistent hash caching is a later
 * optimization for large (GB-class) cores, where re-hashing becomes expensive.
 *
 * Write path: stream to a `<name>.part` sibling in the same directory, verify size, then atomic
 * rename over the destination (temp + fsync-free rename is sufficient here — the file is cache,
 * and a torn cache file is healed by the next launch's size/hash pass). A failed download never
 * leaves a partial destination behind. ZIP and 7z content is detected by file signature (the
 * detail API's file name may omit the archive extension), then its playable members — the entries
 * matching the selected core's supported extensions, ignoring `.m3u` playlist metadata — are
 * resolved: exactly one is extracted atomically with the same safety limits as Android, zero or
 * two-plus reject fail-closed (no usable content / genuinely multi-file ROM). A size-mismatched
 * download that still reads as a complete archive takes this same path instead of failing as a
 * corrupt transfer, because RomM's multi-file packages are compressed ZIPs whose size can never
 * equal the declared sum of their members.
 *
 * All operations are blocking (synchronous OkHttp + file I/O); callers must run this off the UI
 * thread ([DesktopAppCoordinator.launchPlayer] already does — see its KDoc).
 */
class OkHttpRomContentStager(
    private val client: OkHttpClient,
    private val originProvider: () -> String?,
    private val romCacheDir: Path,
) : RomContentStager {

    private val downloadClient = gameDownloadClient(client)

    override fun stage(
        romId: Long,
        fileName: String,
        expectedSizeBytes: Long,
        supportedExtensions: Set<String>,
    ): StagedContent {
        if (fileName.isBlank()) {
            throw RomContentStagingException(
                "ROM file name is blank; cannot stage content for rom $romId",
                failure = RomContentStagingFailure.Misconfigured,
            )
        }
        val origin = originProvider().orEmpty()
        if (origin.isBlank()) {
            throw RomContentStagingException(
                "RomM origin not configured; cannot download ROM content",
                failure = RomContentStagingFailure.Misconfigured,
            )
        }

        // Defend against path traversal in a server-provided file name: the cached file must stay
        // inside the ROM cache root.
        val safeName = fileName.replace('/', '_').replace('\\', '_')
            .takeIf { it.isNotEmpty() && it != "." && it != ".." } ?: "_"

        // Cache identity is scoped by (origin, romId): deterministic per-ROM directory, so the same
        // ROM always stages to the same path across launches and a different ROM can never reuse
        // another ROM's cached file even when the server file names collide.
        val contentDir = romCacheDir.resolve(originKey(origin)).resolve(romId.toString())
        val destination = contentDir.resolve(safeName)

        runCatching { Files.createDirectories(contentDir) }
            .getOrElse {
                throw RomContentStagingException(
                    "cannot create the ROM cache directory: ${it.message}",
                    it,
                    failure = RomContentStagingFailure.WriteFailed,
                )
            }

        // Reuse: already staged and the size matches (or is unknown) — skip the download.
        if (!Files.isSymbolicLink(destination) && Files.isRegularFile(destination)) {
            val existingSize = runCatching { Files.size(destination) }.getOrNull()
            if (existingSize != null && (expectedSizeBytes <= 0 || existingSize == expectedSizeBytes)) {
                // Hash from disk every time: correctness first. Caching the hash persistently is a
                // later optimization for large cores — GB-class ROMs make re-hashing expensive.
                return prepareOrDiscard(destination, safeName, contentDir, supportedExtensions)
            }
        }

        val part = contentDir.resolve("$safeName.part")
        try {
            val url = RommApi.romContentUrl(origin, romId, fileName)
            val request = Request.Builder().url(url).get().build()
            downloadClient.newCall(request).execute().use { response ->
                when {
                    !response.isSuccessful ->
                        throw RomContentStagingException(
                            "HTTP ${response.code} for '$fileName'",
                            failure = RomContentStagingFailure.DownloadFailed,
                        )
                    response.body == null ->
                        throw RomContentStagingException(
                            "no response body for '$fileName'",
                            failure = RomContentStagingFailure.DownloadFailed,
                        )
                    else -> response.body!!.byteStream().use { input ->
                        Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                            .use { output -> input.copyTo(output) }
                    }
                }
            }
        } catch (e: RomContentStagingException) {
            dropPart(part)
            throw e
        } catch (e: IOException) {
            dropPart(part)
            throw RomContentStagingException(
                "${e.message ?: "network error"} while downloading '$fileName'",
                e,
                failure = RomContentStagingFailure.DownloadFailed,
            )
        }

        val size = runCatching { Files.size(part) }.getOrElse {
            dropPart(part)
            throw RomContentStagingException(
                "the staged file could not be read back for '$fileName'",
                it,
                failure = RomContentStagingFailure.WriteFailed,
            )
        }
        if (size == 0L) {
            dropPart(part)
            throw RomContentStagingException(
                "ROM download was empty: '$fileName'",
                failure = RomContentStagingFailure.SizeMismatch,
            )
        }
        if (expectedSizeBytes > 0 && size != expectedSizeBytes) {
            // A compressed archive can legitimately differ from the declared size: RomM serves
            // every file of a multi-file ROM as one ZIP, whose compressed size can never equal
            // the declared sum of its members' sizes. When the bytes are still a COMPLETE,
            // readable archive, keep them and let prepareContent decide — it extracts the
            // package when exactly one member is playable for the core (a single-disc ROM
            // shipped with extra non-playable files) and rejects it otherwise. Anything that
            // does not read as an archive fails closed as a plain size mismatch (truncated or
            // corrupt transfer).
            if (!isReadableArchive(part, safeName)) {
                dropPart(part)
                throw RomContentStagingException(
                    "ROM size mismatch for '$fileName': expected $expectedSizeBytes bytes, got $size",
                    failure = RomContentStagingFailure.SizeMismatch,
                )
            }
        }

        runCatching {
            Files.move(part, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            dropPart(part)
            throw RomContentStagingException(
                "finalizing the staged file failed for '$fileName': ${it.message}",
                it,
                failure = RomContentStagingFailure.WriteFailed,
            )
        }

        return prepareOrDiscard(destination, safeName, contentDir, supportedExtensions)
    }

    /**
     * Runs [prepareContent] and, when it fails because the staged BYTES are at fault (malformed
     * CHD, corrupt archive, unsafe entry, size mismatch), discards the cache entry. A poisoned
     * entry would otherwise be rejected from disk on every launch — even after the user re-uploads
     * a fixed ROM to RomM; deleting it lets the next launch fetch fresh content. Infrastructure
     * and configuration failures ([RomContentStagingFailure.WriteFailed],
     * [RomContentStagingFailure.DownloadFailed], [RomContentStagingFailure.Misconfigured]) keep
     * the cache untouched: the bytes are not the problem.
     */
    private fun prepareOrDiscard(
        destination: Path,
        safeName: String,
        contentDir: Path,
        supportedExtensions: Set<String>,
    ): StagedContent = try {
        prepareContent(destination, safeName, contentDir, supportedExtensions)
    } catch (e: RomContentStagingException) {
        if (e.failure != RomContentStagingFailure.Misconfigured &&
            e.failure != RomContentStagingFailure.DownloadFailed &&
            e.failure != RomContentStagingFailure.WriteFailed
        ) {
            runCatching { Files.deleteIfExists(destination) }
        }
        throw e
    }

    private fun prepareContent(
        downloadedFile: Path,
        safeName: String,
        contentDir: Path,
        supportedExtensions: Set<String>,
    ): StagedContent {
        val archiveFormat = detectArchiveFormat(downloadedFile)
        if (archiveFormat == null) {
            val corePath = addRecognizedContentExtension(downloadedFile, safeName, supportedExtensions)
            return StagedContent(corePath, SecureFiles.sha256Hex(corePath))
        }

        val normalizedExtensions = supportedExtensions
            .map { extension ->
                extension.lowercase(Locale.ROOT).let { if (it.startsWith('.')) it else ".$it" }
            }
            .toSet()
        if (normalizedExtensions.isEmpty()) {
            throw RomContentStagingException(
                "downloaded ROM '$safeName' is an archive, but the selected core declares no supported file extensions",
                failure = RomContentStagingFailure.Misconfigured,
            )
        }

        val entries = readArchiveEntries(downloadedFile, archiveFormat, safeName)
        // Pick the playable member(s). `.m3u` entries are playlist metadata — RomM synthesizes
        // one into every multi-file package it serves — never the payload itself, so they are
        // not candidates. A candidate is any other entry whose name matches a core-supported
        // extension. Exactly one candidate is stageable:
        //  - zero candidates -> no usable content in the package (mislabeled/corrupt upload);
        //  - two or more     -> genuinely multi-file content (multi-disc / multi-part ROM),
        //    rejected as such — the archive is intact, not corrupt.
        val candidates = entries
            .filterNot { it.name.lowercase(Locale.ROOT).endsWith(M3U_EXTENSION) }
            .filter { entry ->
                normalizedExtensions.any { ext -> entry.name.lowercase(Locale.ROOT).endsWith(ext) }
            }
        if (candidates.isEmpty()) {
            throw RomContentStagingException(
                "downloaded ${archiveFormat.label} ROM '$safeName' contains no file supported by the selected core",
                failure = RomContentStagingFailure.CorruptContent,
            )
        }
        if (candidates.size > 1) {
            throw RomContentStagingException(
                "downloaded ${archiveFormat.label} ROM '$safeName' contains multiple files supported by the selected core " +
                    "(${candidates.joinToString(", ") { it.name }}); only single-file ROMs can be played",
                failure = RomContentStagingFailure.MultiFileContent,
            )
        }

        val selected = candidates.single()
        val extension = checkNotNull(normalizedExtensions.firstOrNull {
            selected.name.lowercase(Locale.ROOT).endsWith(it)
        })
        if (selected.size < 0) {
            throw RomContentStagingException(
                "archived ROM '${selected.name}' has an invalid size: ${selected.size} bytes",
                failure = RomContentStagingFailure.CorruptContent,
            )
        }
        val extractionLimits = archiveExtractionLimitsFor(extension)
        if (selected.size > extractionLimits.maxBytes) {
            throw RomContentStagingException(
                "archived ROM '${selected.name}' exceeds the extraction size limit of " +
                    "${extractionLimits.maxBytes} bytes",
                failure = RomContentStagingFailure.UnsafeContent,
            )
        }
        val extracted = contentDir.resolve("$safeName$extension")
        val part = contentDir.resolve("$safeName$extension.extract.part")

        try {
            extractArchiveEntry(downloadedFile, archiveFormat, selected, part, extractionLimits)
            Files.move(part, extracted, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: RomContentStagingException) {
            dropPart(part)
            throw e
        } catch (e: IOException) {
            dropPart(part)
            throw RomContentStagingException(
                "failed to extract archived ROM '${selected.name}': ${e.message}",
                e,
                failure = RomContentStagingFailure.CorruptContent,
            )
        }

        // A `.chd` extracted from an archive must carry the MComprHD signature just like a directly
        // downloaded one — fail closed instead of handing malformed bytes to the core's loader.
        if (extension.equals(CHD_EXTENSION, ignoreCase = true) && !hasChdSignature(extracted)) {
            runCatching { Files.deleteIfExists(extracted) }
            throw RomContentStagingException(
                "ROM content '$safeName' is not a valid CHD file (missing MComprHD signature)",
                failure = RomContentStagingFailure.InvalidChdSignature,
            )
        }

        return StagedContent(extracted, SecureFiles.sha256Hex(extracted))
    }

    private fun detectArchiveFormat(path: Path): ArchiveFormat? {
        val required = maxOf(SEVEN_ZIP_SIGNATURE.size, ZIP_LOCAL_FILE_HEADER_SIGNATURE.size)
        val signature = ByteArray(required)
        // readNBytes-equivalent loop: a single read() may return fewer bytes than requested.
        val bytesRead = Files.newInputStream(path).use { input ->
            var offset = 0
            while (offset < signature.size) {
                val n = input.read(signature, offset, signature.size - offset)
                if (n < 0) break
                offset += n
            }
            offset
        }
        if (bytesRead >= SEVEN_ZIP_SIGNATURE.size &&
            signature.copyOf(SEVEN_ZIP_SIGNATURE.size).contentEquals(SEVEN_ZIP_SIGNATURE)
        ) {
            return ArchiveFormat.SEVEN_ZIP
        }
        // A bare 2-byte "PK" prefix is not enough — a plain ROM that happens to start with
        // 0x50 0x4B would be misdetected. Require the full local-file-header signature
        // (PK\x03\x04), or the end-of-central-directory signature (PK\x05\x06) for edge archives.
        if (bytesRead >= ZIP_LOCAL_FILE_HEADER_SIGNATURE.size) {
            val head = signature.copyOf(ZIP_LOCAL_FILE_HEADER_SIGNATURE.size)
            if (head.contentEquals(ZIP_LOCAL_FILE_HEADER_SIGNATURE) ||
                head.contentEquals(ZIP_END_OF_CENTRAL_DIRECTORY_SIGNATURE)
            ) {
                return ArchiveFormat.ZIP
            }
        }
        return null
    }

    /**
     * Whether [path] is a complete, readable archive: its signature is ZIP/7z AND its entry
     * list parses. Used by the size gate in [stage] to tell "the server served a compressed
     * multi-file package" (whose size can never match the declared sum of its members) apart
     * from a genuinely truncated/corrupt download. When true, [prepareContent] re-inspects the
     * entries and decides between extracting the sole playable member and rejecting the
     * package. A safety-limit rejection ([RomContentStagingException]) still counts as
     * readable — prepareContent re-runs the check and reports the real reason; any other read
     * failure (truncated central directory, partial 7z header) returns false so the caller
     * keeps the plain size-mismatch diagnosis.
     */
    private fun isReadableArchive(path: Path, safeName: String): Boolean {
        val format = detectArchiveFormat(path) ?: return false
        return try {
            readArchiveEntries(path, format, safeName)
            true
        } catch (e: RomContentStagingException) {
            // Only a policy rejection means the archive parsed completely: the entry-count limit
            // and unsafe-entry checks both throw UnsafeContent. Every other staging exception
            // from readArchiveEntries is a parse failure ("cannot read downloaded …") wrapped
            // as CorruptContent — the archive is structurally unreadable (truncated central
            // directory, partial 7z header), so the caller keeps the size-mismatch diagnosis.
            e.failure == RomContentStagingFailure.UnsafeContent
        } catch (_: Exception) {
            false
        }
    }

    /**
     * RomM metadata can omit a raw image's suffix. Preserve the cached source name, but provide
     * Libretro a correctly suffixed sibling for formats whose loaders dispatch by extension.
     *
     * A file that CLAIMS to be a CHD (its name ends in `.chd`) must carry the MComprHD signature —
     * malformed content is rejected fail-closed here instead of being handed to the core's loader,
     * which would only surface the failure later as an opaque player crash (Phase 11 work item 6).
     */
    private fun addRecognizedContentExtension(
        downloadedFile: Path,
        safeName: String,
        supportedExtensions: Set<String>,
    ): Path {
        if (safeName.lowercase(Locale.ROOT).endsWith(CHD_EXTENSION)) {
            if (!hasChdSignature(downloadedFile)) {
                throw RomContentStagingException(
                    "ROM content '$safeName' is not a valid CHD file (missing MComprHD signature)",
                    failure = RomContentStagingFailure.InvalidChdSignature,
                )
            }
            return downloadedFile
        }
        if (CHD_EXTENSION !in supportedExtensions.map { it.lowercase(Locale.ROOT) }) {
            return downloadedFile
        }
        if (!hasChdSignature(downloadedFile)) {
            return downloadedFile
        }

        val corePath = downloadedFile.resolveSibling("$safeName$CHD_EXTENSION")
        if (!Files.isRegularFile(corePath) || Files.size(corePath) != Files.size(downloadedFile)) {
            Files.copy(downloadedFile, corePath, StandardCopyOption.REPLACE_EXISTING)
        }
        return corePath
    }

    /** True when [path] starts with the CHD magic ("MComprHD"). */
    private fun hasChdSignature(path: Path): Boolean {
        val signature = ByteArray(CHD_SIGNATURE.size)
        // readNBytes-equivalent loop: a single read() may return fewer bytes than requested.
        val bytesRead = Files.newInputStream(path).use { input ->
            var offset = 0
            while (offset < signature.size) {
                val count = input.read(signature, offset, signature.size - offset)
                if (count < 0) break
                offset += count
            }
            offset
        }
        return bytesRead == CHD_SIGNATURE.size && signature.contentEquals(CHD_SIGNATURE)
    }

    @Suppress("DEPRECATION")
    private fun readArchiveEntries(
        archivePath: Path,
        format: ArchiveFormat,
        safeName: String,
    ): List<ArchiveEntry> = try {
        val entries = when (format) {
            ArchiveFormat.SEVEN_ZIP -> SevenZFile(archivePath.toFile()).use { archive ->
                archive.entries.map { ArchiveEntry(it.name.orEmpty(), it.size, it.isDirectory) }
            }
            ArchiveFormat.ZIP -> ZipFile(archivePath.toFile()).use { archive ->
                archive.entries().asSequence()
                    .map { ArchiveEntry(it.name, it.size, it.isDirectory) }
                    .toList()
            }
        }
        if (entries.size > MAX_ARCHIVE_ENTRIES) {
            throw RomContentStagingException(
                "downloaded ${format.label} ROM '$safeName' has ${entries.size} entries; limit is $MAX_ARCHIVE_ENTRIES",
                failure = RomContentStagingFailure.UnsafeContent,
            )
        }
        entries.forEach { entry ->
            if (entry.name.isBlank() ||
                entry.name.startsWith('/') ||
                entry.name.startsWith('\\') ||
                entry.name.split('/', '\\').any { it == ".." } ||
                (entry.name.length >= 2 && entry.name[1] == ':')
            ) {
                throw RomContentStagingException(
                    "archive contains an unsafe entry path: ${entry.name}",
                    failure = RomContentStagingFailure.UnsafeContent,
                )
            }
        }
        entries.filterNot { it.isDirectory }
    } catch (e: RomContentStagingException) {
        throw e
    } catch (e: Exception) {
        throw RomContentStagingException(
            "cannot read downloaded ${format.label} ROM '$safeName': ${e.message}",
            e,
            failure = RomContentStagingFailure.CorruptContent,
        )
    }

    @Suppress("DEPRECATION")
    private fun extractArchiveEntry(
        archivePath: Path,
        format: ArchiveFormat,
        selected: ArchiveEntry,
        destination: Path,
        limits: ArchiveExtractionLimits,
    ) {
        when (format) {
            ArchiveFormat.SEVEN_ZIP -> SevenZFile(archivePath.toFile()).use { archive ->
                var entry = archive.nextEntry
                while (entry != null && entry.name != selected.name) {
                    entry = archive.nextEntry
                }
                if (entry == null) {
                    throw RomContentStagingException(
                        "archived ROM '${selected.name}' cannot be read",
                        failure = RomContentStagingFailure.CorruptContent,
                    )
                }
                Files.newOutputStream(destination, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                    .use { output ->
                        copyArchiveBytes(
                            archive::read,
                            output::write,
                            selected,
                            Files.size(archivePath),
                            limits,
                        )
                    }
            }
            ArchiveFormat.ZIP -> ZipFile(archivePath.toFile()).use { archive ->
                val entry = archive.getEntry(selected.name)
                    ?: throw RomContentStagingException(
                        "archived ROM '${selected.name}' cannot be read",
                        failure = RomContentStagingFailure.CorruptContent,
                    )
                Files.newOutputStream(destination, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                    .use { output ->
                        archive.getInputStream(entry).use { input ->
                            val compressedSize = entry.compressedSize.takeIf { it > 0 } ?: Files.size(archivePath)
                            copyArchiveBytes(
                                input::read,
                                output::write,
                                selected,
                                compressedSize,
                                limits,
                            )
                        }
                    }
            }
        }
    }

    private fun copyArchiveBytes(
        read: (ByteArray) -> Int,
        write: (ByteArray, Int, Int) -> Unit,
        selected: ArchiveEntry,
        compressedSize: Long,
        limits: ArchiveExtractionLimits,
    ) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var copied = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            copied += count
            if (copied > limits.maxBytes) {
                throw RomContentStagingException(
                    "archived ROM '${selected.name}' exceeds the extraction size limit",
                    failure = RomContentStagingFailure.UnsafeContent,
                )
            }
            // Multiplicative comparison: integer division (`copied / compressedSize`) truncates, so a
            // ratio marginally above the limit would slip through. Multiplication is safe because
            // realistic archives are far below Long.MAX_VALUE / 1000 and extraction has a 5 GiB cap.
            if (copied > compressedSize.coerceAtLeast(1L) * limits.maxCompressionRatio) {
                throw RomContentStagingException(
                    "archived ROM '${selected.name}' exceeds the compression ratio limit",
                    failure = RomContentStagingFailure.UnsafeContent,
                )
            }
            write(buffer, 0, count)
        }
        if (copied == 0L || copied != selected.size) {
            throw RomContentStagingException(
                "archived ROM '${selected.name}' was truncated: expected ${selected.size} bytes, got $copied",
                failure = RomContentStagingFailure.CorruptContent,
            )
        }
    }

    /** Deterministic directory name for a server origin (non-alphanumerics → `_`; no traversal possible). */
    private fun originKey(origin: String): String =
        origin.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

    private fun dropPart(part: Path) = runCatching { Files.deleteIfExists(part) }

    private companion object {
        data class ArchiveEntry(val name: String, val size: Long, val isDirectory: Boolean)
        enum class ArchiveFormat(val label: String) {
            ZIP("ZIP"),
            SEVEN_ZIP("7z"),
        }

        val SEVEN_ZIP_SIGNATURE = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
        // ZIP local-file-header signature (PK\x03\x04).
        val ZIP_LOCAL_FILE_HEADER_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        // ZIP end-of-central-directory signature (PK\x05\x06), accepted for empty/edge archives.
        val ZIP_END_OF_CENTRAL_DIRECTORY_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
        val CHD_SIGNATURE = "MComprHD".toByteArray(Charsets.US_ASCII)
        const val CHD_EXTENSION = ".chd"
        /** Playlist metadata, never playable content (RomM synthesizes one into every multi-file package). */
        const val M3U_EXTENSION = ".m3u"
        const val MAX_ARCHIVE_ENTRIES = 4096
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}

private val DISC_IMAGE_EXTENSIONS = setOf(
    ".bin", ".iso", ".img", ".mdf", ".pbp", ".chd",
    ".gcm", ".tgc", ".wbfs", ".ciso", ".gcz", ".wia", ".rvz",
)
private const val MAX_EXTRACTED_CARTRIDGE_ROM_BYTES = 512L * 1024 * 1024
private const val MAX_CARTRIDGE_ROM_COMPRESSION_RATIO = 200L
// 5 GiB: covers full-capacity DVD-5 images (4,700,372,992 bytes ≈ 4.38 GiB) — e.g. the ~4.1 GiB
// "Kingdom Hearts - Re-Chain of Memories (USA)" PS2 ISO — with headroom for ISO padding, while
// remaining a bounded fail-closed cap (dual-layer DVD-9 data, 8.54 GiB, is still rejected).
private const val MAX_EXTRACTED_DISC_IMAGE_BYTES = 5L * 1024 * 1024 * 1024
private const val MAX_DISC_IMAGE_COMPRESSION_RATIO = 1000L

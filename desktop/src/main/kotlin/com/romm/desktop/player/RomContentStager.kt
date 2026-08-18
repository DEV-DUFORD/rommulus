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
import java.util.zip.ZipFile

/** Staged ROM content ready to be pinned in a player launch request. */
data class StagedContent(
    /** Path of the staged file (under the ROM cache root). */
    val path: Path,
    /** Lowercase hex SHA-256 of [path]'s current bytes. */
    val sha256: String,
)

/** Fail-closed ROM staging failure: HTTP error, empty body, size mismatch, or write failure. */
class RomContentStagingException(message: String, cause: Throwable? = null) : IOException(message, cause)

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
 * Production [RomContentStager]: downloads via the authenticated OkHttp client (the same Bearer +
 * cookie + CSRF stack as images and BIOS staging) and stages under `cacheDir/roms/<fileName>` —
 * the "roms" cache subdirectory Android maps for ROM content, mirrored on desktop (§9: ROM content
 * is rebuildable cache; saves never live here).
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
 * detail API's file name may omit the archive extension), then its sole file is validated against
 * the selected core's supported extensions and extracted atomically with the same safety limits
 * as Android.
 *
 * All operations are blocking (synchronous OkHttp + file I/O); callers must run this off the UI
 * thread ([DesktopAppCoordinator.launchPlayer] already does — see its KDoc).
 */
class OkHttpRomContentStager(
    private val client: OkHttpClient,
    private val originProvider: () -> String?,
    private val romCacheDir: Path,
) : RomContentStager {

    override fun stage(
        romId: Long,
        fileName: String,
        expectedSizeBytes: Long,
        supportedExtensions: Set<String>,
    ): StagedContent {
        if (fileName.isBlank()) {
            throw RomContentStagingException("ROM file name is blank; cannot stage content for rom $romId")
        }
        val origin = originProvider().orEmpty()
        if (origin.isBlank()) {
            throw RomContentStagingException("RomM origin not configured; cannot download ROM content")
        }

        // Defend against path traversal in a server-provided file name: the cached file must stay
        // inside the ROM cache root.
        val safeName = fileName.replace('/', '_').replace('\\', '_')
            .takeIf { it.isNotEmpty() && it != "." && it != ".." } ?: "_"
        val destination = romCacheDir.resolve(safeName)

        runCatching { Files.createDirectories(romCacheDir) }
            .getOrElse { throw RomContentStagingException("cannot create ROM cache directory ${romCacheDir}: ${it.message}", it) }

        // Reuse: already staged and the size matches (or is unknown) — skip the download.
        if (!Files.isSymbolicLink(destination) && Files.isRegularFile(destination)) {
            val existingSize = runCatching { Files.size(destination) }.getOrNull()
            if (existingSize != null && (expectedSizeBytes <= 0 || existingSize == expectedSizeBytes)) {
                // Hash from disk every time: correctness first. Caching the hash persistently is a
                // later optimization for large cores — GB-class ROMs make re-hashing expensive.
                return prepareContent(destination, safeName, supportedExtensions)
            }
        }

        val part = romCacheDir.resolve("$safeName.part")
        try {
            val url = RommApi.romContentUrl(origin, romId, fileName)
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                when {
                    !response.isSuccessful ->
                        throw RomContentStagingException("ROM download failed: HTTP ${response.code} for $url")
                    response.body == null ->
                        throw RomContentStagingException("ROM download returned no body: $url")
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
            throw RomContentStagingException("ROM download failed for '$fileName': ${e.message}", e)
        }

        val size = runCatching { Files.size(part) }.getOrElse {
            dropPart(part)
            throw RomContentStagingException("staged ROM unreadable: '$fileName'", it)
        }
        if (size == 0L) {
            dropPart(part)
            throw RomContentStagingException("ROM download was empty: '$fileName'")
        }
        if (expectedSizeBytes > 0 && size != expectedSizeBytes) {
            dropPart(part)
            throw RomContentStagingException(
                "ROM size mismatch for '$fileName': expected $expectedSizeBytes bytes, got $size",
            )
        }

        runCatching {
            Files.move(part, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            dropPart(part)
            throw RomContentStagingException("failed to stage ROM '$fileName': ${it.message}", it)
        }

        return prepareContent(destination, safeName, supportedExtensions)
    }

    private fun prepareContent(
        downloadedFile: Path,
        safeName: String,
        supportedExtensions: Set<String>,
    ): StagedContent {
        val archiveFormat = detectArchiveFormat(downloadedFile)
        if (archiveFormat == null) {
            return StagedContent(downloadedFile, SecureFiles.sha256Hex(downloadedFile))
        }

        val normalizedExtensions = supportedExtensions
            .map { extension ->
                extension.lowercase(Locale.ROOT).let { if (it.startsWith('.')) it else ".$it" }
            }
            .toSet()
        if (normalizedExtensions.isEmpty()) {
            throw RomContentStagingException(
                "downloaded ROM '$safeName' is an archive, but the selected core declares no supported file extensions",
            )
        }

        val entries = readArchiveEntries(downloadedFile, archiveFormat, safeName)
        if (entries.size != 1) {
            throw RomContentStagingException(
                "downloaded ${archiveFormat.label} ROM '$safeName' must contain exactly one file; found ${entries.size}",
            )
        }

        val selected = entries.single()
        val extension = normalizedExtensions.firstOrNull {
            selected.name.lowercase(Locale.ROOT).endsWith(it)
        } ?: throw RomContentStagingException(
            "archived file '${selected.name}' is not supported by the selected core",
        )
        if (selected.size < 0 || selected.size > MAX_EXTRACTED_ROM_BYTES) {
            throw RomContentStagingException(
                "archived ROM '${selected.name}' has an invalid or unsupported size: ${selected.size} bytes",
            )
        }
        val extracted = romCacheDir.resolve("$safeName$extension")
        val part = romCacheDir.resolve("$safeName$extension.extract.part")

        try {
            extractArchiveEntry(downloadedFile, archiveFormat, selected, part)
            Files.move(part, extracted, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: RomContentStagingException) {
            dropPart(part)
            throw e
        } catch (e: IOException) {
            dropPart(part)
            throw RomContentStagingException("failed to extract archived ROM '${selected.name}': ${e.message}", e)
        }

        return StagedContent(extracted, SecureFiles.sha256Hex(extracted))
    }

    private fun detectArchiveFormat(path: Path): ArchiveFormat? {
        val signature = ByteArray(SEVEN_ZIP_SIGNATURE.size)
        val bytesRead = Files.newInputStream(path).use { it.read(signature) }
        if (bytesRead == signature.size && signature.contentEquals(SEVEN_ZIP_SIGNATURE)) {
            return ArchiveFormat.SEVEN_ZIP
        }
        if (bytesRead >= ZIP_PREFIX.size && signature.copyOf(ZIP_PREFIX.size).contentEquals(ZIP_PREFIX)) {
            return ArchiveFormat.ZIP
        }
        return null
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
            )
        }
        entries.forEach { entry ->
            if (entry.name.isBlank() ||
                entry.name.startsWith('/') ||
                entry.name.startsWith('\\') ||
                entry.name.split('/', '\\').any { it == ".." } ||
                (entry.name.length >= 2 && entry.name[1] == ':')
            ) {
                throw RomContentStagingException("archive contains an unsafe entry path: ${entry.name}")
            }
        }
        entries.filterNot { it.isDirectory }
    } catch (e: RomContentStagingException) {
        throw e
    } catch (e: Exception) {
        throw RomContentStagingException(
            "cannot read downloaded ${format.label} ROM '$safeName': ${e.message}",
            e,
        )
    }

    @Suppress("DEPRECATION")
    private fun extractArchiveEntry(
        archivePath: Path,
        format: ArchiveFormat,
        selected: ArchiveEntry,
        destination: Path,
    ) {
        when (format) {
            ArchiveFormat.SEVEN_ZIP -> SevenZFile(archivePath.toFile()).use { archive ->
                var entry = archive.nextEntry
                while (entry != null && entry.name != selected.name) {
                    entry = archive.nextEntry
                }
                if (entry == null) {
                    throw RomContentStagingException("archived ROM '${selected.name}' cannot be read")
                }
                Files.newOutputStream(destination, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                    .use { output -> copyArchiveBytes(archive::read, output::write, selected, Files.size(archivePath)) }
            }
            ArchiveFormat.ZIP -> ZipFile(archivePath.toFile()).use { archive ->
                val entry = archive.getEntry(selected.name)
                    ?: throw RomContentStagingException("archived ROM '${selected.name}' cannot be read")
                Files.newOutputStream(destination, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                    .use { output ->
                        archive.getInputStream(entry).use { input ->
                            val compressedSize = entry.compressedSize.takeIf { it > 0 } ?: Files.size(archivePath)
                            copyArchiveBytes(input::read, output::write, selected, compressedSize)
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
    ) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var copied = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            copied += count
            if (copied > MAX_EXTRACTED_ROM_BYTES) {
                throw RomContentStagingException("archived ROM '${selected.name}' exceeds the extraction size limit")
            }
            if (copied / compressedSize.coerceAtLeast(1L) > MAX_COMPRESSION_RATIO) {
                throw RomContentStagingException("archived ROM '${selected.name}' exceeds the compression ratio limit")
            }
            write(buffer, 0, count)
        }
        if (copied == 0L || copied != selected.size) {
            throw RomContentStagingException(
                "archived ROM '${selected.name}' was truncated: expected ${selected.size} bytes, got $copied",
            )
        }
    }

    private fun dropPart(part: Path) = runCatching { Files.deleteIfExists(part) }

    private companion object {
        data class ArchiveEntry(val name: String, val size: Long, val isDirectory: Boolean)
        enum class ArchiveFormat(val label: String) {
            ZIP("ZIP"),
            SEVEN_ZIP("7z"),
        }

        val SEVEN_ZIP_SIGNATURE = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
        val ZIP_PREFIX = byteArrayOf(0x50, 0x4B)
        const val MAX_ARCHIVE_ENTRIES = 4096
        const val MAX_EXTRACTED_ROM_BYTES = 512L * 1024 * 1024
        const val MAX_COMPRESSION_RATIO = 200L
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}

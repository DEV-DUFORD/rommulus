package com.romm.desktop.player

import com.romm.androidtv.romm.RommApi
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

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
    fun stage(romId: Long, fileName: String, expectedSizeBytes: Long): StagedContent
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
 * leaves a partial destination behind.
 *
 * All operations are blocking (synchronous OkHttp + file I/O); callers must run this off the UI
 * thread ([DesktopAppCoordinator.launchPlayer] already does — see its KDoc).
 */
class OkHttpRomContentStager(
    private val client: OkHttpClient,
    private val originProvider: () -> String?,
    private val romCacheDir: Path,
) : RomContentStager {

    override fun stage(romId: Long, fileName: String, expectedSizeBytes: Long): StagedContent {
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
                return StagedContent(destination, SecureFiles.sha256Hex(destination))
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

        return StagedContent(destination, SecureFiles.sha256Hex(destination))
    }

    private fun dropPart(part: Path) = runCatching { Files.deleteIfExists(part) }
}

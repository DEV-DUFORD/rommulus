@file:Suppress("UsableSpace") // See the rationale on AtomicFileStore.hasSufficientSpace.

package com.romm.androidtv.romm

import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.cache.ArchiveExtractionOutcome
import com.romm.androidtv.cache.AtomicFileStore
import com.romm.androidtv.cache.CacheEntryKind
import com.romm.androidtv.cache.ContentCache
import com.romm.androidtv.cache.SevenZArchiveExtractor
import com.romm.androidtv.cache.ZipArchiveExtractor
import com.romm.androidtv.emulation.model.CoreManifest
import com.romm.androidtv.emulation.model.LaunchSpec
import com.romm.androidtv.network.extractServerKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID

/**
 * Fetches canonical ROM metadata and stages content for launch
 * (LIBRETRO_REFACTOR.md sections 6 and 10).
 *
 * Implementations use RomM's native endpoints (`GET /api/roms/{id}`,
 * `GET /api/roms/{id}/content/{file_name}`) rather than constructing server
 * filesystem paths, and never expose a partially-downloaded file.
 */
interface RomRepository {
    /** Fetches canonical metadata for one ROM ID. */
    suspend fun fetchRomMetadata(romId: Long): RomMetadataResult

    /**
     * Ensures the ROM's content is downloaded, validated, and staged in
     * app-private storage, returning a [StagingOutcome] describing the result.
     * Only ever produces a [StagingOutcome.Success] when every check in the
     * download pipeline (section 10) passed; never partially stages content.
     */
    suspend fun stageForLaunch(romId: Long): StagingOutcome
}

sealed interface RomMetadataResult {
    data class Success(val rom: RomInfo) : RomMetadataResult
    data class Failure(val error: RommApiError) : RomMetadataResult
}

/**
 * Outcome of [RomRepository.stageForLaunch]. Every failure case is a distinct,
 * actionable variant (LIBRETRO_REFACTOR.md section 12: "Show distinct errors
 * for authentication, download, firmware, unsupported content... content
 * load") rather than a single generic exception.
 */
sealed interface StagingOutcome {
    data class Success(val launchSpec: LaunchSpec) : StagingOutcome

    /** The ROM's platform has no core with [com.romm.androidtv.emulation.model.CoreLicenseFinding.approved] == true. */
    data class NoApprovedCore(val platformSlug: String) : StagingOutcome

    /** Section 10: initial support explicitly rejects multi-file content with a clear message. */
    data class UnsupportedMultiFile(val fileCount: Int) : StagingOutcome

    /**
     * The ROM's single file is an archive whose format isn't supported for
     * extraction (e.g. rar). [extension] is the lowercased file extension
     * that was detected.
     */
    data class UnsupportedArchiveFormat(val extension: String) : StagingOutcome

    /**
     * The ROM's single file was a supported archive format, but extracting
     * its raw ROM bytes failed a section-10 safety check (path traversal,
     * excessive entry count, decompression-ratio/zip-bomb limit, multiple
     * ambiguous entries, or a malformed archive). [reason] is human-readable.
     */
    data class ArchiveExtractionFailed(val reason: String) : StagingOutcome

    object RomNotFound : StagingOutcome

    /** The session is no longer valid; the caller must re-authenticate before retrying. */
    object AuthExpired : StagingOutcome

    data class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long) : StagingOutcome

    /** Size or hash verification failed; the interrupted/corrupted download was discarded, never staged. */
    data class CorruptedDownload(val reason: String) : StagingOutcome

    data class NetworkError(val message: String) : StagingOutcome
}

/**
 * Real implementation backed by [RommApi] for metadata/network and
 * [ContentCache] for identity-keyed, quota-limited, crash-safe storage.
 *
 * Cache identity is scoped to the authenticated server + user
 * ([SessionStore.current]) so switching accounts or servers can never reuse
 * another scope's cached bytes. If no verified session is on record, staging
 * fails as [StagingOutcome.AuthExpired] rather than guessing a scope.
 */
class RomRepositoryImpl(
    private val client: OkHttpClient,
    private val sessionStore: SessionStore,
    private val contentCache: ContentCache,
    /**
     * Resolves a RomM platform slug to an approved [com.romm.androidtv.emulation.model.CoreLicenseFinding.coreId],
     * or null if no approved core supports it. Defaults to the real, currently-always-empty
     * [CoreManifest.approvedEntries] — do not change this default to something that returns a
     * coreId in production; tests inject a fake resolver instead, so this pipeline's download/
     * cache mechanics can be exercised without ever flipping a [CoreManifest] entry's `approved`
     * flag just to make a test reach further into the pipeline.
     */
    private val resolveApprovedCoreId: (platformSlug: String) -> String? = { platformSlug ->
        CoreManifest.approvedEntries().find { it.supportedSystems.contains(platformSlug) }?.coreId
    },
    /**
     * Advanced, opt-in setting (`SettingsRepository.verifySha1OnLaunch`, off by
     * default): whether to verify a ROM's declared `sha1_hash` before letting
     * it launch. Read fresh on every [stageForLaunch] call (not captured at
     * construction time) so toggling it in Settings takes effect immediately,
     * without requiring an app restart.
     */
    private val verifySha1OnLaunch: () -> Boolean = { false },
) : RomRepository {

    /** Returns [sha1] when the integrity check is enabled, or blank to skip it (see below). */
    private fun effectiveDeclaredSha1(sha1: String): String = if (verifySha1OnLaunch()) sha1 else ""

    override suspend fun fetchRomMetadata(romId: Long): RomMetadataResult = withContext(Dispatchers.IO) {
        val origin = requireOrigin() ?: return@withContext RomMetadataResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        when (val result = RommApi.fetchRomInfo(client, origin, romId)) {
            is RomInfoResult.Success -> RomMetadataResult.Success(result.rom)
            is RomInfoResult.Failure -> RomMetadataResult.Failure(result.error)
        }
    }

    override suspend fun stageForLaunch(romId: Long): StagingOutcome = withContext(Dispatchers.IO) {
        val session = sessionStore.current() ?: return@withContext StagingOutcome.AuthExpired
        val origin = session.origin
        val userKey = session.username ?: return@withContext StagingOutcome.AuthExpired
        val serverKey = extractServerKey(origin)

        val rom = when (val result = RommApi.fetchRomInfo(client, origin, romId)) {
            is RomInfoResult.Success -> result.rom
            is RomInfoResult.Failure -> return@withContext result.error.toStagingOutcome()
        }

        // Section 10: initial support explicitly rejects multi-file content with a clear message.
        val file = rom.files.singleOrNull()
            ?: return@withContext StagingOutcome.UnsupportedMultiFile(rom.files.size)

        val coreId = resolveApprovedCoreId(rom.platformSlug)
            ?: return@withContext StagingOutcome.NoApprovedCore(rom.platformSlug)

        // Detect an unsupported archive format by extension before spending any bandwidth on it
        // (section 10: "never trust just the declared content-type" — this is still extension-only
        // detection, just performed early rather than after a download that can't be used anyway).
        val declaredExtension = file.fileName.substringAfterLast('.', "").lowercase()
        if (declaredExtension in UNSUPPORTED_ARCHIVE_EXTENSIONS) {
            return@withContext StagingOutcome.UnsupportedArchiveFormat(declaredExtension)
        }

        val cacheKey = contentCache.key(
            kind = CacheEntryKind.ROM,
            serverKey = serverKey,
            userKey = userKey,
            remoteId = romId,
            fileIds = listOf(file.fileId),
        )

        val cached = contentCache.findValidEntry(cacheKey)
        if (cached != null) {
            return@withContext when (
                val resolution = resolveLaunchContentPath(File(cached.absolutePath), file.fileName, cached.contentHash, effectiveDeclaredSha1(file.sha1Hash))
            ) {
                is ContentPathResolution.Success ->
                    StagingOutcome.Success(buildLaunchSpec(romId, resolution.romHash, resolution.path, coreId, file.fileName))
                is ContentPathResolution.Failure -> resolution.outcome
            }
        }

        val expectedSizeBytes = file.sizeBytes.takeIf { it > 0 } ?: rom.fsSizeBytes.takeIf { it > 0 }
        if (expectedSizeBytes != null && !AtomicFileStore.hasSufficientSpace(contentCache.contentDir(CacheEntryKind.ROM), expectedSizeBytes)) {
            val available = contentCache.contentDir(CacheEntryKind.ROM).usableSpace
            return@withContext StagingOutcome.InsufficientSpace(expectedSizeBytes, available)
        }

        // RomM's declared sha1_hash describes the actual ROM content, not a compressed container —
        // for a zip/7z single file, that hash applies to the *extracted* bytes (verified after
        // extraction below), never to the archive's own compressed bytes on the wire. This whole
        // check is gated behind the opt-in "verify SHA-1 on launch" advanced setting (off by
        // default) via effectiveDeclaredSha1 — most users trust their library and don't need it.
        val isArchive = declaredExtension in EXTRACTABLE_ARCHIVE_EXTENSIONS
        val url = RommApi.romContentUrl(origin, romId, rom.fsName, fileIds = listOf(file.fileId))
        val expectedDigests = if (!isArchive && effectiveDeclaredSha1(file.sha1Hash).isNotBlank()) {
            mapOf(AtomicFileStore.SHA1 to file.sha1Hash)
        } else {
            emptyMap()
        }
        val downloadRequest = AtomicFileStore.DownloadRequest(
            client = client,
            url = url,
            destinationDir = contentCache.contentDir(CacheEntryKind.ROM),
            finalFileName = sanitizedCacheFileName(romId, file.fileId, file.fileName),
            expectedSizeBytes = expectedSizeBytes,
            expectedDigests = expectedDigests,
            digestsToCompute = setOf(AtomicFileStore.SHA256),
        )

        return@withContext when (val outcome = AtomicFileStore.download(downloadRequest)) {
            is AtomicFileStore.DownloadOutcome.Success -> {
                val contentHash = outcome.digests.getValue(AtomicFileStore.SHA256)
                contentCache.record(
                    key = cacheKey,
                    kind = CacheEntryKind.ROM,
                    serverKey = serverKey,
                    userKey = userKey,
                    remoteId = romId,
                    fileIdsKey = file.fileId.toString(),
                    contentHash = contentHash,
                    file = outcome.file,
                )
                when (val resolution = resolveLaunchContentPath(outcome.file, file.fileName, contentHash, effectiveDeclaredSha1(file.sha1Hash))) {
                    is ContentPathResolution.Success ->
                        StagingOutcome.Success(buildLaunchSpec(romId, resolution.romHash, resolution.path, coreId, file.fileName))
                    is ContentPathResolution.Failure -> resolution.outcome
                }
            }
            is AtomicFileStore.DownloadOutcome.InsufficientSpace ->
                StagingOutcome.InsufficientSpace(outcome.requiredBytes, outcome.availableBytes)
            is AtomicFileStore.DownloadOutcome.SizeMismatch ->
                StagingOutcome.CorruptedDownload("size mismatch: expected ${outcome.expectedBytes}, got ${outcome.actualBytes}")
            is AtomicFileStore.DownloadOutcome.HashMismatch ->
                StagingOutcome.CorruptedDownload("${outcome.algorithm} mismatch: expected ${outcome.expectedHash}, got ${outcome.actualHash}")
            is AtomicFileStore.DownloadOutcome.HttpError ->
                if (outcome.code == 401 || outcome.code == 403) StagingOutcome.AuthExpired
                else if (outcome.code == 404) StagingOutcome.RomNotFound
                else StagingOutcome.NetworkError("HTTP ${outcome.code}")
            is AtomicFileStore.DownloadOutcome.NetworkError -> StagingOutcome.NetworkError(outcome.message)
        }
    }

    /**
     * Resolves the path and identity hash a native core should actually be
     * launched with: [archiveFile] itself (with [archiveContentHash] as its
     * identity) if [displayFileName] doesn't look like a supported archive,
     * or the path and content hash of a memoized, extracted raw-ROM file
     * otherwise. [archiveFile]'s own hash still governs cache/eviction for
     * the *downloaded* bytes (matching RomM's declared file size), but the
     * identity handed to [LaunchSpec]/[com.romm.androidtv.emulation.model.SavePathPolicy]
     * for an archived ROM is always the hash of its *extracted* content —
     * that's what a core actually loads and what RomM's own `sha1_hash`
     * describes (RomM hashes the ROM itself, never the container it happens
     * to be shipped in), and it's what keeps save data addressed by the same
     * key regardless of which archive format a given copy was packaged as.
     */
    private fun resolveLaunchContentPath(
        archiveFile: File,
        displayFileName: String,
        archiveContentHash: String,
        declaredSha1: String,
    ): ContentPathResolution {
        // Detect by extension only — section 10: "never trust just the declared content-type".
        return when (val extension = displayFileName.substringAfterLast('.', "").lowercase()) {
            "zip" -> extractForLaunch(archiveFile, archiveContentHash, declaredSha1) { file, dir -> ZipArchiveExtractor.extractSingleEntry(file, dir) }
            "7z" -> extractForLaunch(archiveFile, archiveContentHash, declaredSha1) { file, dir -> SevenZArchiveExtractor.extractSingleEntry(file, dir) }
            "rar", "gz", "tar", "tar.gz", "7zip" -> ContentPathResolution.Failure(StagingOutcome.UnsupportedArchiveFormat(extension))
            else -> ContentPathResolution.Success(archiveFile.absolutePath, archiveContentHash)
        }
    }

    /**
     * Runs [extractor] against [archiveFile], memoizing its output (and the
     * extracted content's own SHA-256, used as the launch identity hash)
     * under an `extracted/<archiveContentHash>/` directory so a repeat
     * launch of the same downloaded archive never re-extracts or re-hashes.
     * Extraction always happens into a fresh temp directory first and is
     * atomically renamed into place only once fully written and hash-
     * verified, so a process death mid-extraction can never leave a
     * partially-extracted or unverified file where a later launch would
     * find it.
     */
    private fun extractForLaunch(
        archiveFile: File,
        archiveContentHash: String,
        declaredSha1: String,
        extractor: (archiveFile: File, destinationDir: File) -> ArchiveExtractionOutcome,
    ): ContentPathResolution {
        val extractionRoot = File(contentCache.contentDir(CacheEntryKind.ROM), "extracted")
        val finalDir = File(extractionRoot, archiveContentHash)
        val marker = File(finalDir, EXTRACTION_COMPLETE_MARKER)
        val hashSidecar = File(finalDir, EXTRACTED_HASH_SIDECAR)
        if (marker.isFile) {
            val existing = finalDir.listFiles { candidate ->
                candidate.name != EXTRACTION_COMPLETE_MARKER && candidate.name != EXTRACTED_HASH_SIDECAR
            }?.singleOrNull()
            val recordedHash = hashSidecar.takeIf { it.isFile }?.readText()?.trim()
            if (existing != null && existing.isFile && !recordedHash.isNullOrBlank()) {
                return ContentPathResolution.Success(existing.absolutePath, recordedHash)
            }
            // Marker present but the extracted content/its recorded hash is missing/altered —
            // never trust it; re-extract.
            finalDir.deleteRecursively()
        }

        val tempDir = File(extractionRoot, ".tmp-$archiveContentHash-${UUID.randomUUID()}")
        tempDir.mkdirs()

        return when (val outcome = extractor(archiveFile, tempDir)) {
            is ArchiveExtractionOutcome.Success -> {
                val extractedSha1 = sha1Hex(outcome.file)
                if (declaredSha1.isNotBlank() && !declaredSha1.equals(extractedSha1, ignoreCase = true)) {
                    tempDir.deleteRecursively()
                    return ContentPathResolution.Failure(
                        StagingOutcome.CorruptedDownload(
                            "extracted content SHA-1 mismatch: expected $declaredSha1, got $extractedSha1"
                        )
                    )
                }
                val extractedSha256 = sha256Hex(outcome.file)
                File(tempDir, EXTRACTED_HASH_SIDECAR).writeText(extractedSha256)
                File(tempDir, EXTRACTION_COMPLETE_MARKER).writeText("")
                extractionRoot.mkdirs()
                if (tempDir.renameTo(finalDir)) {
                    ContentPathResolution.Success(File(finalDir, outcome.file.name).absolutePath, extractedSha256)
                } else {
                    // Rare cross-filesystem/concurrent-launch race — the bytes already written into
                    // tempDir are still valid and safely readable; use them directly rather than
                    // failing an extraction that actually succeeded.
                    ContentPathResolution.Success(outcome.file.absolutePath, extractedSha256)
                }
            }
            is ArchiveExtractionOutcome.MultipleEntries -> {
                tempDir.deleteRecursively()
                ContentPathResolution.Failure(StagingOutcome.UnsupportedMultiFile(outcome.count))
            }
            is ArchiveExtractionOutcome.Rejected -> {
                tempDir.deleteRecursively()
                ContentPathResolution.Failure(StagingOutcome.ArchiveExtractionFailed(outcome.reason))
            }
        }
    }

    private fun sha1Hex(file: File): String = hashHex(file, "SHA-1")

    private fun sha256Hex(file: File): String = hashHex(file, "SHA-256")

    private fun hashHex(file: File, algorithm: String): String {
        val digest = java.security.MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private sealed interface ContentPathResolution {
        data class Success(val path: String, val romHash: String) : ContentPathResolution
        data class Failure(val outcome: StagingOutcome) : ContentPathResolution
    }

    private fun buildLaunchSpec(romId: Long, romHash: String, contentPath: String, coreId: String, serverSaveFileName: String) = LaunchSpec(
        romId = romId,
        romHash = romHash,
        contentPath = contentPath,
        coreId = coreId,
        // Deliberately routed through the existing policy gate rather than a hardcoded
        // NATIVE_LIBRETRO value: PlaybackBackendPolicy.resolve() still always returns
        // WEBVIEW on this build (see config/PlaybackBackend.kt), and must keep doing so
        // regardless of anything the content-staging pipeline is capable of preparing.
        backend = com.romm.androidtv.config.PlaybackBackendPolicy.resolve(),
        sessionId = java.util.UUID.randomUUID(),
        serverSaveFileName = serverSaveFileName,
    )

    private fun requireOrigin(): String? = sessionStore.current()?.origin

    private fun RommApiError.toStagingOutcome(): StagingOutcome = when (this) {
        RommApiError.NOT_FOUND -> StagingOutcome.RomNotFound
        RommApiError.AUTH_EXPIRED -> StagingOutcome.AuthExpired
        RommApiError.SERVER_ERROR -> StagingOutcome.NetworkError("server error")
        RommApiError.NETWORK_ERROR -> StagingOutcome.NetworkError("network error")
        RommApiError.TLS_ERROR -> StagingOutcome.NetworkError("TLS error")
        RommApiError.PARSE_ERROR -> StagingOutcome.NetworkError("malformed response")
        RommApiError.ORIGIN_NOT_CONFIGURED -> StagingOutcome.NetworkError("no server configured")
    }

    private companion object {
        /** Empty sentinel file marking a fully, successfully extracted archive directory. */
        const val EXTRACTION_COMPLETE_MARKER = ".complete"

        /** Sidecar file recording the extracted content's own SHA-256, memoized alongside it. */
        const val EXTRACTED_HASH_SIDECAR = ".content-sha256"

        /** Archive formats this pipeline knows how to extract raw ROM bytes from. */
        val EXTRACTABLE_ARCHIVE_EXTENSIONS = setOf("zip", "7z")

        /** Archive formats detected by extension that this pipeline doesn't (yet) extract. */
        val UNSUPPORTED_ARCHIVE_EXTENSIONS = setOf("rar", "gz", "tar", "7zip")
    }
}

/**
 * Derives a collision-safe on-disk file name from stable RomM IDs rather than
 * the display file name alone (section 10: "Never use a display filename as
 * identity"). Two different remote records that happen to share a generic
 * file name (e.g. "rom.bin" or "bios.bin") can never collide on disk.
 */
internal fun sanitizedCacheFileName(remoteId: Long, subId: Long, displayFileName: String): String {
    val extension = displayFileName.substringAfterLast('.', missingDelimiterValue = "")
    val safeExtension = extension.filter { it.isLetterOrDigit() }.take(8)
    return if (safeExtension.isNotEmpty()) "${remoteId}_$subId.$safeExtension" else "${remoteId}_$subId"
}

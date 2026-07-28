@file:Suppress("UsableSpace") // See the rationale on AtomicFileStore.hasSufficientSpace.

package com.romm.androidtv.romm

import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.cache.AtomicFileStore
import com.romm.androidtv.cache.CacheEntryKind
import com.romm.androidtv.cache.ContentCache
import com.romm.androidtv.emulation.model.CoreManifest
import com.romm.androidtv.emulation.model.LaunchSpec
import com.romm.androidtv.network.RommOrigin
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
) : RomRepository {

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
        val serverKey = RommOrigin.parse(origin)?.host ?: origin

        val rom = when (val result = RommApi.fetchRomInfo(client, origin, romId)) {
            is RomInfoResult.Success -> result.rom
            is RomInfoResult.Failure -> return@withContext result.error.toStagingOutcome()
        }

        // Section 10: initial support explicitly rejects multi-file content with a clear message.
        val file = rom.files.singleOrNull()
            ?: return@withContext StagingOutcome.UnsupportedMultiFile(rom.files.size)

        val coreId = resolveApprovedCoreId(rom.platformSlug)
            ?: return@withContext StagingOutcome.NoApprovedCore(rom.platformSlug)

        val cacheKey = contentCache.key(
            kind = CacheEntryKind.ROM,
            serverKey = serverKey,
            userKey = userKey,
            remoteId = romId,
            fileIds = listOf(file.fileId),
        )

        val cached = contentCache.findValidEntry(cacheKey)
        if (cached != null) {
            return@withContext StagingOutcome.Success(
                buildLaunchSpec(romId, cached.contentHash, cached.absolutePath, coreId)
            )
        }

        val expectedSizeBytes = file.sizeBytes.takeIf { it > 0 } ?: rom.fsSizeBytes.takeIf { it > 0 }
        if (expectedSizeBytes != null && !AtomicFileStore.hasSufficientSpace(contentCache.contentDir(CacheEntryKind.ROM), expectedSizeBytes)) {
            val available = contentCache.contentDir(CacheEntryKind.ROM).usableSpace
            return@withContext StagingOutcome.InsufficientSpace(expectedSizeBytes, available)
        }

        val url = RommApi.romContentUrl(origin, romId, rom.fsName, fileIds = listOf(file.fileId))
        val expectedDigests = if (file.sha1Hash.isNotBlank()) {
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
                StagingOutcome.Success(buildLaunchSpec(romId, contentHash, outcome.file.absolutePath, coreId))
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

    private fun buildLaunchSpec(romId: Long, romHash: String, contentPath: String, coreId: String) = LaunchSpec(
        romId = romId,
        romHash = romHash,
        contentPath = contentPath,
        coreId = coreId,
        // Deliberately routed through the existing policy gate rather than a hardcoded
        // NATIVE_LIBRETRO value: PlaybackBackendPolicy.resolve() still always returns
        // WEBVIEW on this build (see config/PlaybackBackend.kt), and must keep doing so
        // regardless of anything the content-staging pipeline is capable of preparing.
        backend = com.romm.androidtv.config.PlaybackBackendPolicy.resolve(),
        sessionId = UUID.randomUUID().toString(),
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

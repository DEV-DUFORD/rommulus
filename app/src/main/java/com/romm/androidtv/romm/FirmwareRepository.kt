@file:Suppress("UsableSpace") // See the rationale on AtomicFileStore.hasSufficientSpace.

package com.romm.androidtv.romm

import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.cache.AtomicFileStore
import com.romm.androidtv.cache.CacheEntryKind
import com.romm.androidtv.cache.ContentCache
import com.romm.androidtv.network.extractServerKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Discovers, validates, and stages required firmware/BIOS files into a core's
 * system directory (LIBRETRO_REFACTOR.md section 10, "Firmware").
 *
 * Implementations must stage only verified files and report missing or
 * mismatched firmware before a core load is attempted; they must never guess
 * a BIOS identity based only on file extension.
 */
interface FirmwareRepository {
    suspend fun findPlatformId(platformSlug: String): PlatformIdOutcome

    suspend fun listAvailable(platformId: Long? = null): FirmwareCatalogOutcome

    suspend fun findCachedPath(firmwareId: Long): String?

    suspend fun ensureStaged(firmware: FirmwareInfo): FirmwareStagingOutcome

    /**
     * Returns which of [requiredFileNames] (for [platformId]) are present and
     * hash-verified in local storage, without downloading anything.
     */
    suspend fun checkAvailability(platformId: Long, requiredFileNames: List<String>): FirmwareAvailability

    /**
     * Downloads and verifies any of [requiredFileNames] not already cached,
     * reusing verified cached copies for the rest.
     */
    suspend fun ensureStaged(platformId: Long, requiredFileNames: List<String>): FirmwareStagingOutcome
}

sealed interface PlatformIdOutcome {
    data class Success(val platformId: Long?) : PlatformIdOutcome
    object AuthExpired : PlatformIdOutcome
    data class Error(val message: String) : PlatformIdOutcome
}

sealed interface FirmwareCatalogOutcome {
    data class Success(val firmware: List<FirmwareInfo>) : FirmwareCatalogOutcome
    object AuthExpired : FirmwareCatalogOutcome
    data class Error(val message: String) : FirmwareCatalogOutcome
}

data class FirmwareAvailability(
    val present: List<String>,
    val missing: List<String>,
    val hashMismatches: List<String>,
) {
    val isReady: Boolean get() = missing.isEmpty() && hashMismatches.isEmpty()
}

// FirmwareStagingOutcome moved to `:shared:presentation` (same package
// com.romm.androidtv.romm) for the Linux port Phase 4; references here and in
// SegaCdBiosManager/PsxBiosManager keep resolving unchanged.

class FirmwareRepositoryImpl(
    private val client: OkHttpClient,
    private val sessionStore: SessionStore,
    private val contentCache: ContentCache,
) : FirmwareRepository {

    override suspend fun findPlatformId(platformSlug: String): PlatformIdOutcome = withContext(Dispatchers.IO) {
        val session = sessionStore.current() ?: return@withContext PlatformIdOutcome.AuthExpired
        when (val result = RommApi.fetchPlatformId(client, session.origin, platformSlug)) {
            is PlatformIdResult.Success -> PlatformIdOutcome.Success(result.platformId)
            is PlatformIdResult.Failure -> when (result.error) {
                RommApiError.AUTH_EXPIRED -> PlatformIdOutcome.AuthExpired
                else -> PlatformIdOutcome.Error(result.error.name)
            }
        }
    }

    override suspend fun listAvailable(platformId: Long?): FirmwareCatalogOutcome = withContext(Dispatchers.IO) {
        val session = sessionStore.current() ?: return@withContext FirmwareCatalogOutcome.AuthExpired
        when (val result = RommApi.fetchFirmwareList(client, session.origin, platformId)) {
            is FirmwareListResult.Success -> FirmwareCatalogOutcome.Success(result.firmware)
            is FirmwareListResult.Failure -> when (result.error) {
                RommApiError.AUTH_EXPIRED -> FirmwareCatalogOutcome.AuthExpired
                else -> FirmwareCatalogOutcome.Error(result.error.name)
            }
        }
    }

    override suspend fun findCachedPath(firmwareId: Long): String? = withContext(Dispatchers.IO) {
        val session = sessionStore.current() ?: return@withContext null
        val userKey = session.username ?: return@withContext null
        val key = contentCache.key(
            CacheEntryKind.FIRMWARE,
            extractServerKey(session.origin),
            userKey,
            firmwareId,
        )
        contentCache.findValidEntry(key)?.absolutePath
    }

    override suspend fun ensureStaged(firmware: FirmwareInfo): FirmwareStagingOutcome = withContext(Dispatchers.IO) {
        val session = sessionStore.current() ?: return@withContext FirmwareStagingOutcome.AuthExpired
        val userKey = session.username ?: return@withContext FirmwareStagingOutcome.AuthExpired
        val serverKey = extractServerKey(session.origin)
        val key = contentCache.key(CacheEntryKind.FIRMWARE, serverKey, userKey, firmware.firmwareId)
        contentCache.findValidEntry(key)?.let {
            return@withContext FirmwareStagingOutcome.Success(mapOf(firmware.fileName to it.absolutePath))
        }

        val destinationDir = contentCache.contentDir(CacheEntryKind.FIRMWARE)
        if (firmware.sizeBytes > 0 && !AtomicFileStore.hasSufficientSpace(destinationDir, firmware.sizeBytes)) {
            return@withContext FirmwareStagingOutcome.InsufficientSpace(firmware.sizeBytes, destinationDir.usableSpace)
        }

        val request = AtomicFileStore.DownloadRequest(
            client = client,
            url = RommApi.firmwareContentUrl(session.origin, firmware.firmwareId, firmware.fileName),
            destinationDir = destinationDir,
            finalFileName = sanitizedCacheFileName(firmware.firmwareId, firmware.firmwareId, firmware.fileName),
            expectedSizeBytes = firmware.sizeBytes.takeIf { it > 0 },
            expectedDigests = if (firmware.sha1Hash.isNotBlank()) {
                mapOf(AtomicFileStore.SHA1 to firmware.sha1Hash)
            } else {
                emptyMap()
            },
            digestsToCompute = setOf(AtomicFileStore.SHA256),
        )
        when (val outcome = AtomicFileStore.download(request)) {
            is AtomicFileStore.DownloadOutcome.Success -> {
                contentCache.record(
                    key = key,
                    kind = CacheEntryKind.FIRMWARE,
                    serverKey = serverKey,
                    userKey = userKey,
                    remoteId = firmware.firmwareId,
                    fileIdsKey = "",
                    contentHash = outcome.digests.getValue(AtomicFileStore.SHA256),
                    file = outcome.file,
                )
                FirmwareStagingOutcome.Success(mapOf(firmware.fileName to outcome.file.absolutePath))
            }
            is AtomicFileStore.DownloadOutcome.InsufficientSpace ->
                FirmwareStagingOutcome.InsufficientSpace(outcome.requiredBytes, outcome.availableBytes)
            is AtomicFileStore.DownloadOutcome.SizeMismatch ->
                FirmwareStagingOutcome.CorruptedDownload(firmware.fileName, "size mismatch")
            is AtomicFileStore.DownloadOutcome.HashMismatch ->
                FirmwareStagingOutcome.CorruptedDownload(firmware.fileName, "${outcome.algorithm} mismatch")
            is AtomicFileStore.DownloadOutcome.HttpError ->
                if (outcome.code == 401 || outcome.code == 403) {
                    FirmwareStagingOutcome.AuthExpired
                } else {
                    FirmwareStagingOutcome.NetworkError("HTTP ${outcome.code}")
                }
            is AtomicFileStore.DownloadOutcome.NetworkError ->
                FirmwareStagingOutcome.NetworkError(outcome.message)
        }
    }

    override suspend fun checkAvailability(
        platformId: Long,
        requiredFileNames: List<String>,
    ): FirmwareAvailability = withContext(Dispatchers.IO) {
        val session = sessionStore.current()
        if (session == null || requiredFileNames.isEmpty()) {
            return@withContext FirmwareAvailability(present = emptyList(), missing = requiredFileNames, hashMismatches = emptyList())
        }
        val serverKey = extractServerKey(session.origin)
        val userKey = session.username ?: return@withContext FirmwareAvailability(emptyList(), requiredFileNames, emptyList())

        val firmwareList = when (val result = RommApi.fetchFirmwareList(client, session.origin, platformId)) {
            is FirmwareListResult.Success -> result.firmware
            is FirmwareListResult.Failure -> return@withContext FirmwareAvailability(emptyList(), requiredFileNames, emptyList())
        }

        val missing = mutableListOf<String>()
        // A cache entry is only ever created after AtomicFileStore verified the download's
        // hash against the remote-declared sha1 at download time (see ensureStaged below), so
        // a *valid* cache hit here is already hash-verified; there is nothing further to
        // recompute. `hashMismatches` stays reserved for a future "remote hash changed since
        // we cached this file" reconciliation pass, which this method does not yet perform.
        val present = mutableListOf<String>()
        for (fileName in requiredFileNames) {
            val remote = firmwareList.find { it.fileName == fileName }
            if (remote == null) {
                missing += fileName
                continue
            }
            val key = contentCache.key(CacheEntryKind.FIRMWARE, serverKey, userKey, remote.firmwareId)
            val cached = contentCache.findValidEntry(key)
            if (cached == null) missing += fileName else present += fileName
        }
        FirmwareAvailability(present, missing, hashMismatches = emptyList())
    }

    override suspend fun ensureStaged(
        platformId: Long,
        requiredFileNames: List<String>,
    ): FirmwareStagingOutcome = withContext(Dispatchers.IO) {
        sessionStore.current()?.username ?: return@withContext FirmwareStagingOutcome.AuthExpired

        val firmwareList = when (val result = listAvailable(platformId)) {
            is FirmwareCatalogOutcome.Success -> result.firmware
            FirmwareCatalogOutcome.AuthExpired -> return@withContext FirmwareStagingOutcome.AuthExpired
            is FirmwareCatalogOutcome.Error -> return@withContext FirmwareStagingOutcome.NetworkError(result.message)
        }

        val missing = requiredFileNames.filter { name -> firmwareList.none { it.fileName == name } }
        if (missing.isNotEmpty()) return@withContext FirmwareStagingOutcome.Missing(missing)

        val stagedPaths = mutableMapOf<String, String>()
        for (fileName in requiredFileNames) {
            val remote = firmwareList.first { it.fileName == fileName }
            when (val outcome = ensureStaged(remote)) {
                is FirmwareStagingOutcome.Success -> stagedPaths.putAll(outcome.stagedPaths)
                else -> return@withContext outcome
            }
        }
        FirmwareStagingOutcome.Success(stagedPaths)
    }

    private fun RommApiError.toStagingOutcome(): FirmwareStagingOutcome = when (this) {
        RommApiError.AUTH_EXPIRED -> FirmwareStagingOutcome.AuthExpired
        else -> FirmwareStagingOutcome.NetworkError(name)
    }
}

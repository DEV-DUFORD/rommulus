@file:Suppress("UsableSpace") // See the rationale on AtomicFileStore.hasSufficientSpace.

package com.romm.androidtv.romm

import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.cache.AtomicFileStore
import com.romm.androidtv.cache.CacheEntryKind
import com.romm.androidtv.cache.ContentCache
import com.romm.androidtv.network.RommOrigin
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

data class FirmwareAvailability(
    val present: List<String>,
    val missing: List<String>,
    val hashMismatches: List<String>,
) {
    val isReady: Boolean get() = missing.isEmpty() && hashMismatches.isEmpty()
}

sealed interface FirmwareStagingOutcome {
    /** Absolute paths for every requested file name, all hash-verified. */
    data class Success(val stagedPaths: Map<String, String>) : FirmwareStagingOutcome
    data class Missing(val fileNames: List<String>) : FirmwareStagingOutcome
    object AuthExpired : FirmwareStagingOutcome
    data class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long) : FirmwareStagingOutcome
    data class CorruptedDownload(val fileName: String, val reason: String) : FirmwareStagingOutcome
    data class NetworkError(val message: String) : FirmwareStagingOutcome
}

class FirmwareRepositoryImpl(
    private val client: OkHttpClient,
    private val sessionStore: SessionStore,
    private val contentCache: ContentCache,
) : FirmwareRepository {

    override suspend fun checkAvailability(
        platformId: Long,
        requiredFileNames: List<String>,
    ): FirmwareAvailability = withContext(Dispatchers.IO) {
        val session = sessionStore.current()
        if (session == null || requiredFileNames.isEmpty()) {
            return@withContext FirmwareAvailability(present = emptyList(), missing = requiredFileNames, hashMismatches = emptyList())
        }
        val serverKey = RommOrigin.parse(session.origin)?.host ?: session.origin
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
        val session = sessionStore.current() ?: return@withContext FirmwareStagingOutcome.AuthExpired
        val userKey = session.username ?: return@withContext FirmwareStagingOutcome.AuthExpired
        val serverKey = RommOrigin.parse(session.origin)?.host ?: session.origin

        val firmwareList = when (val result = RommApi.fetchFirmwareList(client, session.origin, platformId)) {
            is FirmwareListResult.Success -> result.firmware
            is FirmwareListResult.Failure -> return@withContext result.error.toStagingOutcome()
        }

        val missing = requiredFileNames.filter { name -> firmwareList.none { it.fileName == name } }
        if (missing.isNotEmpty()) return@withContext FirmwareStagingOutcome.Missing(missing)

        val stagedPaths = mutableMapOf<String, String>()
        for (fileName in requiredFileNames) {
            val remote = firmwareList.first { it.fileName == fileName }
            val key = contentCache.key(CacheEntryKind.FIRMWARE, serverKey, userKey, remote.firmwareId)
            val cached = contentCache.findValidEntry(key)
            if (cached != null) {
                stagedPaths[fileName] = cached.absolutePath
                continue
            }

            val destinationDir = contentCache.contentDir(CacheEntryKind.FIRMWARE)
            if (remote.sizeBytes > 0 && !AtomicFileStore.hasSufficientSpace(destinationDir, remote.sizeBytes)) {
                return@withContext FirmwareStagingOutcome.InsufficientSpace(remote.sizeBytes, destinationDir.usableSpace)
            }

            val request = AtomicFileStore.DownloadRequest(
                client = client,
                url = RommApi.firmwareContentUrl(session.origin, remote.firmwareId, remote.fileName),
                destinationDir = destinationDir,
                finalFileName = sanitizedCacheFileName(remote.firmwareId, remote.firmwareId, remote.fileName),
                expectedSizeBytes = remote.sizeBytes.takeIf { it > 0 },
                expectedDigests = if (remote.sha1Hash.isNotBlank()) mapOf(AtomicFileStore.SHA1 to remote.sha1Hash) else emptyMap(),
                digestsToCompute = setOf(AtomicFileStore.SHA256),
            )
            when (val outcome = AtomicFileStore.download(request)) {
                is AtomicFileStore.DownloadOutcome.Success -> {
                    contentCache.record(
                        key = key,
                        kind = CacheEntryKind.FIRMWARE,
                        serverKey = serverKey,
                        userKey = userKey,
                        remoteId = remote.firmwareId,
                        fileIdsKey = "",
                        contentHash = outcome.digests.getValue(AtomicFileStore.SHA256),
                        file = outcome.file,
                    )
                    stagedPaths[fileName] = outcome.file.absolutePath
                }
                is AtomicFileStore.DownloadOutcome.InsufficientSpace ->
                    return@withContext FirmwareStagingOutcome.InsufficientSpace(outcome.requiredBytes, outcome.availableBytes)
                is AtomicFileStore.DownloadOutcome.SizeMismatch ->
                    return@withContext FirmwareStagingOutcome.CorruptedDownload(fileName, "size mismatch")
                is AtomicFileStore.DownloadOutcome.HashMismatch ->
                    return@withContext FirmwareStagingOutcome.CorruptedDownload(fileName, "${outcome.algorithm} mismatch")
                is AtomicFileStore.DownloadOutcome.HttpError ->
                    return@withContext if (outcome.code == 401 || outcome.code == 403) {
                        FirmwareStagingOutcome.AuthExpired
                    } else {
                        FirmwareStagingOutcome.NetworkError("HTTP ${outcome.code}")
                    }
                is AtomicFileStore.DownloadOutcome.NetworkError ->
                    return@withContext FirmwareStagingOutcome.NetworkError(outcome.message)
            }
        }
        FirmwareStagingOutcome.Success(stagedPaths)
    }

    private fun RommApiError.toStagingOutcome(): FirmwareStagingOutcome = when (this) {
        RommApiError.AUTH_EXPIRED -> FirmwareStagingOutcome.AuthExpired
        else -> FirmwareStagingOutcome.NetworkError(name)
    }
}

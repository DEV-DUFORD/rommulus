@file:OptIn(ExperimentalStdlibApi::class)

package com.romm.androidtv.romm

import com.romm.androidtv.network.RommOrigin
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.KotlinJsonAdapterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/**
 * Typed models and network calls for RomM's ROM/file/firmware endpoints
 * (LIBRETRO_REFACTOR.md section 10). Mirrors the real backend response shapes
 * audited against `backend/endpoints/responses/rom.py` and
 * `backend/endpoints/responses/firmware.py` in the reference RomM source —
 * only the fields this client actually needs are modeled; every other field
 * RomM returns is ignored by Moshi's default lenient parsing.
 *
 * Following [com.romm.androidtv.network.AuthService]'s existing convention:
 * pure JSON-parsing functions are separated from the network call functions
 * so parsing logic is unit-testable without a live/mock server.
 */

/** One physical file backing a ROM (`RomFileSchema` in the reference backend). */
data class RomFileInfo(
    val fileId: Long,
    val fileName: String,
    val sizeBytes: Long,
    val isTopLevel: Boolean,
    /** Verified-by-server content hashes, when RomM has them on file. Empty string if absent. */
    val sha1Hash: String,
    val md5Hash: String,
    val crcHash: String,
)

/** Canonical ROM metadata needed to select a core and stage content (`RomSchema`). */
data class RomInfo(
    val romId: Long,
    /** The exact file name the content endpoint expects as its `{file_name}` path segment. */
    val fsName: String,
    val fsSizeBytes: Long,
    val platformSlug: String,
    val hasMultipleFiles: Boolean,
    val files: List<RomFileInfo>,
)

/** One firmware/BIOS file RomM knows about (`FirmwareSchema`). */
data class FirmwareInfo(
    val firmwareId: Long,
    val fileName: String,
    val sizeBytes: Long,
    val sha1Hash: String,
    val md5Hash: String,
    val crcHash: String,
    val isVerified: Boolean,
    val missingFromFs: Boolean = false,
    /** Raw ISO-8601 UTC `created_at` string, when the server returned one (null if absent). */
    val createdAt: String? = null,
    /** Raw ISO-8601 UTC `updated_at` string, when the server returned one (null if absent). */
    val updatedAt: String? = null,
)

sealed interface RomInfoResult {
    data class Success(val rom: RomInfo) : RomInfoResult
    data class Failure(val error: RommApiError, val httpCode: Int? = null) : RomInfoResult
}

sealed interface FirmwareListResult {
    data class Success(val firmware: List<FirmwareInfo>) : FirmwareListResult
    data class Failure(val error: RommApiError, val httpCode: Int? = null) : FirmwareListResult
}

sealed interface PlatformIdResult {
    data class Success(val platformId: Long?) : PlatformIdResult
    data class Failure(val error: RommApiError, val httpCode: Int? = null) : PlatformIdResult
}

/** Error classification shared by every RommApi network call. */
enum class RommApiError {
    NOT_FOUND,
    /** 401/403 — the session is no longer valid; the caller must re-authenticate. */
    AUTH_EXPIRED,
    SERVER_ERROR,
    NETWORK_ERROR,
    TLS_ERROR,
    PARSE_ERROR,
    ORIGIN_NOT_CONFIGURED,
}

@JsonClass(generateAdapter = false)
internal data class RomFileJson(
    val id: Long = 0,
    val file_name: String = "",
    val file_size_bytes: Long = 0,
    val is_top_level: Boolean = false,
    val sha1_hash: String? = null,
    val md5_hash: String? = null,
    val crc_hash: String? = null,
)

@JsonClass(generateAdapter = false)
internal data class RomJson(
    val id: Long = 0,
    val fs_name: String = "",
    val fs_size_bytes: Long = 0,
    val platform_slug: String = "",
    val has_multiple_files: Boolean = false,
    @Json(name = "files") val files: List<RomFileJson>? = null,
)

@JsonClass(generateAdapter = false)
internal data class FirmwareJson(
    val id: Long = 0,
    val file_name: String = "",
    val file_size_bytes: Long = 0,
    val sha1_hash: String? = null,
    val md5_hash: String? = null,
    val crc_hash: String? = null,
    val is_verified: Boolean = false,
    val missing_from_fs: Boolean = false,
    val created_at: String? = null,
    val updated_at: String? = null,
)

@JsonClass(generateAdapter = false)
internal data class PlatformIdentityJson(
    val id: Long = 0,
    val slug: String = "",
    val fs_slug: String = "",
)

object RommApi {

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
    private val romAdapter = moshi.adapter<RomJson>()
    private val firmwareListAdapter = moshi.adapter<List<FirmwareJson>>()
    private val platformListAdapter = moshi.adapter<List<PlatformIdentityJson>>()

    /** Parses the JSON body of `GET /api/roms/{id}`. Returns null on any malformed input. */
    fun parseRomInfo(body: String): RomInfo? {
        return try {
            val json = romAdapter.fromJson(body.trim()) ?: return null
            if (json.id <= 0 || json.fs_name.isBlank()) return null
            RomInfo(
                romId = json.id,
                fsName = json.fs_name,
                fsSizeBytes = json.fs_size_bytes,
                platformSlug = json.platform_slug,
                hasMultipleFiles = json.has_multiple_files,
                files = json.files.orEmpty().map {
                    RomFileInfo(
                        fileId = it.id,
                        fileName = it.file_name,
                        sizeBytes = it.file_size_bytes,
                        isTopLevel = it.is_top_level,
                        sha1Hash = it.sha1_hash.orEmpty(),
                        md5Hash = it.md5_hash.orEmpty(),
                        crcHash = it.crc_hash.orEmpty(),
                    )
                },
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Parses the JSON body of `GET /api/firmware`. Returns null on any malformed input. */
    fun parseFirmwareList(body: String): List<FirmwareInfo>? {
        return try {
            val json = firmwareListAdapter.fromJson(body.trim()) ?: return null
            json.filterNot { it.missing_from_fs }.map {
                FirmwareInfo(
                    firmwareId = it.id,
                    fileName = it.file_name,
                    sizeBytes = it.file_size_bytes,
                    sha1Hash = it.sha1_hash.orEmpty(),
                    md5Hash = it.md5_hash.orEmpty(),
                    crcHash = it.crc_hash.orEmpty(),
                    isVerified = it.is_verified,
                    missingFromFs = it.missing_from_fs,
                    createdAt = it.created_at,
                    updatedAt = it.updated_at,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun parsePlatformId(body: String, platformSlug: String): Long? {
        return try {
            platformListAdapter.fromJson(body.trim())
                ?.firstOrNull { it.slug == platformSlug || it.fs_slug == platformSlug }
                ?.id
                ?.takeIf { it > 0 }
        } catch (_: Exception) {
            null
        }
    }

    /** `GET /api/roms/{id}` — canonical ROM metadata. */
    fun fetchRomInfo(client: okhttp3.OkHttpClient, origin: String, romId: Long): RomInfoResult {
        if (origin.isBlank()) return RomInfoResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        val normalizedOrigin = RommOrigin.parse(origin)?.toUrl() ?: origin.removeSuffix("/")
        val request = okhttp3.Request.Builder()
            .url("$normalizedOrigin/api/roms/$romId")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                classifyResponse(response)?.let { return RomInfoResult.Failure(it, response.code) }
                val body = response.body?.string()
                val rom = body?.let { parseRomInfo(it) }
                if (rom == null) RomInfoResult.Failure(RommApiError.PARSE_ERROR, response.code)
                else RomInfoResult.Success(rom)
            }
        } catch (e: IOException) {
            RomInfoResult.Failure(classifyIOException(e))
        }
    }

    /** `GET /api/firmware`, optionally filtered to one platform. */
    fun fetchFirmwareList(
        client: okhttp3.OkHttpClient,
        origin: String,
        platformId: Long? = null,
    ): FirmwareListResult {
        if (origin.isBlank()) return FirmwareListResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        val normalizedOrigin = RommOrigin.parse(origin)?.toUrl() ?: origin.removeSuffix("/")
        val url = buildString {
            append("$normalizedOrigin/api/firmware")
            if (platformId != null) append("?platform_id=$platformId")
        }

        val request = okhttp3.Request.Builder().url(url).get().build()

        return try {
            client.newCall(request).execute().use { response ->
                classifyResponse(response)?.let { return FirmwareListResult.Failure(it, response.code) }
                val body = response.body?.string()
                val list = body?.let { parseFirmwareList(it) }
                if (list == null) FirmwareListResult.Failure(RommApiError.PARSE_ERROR, response.code)
                else FirmwareListResult.Success(list)
            }
        } catch (e: IOException) {
            FirmwareListResult.Failure(classifyIOException(e))
        }
    }

    fun fetchPlatformId(client: okhttp3.OkHttpClient, origin: String, platformSlug: String): PlatformIdResult {
        if (origin.isBlank()) return PlatformIdResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        val normalizedOrigin = RommOrigin.parse(origin)?.toUrl() ?: origin.removeSuffix("/")
        val request = okhttp3.Request.Builder().url("$normalizedOrigin/api/platforms").get().build()
        return try {
            client.newCall(request).execute().use { response ->
                classifyResponse(response)?.let { return PlatformIdResult.Failure(it, response.code) }
                PlatformIdResult.Success(response.body?.string()?.let { parsePlatformId(it, platformSlug) })
            }
        } catch (e: IOException) {
            PlatformIdResult.Failure(classifyIOException(e))
        }
    }

    /** Builds the exact content-download URL for a ROM (`GET /api/roms/{id}/content/{file_name}`). */
    fun romContentUrl(origin: String, romId: Long, fileName: String, fileIds: List<Long>? = null): String {
        val normalizedOrigin = RommOrigin.parse(origin)?.toUrl() ?: origin.removeSuffix("/")
        val base = normalizedOrigin.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid RomM origin: $origin")
        val builder = base.newBuilder()
            .addPathSegment("api")
            .addPathSegment("roms")
            .addPathSegment(romId.toString())
            .addPathSegment("content")
            .addPathSegment(fileName)
        if (!fileIds.isNullOrEmpty()) {
            builder.addQueryParameter("file_ids", fileIds.joinToString(","))
        }
        return builder.build().toString()
    }

    /** Builds the exact content-download URL for firmware (`GET /api/firmware/{id}/content/{file_name}`). */
    fun firmwareContentUrl(origin: String, firmwareId: Long, fileName: String): String {
        val normalizedOrigin = RommOrigin.parse(origin)?.toUrl() ?: origin.removeSuffix("/")
        val base = normalizedOrigin.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid RomM origin: $origin")
        return base.newBuilder()
            .addPathSegment("api")
            .addPathSegment("firmware")
            .addPathSegment(firmwareId.toString())
            .addPathSegment("content")
            .addPathSegment(fileName)
            .build()
            .toString()
    }

    private fun classifyResponse(response: okhttp3.Response): RommApiError? {
        return when {
            response.isSuccessful -> null
            response.code == 401 || response.code == 403 -> RommApiError.AUTH_EXPIRED
            response.code == 404 -> RommApiError.NOT_FOUND
            else -> RommApiError.SERVER_ERROR
        }
    }

    private fun classifyIOException(e: IOException): RommApiError {
        val cause = e.cause ?: e
        return if (cause is javax.net.ssl.SSLException ||
            cause.javaClass.name.contains("SSL", ignoreCase = true)
        ) {
            RommApiError.TLS_ERROR
        } else {
            RommApiError.NETWORK_ERROR
        }
    }
}

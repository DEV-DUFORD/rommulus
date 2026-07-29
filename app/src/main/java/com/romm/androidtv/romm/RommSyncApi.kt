@file:OptIn(ExperimentalStdlibApi::class)

package com.romm.androidtv.romm

import com.romm.androidtv.network.RommOrigin
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.KotlinJsonAdapterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant

/**
 * Typed models and network calls for RomM's device-registration and
 * negotiated-save-sync endpoints (LIBRETRO_REFACTOR.md section 11.2/11.3).
 *
 * This contract was audited directly against the pinned reference backend
 * (RomM commit `ce498d6c3e012faaab0ae388860d1950f8be66ca`,
 * `backend/endpoints/device.py`, `backend/endpoints/sync.py`,
 * `backend/endpoints/saves.py`, and their `responses/` schemas) — not
 * guessed from the plan text alone. Follows [RommApi]'s existing convention:
 * pure JSON-parsing functions are separated from the network call functions
 * so parsing logic is unit-testable without a live/mock server, and errors
 * are classified into the shared [RommApiError] enum.
 */

// ---- Device registration (POST /api/devices) ----

data class DeviceRegisterRequest(
    val name: String? = null,
    val platform: String? = null,
    val client: String? = null,
    val clientVersion: String? = null,
    val clientDeviceIdentifier: String? = null,
    val allowExisting: Boolean = true,
    val allowDuplicate: Boolean = false,
)

data class DeviceRegisterInfo(
    val deviceId: String,
    val name: String?,
    val createdAt: Instant?,
)

sealed interface DeviceRegisterResult {
    /** The [alreadyExisted] flag distinguishes HTTP 200 (reused) from 201 (newly created). */
    data class Success(val device: DeviceRegisterInfo, val alreadyExisted: Boolean) : DeviceRegisterResult
    data class Failure(val error: RommApiError, val httpCode: Int? = null) : DeviceRegisterResult
}

@JsonClass(generateAdapter = false)
internal data class DeviceCreatePayloadJson(
    val name: String? = null,
    val platform: String? = null,
    val client: String? = null,
    val client_version: String? = null,
    val client_device_identifier: String? = null,
    val allow_existing: Boolean = true,
    val allow_duplicate: Boolean = false,
)

@JsonClass(generateAdapter = false)
internal data class DeviceCreateResponseJson(
    val device_id: String = "",
    val name: String? = null,
    val created_at: String? = null,
)

// ---- Negotiated sync (POST /api/sync/negotiate, POST /api/sync/sessions/{id}/complete) ----

/** Mirrors the backend's `ClientSaveState` (`endpoints/sync.py`). */
data class ClientSaveState(
    val romId: Long,
    val fileName: String,
    /** Stable slot name (e.g. "autosave"). Null means an archival, never-paired manual upload. */
    val slot: String?,
    val emulator: String?,
    val contentHash: String?,
    val updatedAt: Instant,
    val fileSizeBytes: Long,
)

data class SyncNegotiateRequest(
    val deviceId: String,
    val saves: List<ClientSaveState>,
)

enum class SyncAction { UPLOAD, DOWNLOAD, CONFLICT, NO_OP }

data class SyncOperation(
    val action: SyncAction,
    val romId: Long,
    val saveId: Long?,
    val fileName: String,
    val slot: String?,
    val emulator: String?,
    val reason: String,
    val serverUpdatedAt: Instant?,
    val serverContentHash: String?,
)

data class SyncNegotiateInfo(
    val sessionId: Long,
    val operations: List<SyncOperation>,
)

sealed interface SyncNegotiateResult {
    data class Success(val negotiation: SyncNegotiateInfo) : SyncNegotiateResult
    data class Failure(val error: RommApiError, val httpCode: Int? = null) : SyncNegotiateResult
}

data class SyncCompleteRequest(
    val operationsCompleted: Int,
    val operationsFailed: Int,
)

sealed interface SyncCompleteResult {
    data class Success(val sessionStatus: String) : SyncCompleteResult
    data class Failure(val error: RommApiError, val httpCode: Int? = null) : SyncCompleteResult
}

@JsonClass(generateAdapter = false)
internal data class ClientSaveStateJson(
    val rom_id: Long,
    val file_name: String,
    val slot: String?,
    val emulator: String?,
    val content_hash: String?,
    val updated_at: String,
    val file_size_bytes: Long,
)

@JsonClass(generateAdapter = false)
internal data class SyncNegotiatePayloadJson(
    val device_id: String?,
    val saves: List<ClientSaveStateJson>,
)

@JsonClass(generateAdapter = false)
internal data class SyncOperationJson(
    val action: String = "no_op",
    val rom_id: Long = 0,
    val save_id: Long? = null,
    val file_name: String = "",
    val slot: String? = null,
    val emulator: String? = null,
    val reason: String = "",
    val server_updated_at: String? = null,
    val server_content_hash: String? = null,
)

@JsonClass(generateAdapter = false)
internal data class SyncNegotiateResponseJson(
    val session_id: Long = 0,
    val operations: List<SyncOperationJson> = emptyList(),
    val total_upload: Int = 0,
    val total_download: Int = 0,
    val total_conflict: Int = 0,
    val total_no_op: Int = 0,
)

@JsonClass(generateAdapter = false)
internal data class SyncCompletePayloadJson(
    val operations_completed: Int = 0,
    val operations_failed: Int = 0,
)

@JsonClass(generateAdapter = false)
internal data class SyncSessionJson(
    val id: Long = 0,
    val status: String = "",
)

@JsonClass(generateAdapter = false)
internal data class SyncCompleteResponseJson(
    val session: SyncSessionJson = SyncSessionJson(),
)

// ---- Save upload/download/confirmation (POST /api/saves, GET .../content, POST .../downloaded) ----

data class SaveUploadRequest(
    val romId: Long,
    val slot: String?,
    val emulator: String?,
    val deviceId: String,
    val sessionId: Long?,
    /** Server rejects with 409 (mapped to [RommApiError.SERVER_ERROR]... see [RommApiError.CONFLICT]) if false and a newer save exists. */
    val overwrite: Boolean,
    val fileName: String,
    val bytes: ByteArray,
)

data class ServerSaveInfo(
    val saveId: Long,
    val romId: Long,
    val fileName: String,
    val slot: String?,
    val emulator: String?,
    val contentHash: String?,
    val updatedAt: Instant?,
    val fileSizeBytes: Long,
)

sealed interface SaveUploadResult {
    data class Success(val save: ServerSaveInfo) : SaveUploadResult
    /** The server reports a newer save already exists for this slot/rom (HTTP 409) — do not retry blindly; renegotiate. */
    data class Conflict(val httpCode: Int) : SaveUploadResult
    data class Failure(val error: RommApiError, val httpCode: Int? = null) : SaveUploadResult
}

sealed interface SaveDownloadResult {
    data class Success(val bytes: ByteArray) : SaveDownloadResult
    data class Failure(val error: RommApiError, val httpCode: Int? = null) : SaveDownloadResult
}

sealed interface SaveConfirmResult {
    data object Success : SaveConfirmResult
    data class Failure(val error: RommApiError, val httpCode: Int? = null) : SaveConfirmResult
}

@JsonClass(generateAdapter = false)
internal data class SaveSchemaJson(
    val id: Long = 0,
    val rom_id: Long = 0,
    val file_name: String = "",
    val slot: String? = null,
    val emulator: String? = null,
    val content_hash: String? = null,
    val updated_at: String? = null,
    val file_size_bytes: Long = 0,
)

object RommSyncApi {

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
    private val deviceCreatePayloadAdapter = moshi.adapter<DeviceCreatePayloadJson>()
    private val deviceCreateResponseAdapter = moshi.adapter<DeviceCreateResponseJson>()
    private val syncNegotiatePayloadAdapter = moshi.adapter<SyncNegotiatePayloadJson>()
    private val syncNegotiateResponseAdapter = moshi.adapter<SyncNegotiateResponseJson>()
    private val syncCompletePayloadAdapter = moshi.adapter<SyncCompletePayloadJson>()
    private val syncCompleteResponseAdapter = moshi.adapter<SyncCompleteResponseJson>()
    private val saveSchemaAdapter = moshi.adapter<SaveSchemaJson>()

    // ---- Pure parse functions (unit-testable without a server) ----

    fun parseDeviceCreateResponse(body: String): DeviceRegisterInfo? = try {
        val json = deviceCreateResponseAdapter.fromJson(body.trim())
        if (json == null || json.device_id.isBlank()) null
        else DeviceRegisterInfo(
            deviceId = json.device_id,
            name = json.name,
            createdAt = json.created_at?.let(::parseInstantOrNull),
        )
    } catch (_: Exception) {
        null
    }

    fun parseSyncNegotiateResponse(body: String): SyncNegotiateInfo? = try {
        val json = syncNegotiateResponseAdapter.fromJson(body.trim())
        if (json == null) null
        else SyncNegotiateInfo(
            sessionId = json.session_id,
            operations = json.operations.mapNotNull { op ->
                val action = when (op.action) {
                    "upload" -> SyncAction.UPLOAD
                    "download" -> SyncAction.DOWNLOAD
                    "conflict" -> SyncAction.CONFLICT
                    "no_op" -> SyncAction.NO_OP
                    else -> return@mapNotNull null
                }
                SyncOperation(
                    action = action,
                    romId = op.rom_id,
                    saveId = op.save_id,
                    fileName = op.file_name,
                    slot = op.slot,
                    emulator = op.emulator,
                    reason = op.reason,
                    serverUpdatedAt = op.server_updated_at?.let(::parseInstantOrNull),
                    serverContentHash = op.server_content_hash,
                )
            },
        )
    } catch (_: Exception) {
        null
    }

    fun parseSyncCompleteResponse(body: String): String? = try {
        syncCompleteResponseAdapter.fromJson(body.trim())?.session?.status?.ifBlank { null }
    } catch (_: Exception) {
        null
    }

    fun parseSaveSchema(body: String): ServerSaveInfo? = try {
        val json = saveSchemaAdapter.fromJson(body.trim())
        if (json == null || json.id <= 0) null
        else ServerSaveInfo(
            saveId = json.id,
            romId = json.rom_id,
            fileName = json.file_name,
            slot = json.slot,
            emulator = json.emulator,
            contentHash = json.content_hash,
            updatedAt = json.updated_at?.let(::parseInstantOrNull),
            fileSizeBytes = json.file_size_bytes,
        )
    } catch (_: Exception) {
        null
    }

    private fun parseInstantOrNull(raw: String): Instant? = try {
        Instant.parse(raw)
    } catch (_: Exception) {
        null
    }

    // ---- Network calls ----

    /** `POST /api/devices`. */
    fun registerDevice(
        client: okhttp3.OkHttpClient,
        origin: String,
        request: DeviceRegisterRequest,
    ): DeviceRegisterResult {
        if (origin.isBlank()) return DeviceRegisterResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        val url = apiUrl(origin, "devices") ?: return DeviceRegisterResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)

        val payloadJson = deviceCreatePayloadAdapter.toJson(
            DeviceCreatePayloadJson(
                name = request.name,
                platform = request.platform,
                client = request.client,
                client_version = request.clientVersion,
                client_device_identifier = request.clientDeviceIdentifier,
                allow_existing = request.allowExisting,
                allow_duplicate = request.allowDuplicate,
            )
        )
        val body = payloadJson.toRequestBody("application/json".toMediaType())
        val httpRequest = okhttp3.Request.Builder().url(url).post(body).build()

        return try {
            client.newCall(httpRequest).execute().use { response ->
                classifyResponse(response)?.let { return DeviceRegisterResult.Failure(it, response.code) }
                val responseBody = response.body?.string()
                val info = responseBody?.let(::parseDeviceCreateResponse)
                if (info == null) DeviceRegisterResult.Failure(RommApiError.PARSE_ERROR, response.code)
                else DeviceRegisterResult.Success(info, alreadyExisted = response.code == 200)
            }
        } catch (e: IOException) {
            DeviceRegisterResult.Failure(classifyIOException(e))
        }
    }

    /** `POST /api/sync/negotiate`. */
    fun negotiateSync(
        client: okhttp3.OkHttpClient,
        origin: String,
        request: SyncNegotiateRequest,
    ): SyncNegotiateResult {
        if (origin.isBlank()) return SyncNegotiateResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        val url = apiUrl(origin, "sync/negotiate") ?: return SyncNegotiateResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)

        val payloadJson = syncNegotiatePayloadAdapter.toJson(
            SyncNegotiatePayloadJson(
                device_id = request.deviceId,
                saves = request.saves.map { s ->
                    ClientSaveStateJson(
                        rom_id = s.romId,
                        file_name = s.fileName,
                        slot = s.slot,
                        emulator = s.emulator,
                        content_hash = s.contentHash,
                        updated_at = s.updatedAt.toString(),
                        file_size_bytes = s.fileSizeBytes,
                    )
                },
            )
        )
        val body = payloadJson.toRequestBody("application/json".toMediaType())
        val httpRequest = okhttp3.Request.Builder().url(url).post(body).build()

        return try {
            client.newCall(httpRequest).execute().use { response ->
                classifyResponse(response)?.let { return SyncNegotiateResult.Failure(it, response.code) }
                val responseBody = response.body?.string()
                val info = responseBody?.let(::parseSyncNegotiateResponse)
                if (info == null) SyncNegotiateResult.Failure(RommApiError.PARSE_ERROR, response.code)
                else SyncNegotiateResult.Success(info)
            }
        } catch (e: IOException) {
            SyncNegotiateResult.Failure(classifyIOException(e))
        }
    }

    /** `POST /api/sync/sessions/{sessionId}/complete`. */
    fun completeSyncSession(
        client: okhttp3.OkHttpClient,
        origin: String,
        sessionId: Long,
        request: SyncCompleteRequest,
    ): SyncCompleteResult {
        if (origin.isBlank()) return SyncCompleteResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        val url = apiUrl(origin, "sync/sessions/$sessionId/complete")
            ?: return SyncCompleteResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)

        val payloadJson = syncCompletePayloadAdapter.toJson(
            SyncCompletePayloadJson(
                operations_completed = request.operationsCompleted,
                operations_failed = request.operationsFailed,
            )
        )
        val body = payloadJson.toRequestBody("application/json".toMediaType())
        val httpRequest = okhttp3.Request.Builder().url(url).post(body).build()

        return try {
            client.newCall(httpRequest).execute().use { response ->
                classifyResponse(response)?.let { return SyncCompleteResult.Failure(it, response.code) }
                val responseBody = response.body?.string()
                val status = responseBody?.let(::parseSyncCompleteResponse)
                if (status == null) SyncCompleteResult.Failure(RommApiError.PARSE_ERROR, response.code)
                else SyncCompleteResult.Success(status)
            }
        } catch (e: IOException) {
            SyncCompleteResult.Failure(classifyIOException(e))
        }
    }

    /** `POST /api/saves` (multipart, matches `add_save`'s query params + `saveFile` part). */
    fun uploadSave(
        client: okhttp3.OkHttpClient,
        origin: String,
        request: SaveUploadRequest,
    ): SaveUploadResult {
        if (origin.isBlank()) return SaveUploadResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        val base = apiUrl(origin, "saves") ?: return SaveUploadResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        val urlBuilder = base.toHttpUrlOrNull()?.newBuilder()
            ?: return SaveUploadResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        urlBuilder.addQueryParameter("rom_id", request.romId.toString())
        request.emulator?.let { urlBuilder.addQueryParameter("emulator", it) }
        request.slot?.let { urlBuilder.addQueryParameter("slot", it) }
        urlBuilder.addQueryParameter("device_id", request.deviceId)
        request.sessionId?.let { urlBuilder.addQueryParameter("session_id", it.toString()) }
        urlBuilder.addQueryParameter("overwrite", request.overwrite.toString())

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "saveFile",
                request.fileName,
                request.bytes.toRequestBody("application/octet-stream".toMediaType()),
            )
            .build()

        val httpRequest = okhttp3.Request.Builder().url(urlBuilder.build()).post(multipart).build()

        return try {
            client.newCall(httpRequest).execute().use { response ->
                if (response.code == 409) return SaveUploadResult.Conflict(response.code)
                classifyResponse(response)?.let { return SaveUploadResult.Failure(it, response.code) }
                val responseBody = response.body?.string()
                val save = responseBody?.let(::parseSaveSchema)
                if (save == null) SaveUploadResult.Failure(RommApiError.PARSE_ERROR, response.code)
                else SaveUploadResult.Success(save)
            }
        } catch (e: IOException) {
            SaveUploadResult.Failure(classifyIOException(e))
        }
    }

    /** `GET /api/saves/{id}/content`. */
    fun downloadSaveContent(
        client: okhttp3.OkHttpClient,
        origin: String,
        saveId: Long,
        deviceId: String,
        sessionId: Long? = null,
    ): SaveDownloadResult {
        if (origin.isBlank()) return SaveDownloadResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        val base = apiUrl(origin, "saves/$saveId/content") ?: return SaveDownloadResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        val urlBuilder = base.toHttpUrlOrNull()?.newBuilder()
            ?: return SaveDownloadResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        urlBuilder.addQueryParameter("device_id", deviceId)
        sessionId?.let { urlBuilder.addQueryParameter("session_id", it.toString()) }

        val httpRequest = okhttp3.Request.Builder().url(urlBuilder.build()).get().build()

        return try {
            client.newCall(httpRequest).execute().use { response ->
                classifyResponse(response)?.let { return SaveDownloadResult.Failure(it, response.code) }
                val bytes = response.body?.bytes()
                if (bytes == null) SaveDownloadResult.Failure(RommApiError.PARSE_ERROR, response.code)
                else SaveDownloadResult.Success(bytes)
            }
        } catch (e: IOException) {
            SaveDownloadResult.Failure(classifyIOException(e))
        }
    }

    /** `POST /api/saves/{id}/downloaded` — durable download confirmation (section 11.3). */
    fun confirmDownload(
        client: okhttp3.OkHttpClient,
        origin: String,
        saveId: Long,
        deviceId: String,
    ): SaveConfirmResult {
        if (origin.isBlank()) return SaveConfirmResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        val url = apiUrl(origin, "saves/$saveId/downloaded") ?: return SaveConfirmResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)

        val json = "{\"device_id\":${jsonQuote(deviceId)}}"
        val body = json.toRequestBody("application/json".toMediaType())
        val httpRequest = okhttp3.Request.Builder().url(url).post(body).build()

        return try {
            client.newCall(httpRequest).execute().use { response ->
                classifyResponse(response)?.let { return SaveConfirmResult.Failure(it, response.code) }
                SaveConfirmResult.Success
            }
        } catch (e: IOException) {
            SaveConfirmResult.Failure(classifyIOException(e))
        }
    }

    private fun jsonQuote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun apiUrl(origin: String, path: String): String? {
        val normalizedOrigin = RommOrigin.parse(origin)?.toUrl() ?: origin.removeSuffix("/")
        return "$normalizedOrigin/api/$path"
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

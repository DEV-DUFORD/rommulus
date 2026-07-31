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

/** Stable tag for all auth-loop boundary diagnostics (logcat -s RommAuthDx). */
private const val TAG = "RommAuthDx"

/** Safe diagnostic logger: swallows unmocked android.util.Log in JVM unit tests. */
private fun diagLog(priority: Int, message: String) {
    try { android.util.Log.println(priority, TAG, message) } catch (_: Exception) { /* JVM test env */ }
}

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

// ---- Play-session ingestion (POST /api/play-sessions) ----
// Drives the server's `rom_user.last_played`/`now_playing` fields (backend/handler/play_session_handler.py),
// which is what the RomM Home screen's "Continue Playing" row is actually sourced from
// (`last_played=true&order_by=last_played` — see LibraryApi.kt's RomQuery.ContinuePlaying). Native
// play never called this endpoint at all, so titles played through the native library never
// appeared there even though gameplay and save-sync both worked correctly.

/** Mirrors the backend's `PlaySessionEntry` (`endpoints/play_sessions.py`). */
data class PlaySessionEntry(
    val romId: Long,
    /** Stable slot name (e.g. "autosave"), or null for an untracked/manual session. */
    val saveSlot: String?,
    val startTime: Instant,
    val endTime: Instant,
    val durationMs: Long,
)

data class PlaySessionIngestRequest(
    val deviceId: String?,
    val sessions: List<PlaySessionEntry>,
)

sealed interface PlaySessionIngestResult {
    data class Success(val createdCount: Int, val skippedCount: Int) : PlaySessionIngestResult
    data class Failure(val error: RommApiError, val httpCode: Int? = null) : PlaySessionIngestResult
}

@JsonClass(generateAdapter = false)
internal data class PlaySessionEntryJson(
    val rom_id: Long,
    val save_slot: String? = null,
    val start_time: String,
    val end_time: String,
    val duration_ms: Long,
)

@JsonClass(generateAdapter = false)
internal data class PlaySessionIngestPayloadJson(
    val device_id: String? = null,
    val sessions: List<PlaySessionEntryJson>,
)

@JsonClass(generateAdapter = false)
internal data class PlaySessionIngestResultJson(
    val index: Int = 0,
    val status: String = "",
    val id: Long? = null,
    val detail: String? = null,
)

@JsonClass(generateAdapter = false)
internal data class PlaySessionIngestResponseJson(
    val results: List<PlaySessionIngestResultJson> = emptyList(),
    val created_count: Int = 0,
    val skipped_count: Int = 0,
)

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
    /**
     * When true (with [slot] set), the server deletes older saves in that slot beyond
     * [autocleanupLimit] right after this upload succeeds (`add_save`'s `autocleanup`/
     * `autocleanup_limit` query params). Used so this device's own uploads into the
     * "autosave" slot never accumulate more than [autocleanupLimit] file(s) server-side,
     * even though the server still mints a new timestamped filename per upload.
     */
    val autocleanup: Boolean = false,
    val autocleanupLimit: Int = 10,
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

sealed interface SaveListResult {
    data class Success(val saves: List<ServerSaveInfo>) : SaveListResult
    data class Failure(val error: RommApiError, val httpCode: Int? = null) : SaveListResult
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

data class ClientToken(val raw: String) {
    init { require(raw.isNotBlank()) { "ClientToken.raw must not be blank" } }
}

data class ClientTokenInfo(
    val token: ClientToken,
    val expiresAtEpochSeconds: Long?,
)

sealed interface ClientTokenAcquireResult {
    data class Success(val info: ClientTokenInfo) : ClientTokenAcquireResult
    data class Failure(val error: RommApiError, val httpCode: Int? = null) : ClientTokenAcquireResult
}

@JsonClass(generateAdapter = false)
internal data class ClientTokenPayloadJson(
    val name: String = "romm-android-tv",
    val scopes: List<String> = emptyList(),
    val expires_in: String? = null,
)

@JsonClass(generateAdapter = false)
internal data class ClientTokenResponseJson(
    val id: Long = 0,
    val name: String = "",
    val scopes: List<String> = emptyList(),
    val raw_token: String = "",
    val expires_at: String? = null,
    val created_at: String? = null,
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
    private val playSessionIngestPayloadAdapter = moshi.adapter<PlaySessionIngestPayloadJson>()
    private val playSessionIngestResponseAdapter = moshi.adapter<PlaySessionIngestResponseJson>()
    private val saveSchemaAdapter = moshi.adapter<SaveSchemaJson>()
    private val saveSchemaListAdapter = moshi.adapter<List<SaveSchemaJson>>(
        com.squareup.moshi.Types.newParameterizedType(List::class.java, SaveSchemaJson::class.java)
    )
    private val clientTokenPayloadAdapter = moshi.adapter<ClientTokenPayloadJson>()
    private val clientTokenResponseAdapter = moshi.adapter<ClientTokenResponseJson>()

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

    fun parsePlaySessionIngestResponse(body: String): Pair<Int, Int>? = try {
        val json = playSessionIngestResponseAdapter.fromJson(body.trim())
        json?.let { it.created_count to it.skipped_count }
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

    /** Parses a `GET /api/saves` list response body (a bare JSON array of save schemas). */
    fun parseSaveSchemaList(body: String): List<ServerSaveInfo>? = try {
        val json = saveSchemaListAdapter.fromJson(body.trim())
        json?.filter { it.id > 0 }?.map { j ->
            ServerSaveInfo(
                saveId = j.id,
                romId = j.rom_id,
                fileName = j.file_name,
                slot = j.slot,
                emulator = j.emulator,
                contentHash = j.content_hash,
                updatedAt = j.updated_at?.let(::parseInstantOrNull),
                fileSizeBytes = j.file_size_bytes,
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun parseInstantOrNull(raw: String): Instant? = try {
        Instant.parse(raw)
    } catch (_: Exception) {
        null
    }

    fun parseClientTokenResponse(body: String): ClientTokenInfo? = try {
        val json = clientTokenResponseAdapter.fromJson(body.trim())
        if (json == null || json.raw_token.isBlank()) null
        else ClientTokenInfo(
            token = ClientToken(json.raw_token),
            expiresAtEpochSeconds = json.expires_at?.let { Instant.parse(it).epochSecond },
        )
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
                val classification = classifyResponse(response)
                if (classification != null) {
                    diagLog(android.util.Log.DEBUG, "RommSyncApi.registerDevice: failure error=${classification.name} httpCode=${response.code}")
                    return DeviceRegisterResult.Failure(classification, response.code)
                }
                val responseBody = response.body?.string()
                val info = responseBody?.let(::parseDeviceCreateResponse)
                if (info == null) {
                    diagLog(android.util.Log.DEBUG, "RommSyncApi.registerDevice: parseFailed httpCode=${response.code}")
                    DeviceRegisterResult.Failure(RommApiError.PARSE_ERROR, response.code)
                } else {
                    val alreadyExisted = response.code == 200
                    diagLog(android.util.Log.DEBUG, "RommSyncApi.registerDevice: success status=${if (alreadyExisted) "reused" else "created"}")
                    DeviceRegisterResult.Success(info, alreadyExisted = alreadyExisted)
                }
            }
        } catch (e: IOException) {
            val error = classifyIOException(e)
            diagLog(android.util.Log.WARN, "RommSyncApi.registerDevice: ioError $error")
            DeviceRegisterResult.Failure(error)
        }
    }

    /**
     * `POST /api/client-tokens` — acquire a durable ClientToken for this user.
     * Called from the foreground authenticated login/session-verification lifecycle
     * (cookie-authenticated client). The returned raw token is persisted via
     * [ClientTokenStore] for later Bearer-only worker execution.
     */
    fun acquireClientToken(
        client: okhttp3.OkHttpClient,
        origin: String,
        scopes: List<String>,
    ): ClientTokenAcquireResult {
        if (origin.isBlank()) return ClientTokenAcquireResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)

        val httpRequest: okhttp3.Request = try {
            diagLog(android.util.Log.DEBUG, "RommSyncApi.acquireClientToken: phase urlConstruction")
            val url = apiUrl(origin, "client-tokens") ?: return ClientTokenAcquireResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
            diagLog(android.util.Log.DEBUG, "RommSyncApi.acquireClientToken: phase payloadSerialization")
            val payloadJson = clientTokenPayloadAdapter.toJson(
                ClientTokenPayloadJson(
                    name = "romm-android-tv",
                    scopes = scopes,
                ),
            )
            diagLog(android.util.Log.DEBUG, "RommSyncApi.acquireClientToken: phase requestBody")
            val body = payloadJson.toRequestBody("application/json".toMediaType())
            diagLog(android.util.Log.DEBUG, "RommSyncApi.acquireClientToken: phase requestBuilder")
            val builder = okhttp3.Request.Builder().url(url).post(body)
            // Cookie-authenticated POST /api/client-tokens is CSRF-protected: the matching
            // romm_csrftoken cookie value must also be sent as X-CSRFToken. Presence-only diag.
            diagLog(android.util.Log.DEBUG, "RommSyncApi.acquireClientToken: phase csrfHeader")
            val httpUrl = url.toHttpUrlOrNull()
            val csrfToken = httpUrl?.let { client.cookieJar.loadForRequest(it) }
                ?.firstOrNull { it.name == "romm_csrftoken" }
                ?.value
            if (csrfToken != null) {
                builder.header("X-CSRFToken", csrfToken)
            }
            diagLog(android.util.Log.DEBUG, "RommSyncApi.acquireClientToken: csrfTokenPresent=${csrfToken != null}")
            builder.build()
        } catch (e: Exception) {
            diagLog(android.util.Log.WARN, "RommSyncApi.acquireClientToken: constructionFailed ${e.javaClass.simpleName}")
            return ClientTokenAcquireResult.Failure(RommApiError.NETWORK_ERROR)
        }

        return try {
            diagLog(android.util.Log.DEBUG, "RommSyncApi.acquireClientToken: phase executeStart")
            client.newCall(httpRequest).execute().use { response ->
                val classification = classifyResponse(response)
                if (classification != null) {
                    diagLog(android.util.Log.DEBUG, "RommSyncApi.acquireClientToken: failure error=${classification.name} httpCode=${response.code}")
                    return ClientTokenAcquireResult.Failure(classification, response.code)
                }
                val responseBody = response.body?.string()
                val info = responseBody?.let(::parseClientTokenResponse)
                if (info == null) {
                    diagLog(android.util.Log.DEBUG, "RommSyncApi.acquireClientToken: parseFailed httpCode=${response.code}")
                    ClientTokenAcquireResult.Failure(RommApiError.PARSE_ERROR, response.code)
                } else {
                    diagLog(android.util.Log.DEBUG, "RommSyncApi.acquireClientToken: success scopes=$scopes")
                    ClientTokenAcquireResult.Success(info)
                }
            }
        } catch (e: IOException) {
            val error = classifyIOException(e)
            diagLog(android.util.Log.WARN, "RommSyncApi.acquireClientToken: ioError $error")
            ClientTokenAcquireResult.Failure(error)
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
                val classification = classifyResponse(response)
                if (classification != null) {
                    diagLog(android.util.Log.DEBUG, "RommSyncApi.negotiate: failure error=${classification.name} httpCode=${response.code}")
                    return SyncNegotiateResult.Failure(classification, response.code)
                }
                val responseBody = response.body?.string()
                val info = responseBody?.let(::parseSyncNegotiateResponse)
                if (info == null) {
                    diagLog(android.util.Log.DEBUG, "RommSyncApi.negotiate: parseFailed httpCode=${response.code}")
                    SyncNegotiateResult.Failure(RommApiError.PARSE_ERROR, response.code)
                } else {
                    val opCount = info.operations.size
                    diagLog(android.util.Log.DEBUG, "RommSyncApi.negotiate: success ops=$opCount")
                    SyncNegotiateResult.Success(info)
                }
            }
        } catch (e: IOException) {
            val error = classifyIOException(e)
            diagLog(android.util.Log.WARN, "RommSyncApi.negotiate: ioError $error")
            SyncNegotiateResult.Failure(error)
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

    /**
     * `POST /api/play-sessions`. Reports completed gameplay so the server can advance
     * `rom_user.last_played`/`now_playing` — this is what makes a title appear in the RomM Home
     * screen's "Continue Playing" row (see `RomQuery.ContinuePlaying` in `LibraryApi.kt`).
     * Best-effort by design: a failure here must never block save-sync or gameplay.
     */
    fun ingestPlaySessions(
        client: okhttp3.OkHttpClient,
        origin: String,
        request: PlaySessionIngestRequest,
    ): PlaySessionIngestResult {
        if (origin.isBlank()) return PlaySessionIngestResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        val url = apiUrl(origin, "play-sessions") ?: return PlaySessionIngestResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)

        val payloadJson = playSessionIngestPayloadAdapter.toJson(
            PlaySessionIngestPayloadJson(
                device_id = request.deviceId,
                sessions = request.sessions.map { s ->
                    PlaySessionEntryJson(
                        rom_id = s.romId,
                        save_slot = s.saveSlot,
                        start_time = s.startTime.toString(),
                        end_time = s.endTime.toString(),
                        duration_ms = s.durationMs,
                    )
                },
            )
        )
        val body = payloadJson.toRequestBody("application/json".toMediaType())
        val httpRequest = okhttp3.Request.Builder().url(url).post(body).build()

        return try {
            client.newCall(httpRequest).execute().use { response ->
                val classification = classifyResponse(response)
                if (classification != null) {
                    diagLog(android.util.Log.DEBUG, "RommSyncApi.ingestPlaySessions: failure error=${classification.name} httpCode=${response.code}")
                    return PlaySessionIngestResult.Failure(classification, response.code)
                }
                val responseBody = response.body?.string()
                val counts = responseBody?.let(::parsePlaySessionIngestResponse)
                if (counts == null) {
                    diagLog(android.util.Log.DEBUG, "RommSyncApi.ingestPlaySessions: parseFailed httpCode=${response.code}")
                    PlaySessionIngestResult.Failure(RommApiError.PARSE_ERROR, response.code)
                } else {
                    diagLog(android.util.Log.DEBUG, "RommSyncApi.ingestPlaySessions: success created=${counts.first} skipped=${counts.second}")
                    PlaySessionIngestResult.Success(counts.first, counts.second)
                }
            }
        } catch (e: IOException) {
            val error = classifyIOException(e)
            diagLog(android.util.Log.WARN, "RommSyncApi.ingestPlaySessions: ioError $error")
            PlaySessionIngestResult.Failure(error)
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
        if (request.autocleanup) {
            urlBuilder.addQueryParameter("autocleanup", "true")
            urlBuilder.addQueryParameter("autocleanup_limit", request.autocleanupLimit.toString())
        }

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

    /**
     * `GET /api/saves/{id}/content` with `optimistic=false` and no `session_id`.
     *
     * Used for keep-local backup reads during conflict resolution: downloads the
     * server's current save bytes without mutating device-sync bookkeeping or
     * incrementing session completion counters. Grounded in the pinned endpoint
     * query schema (`optimistic: bool = True`, `session_id: int | None = None`).
     */
    fun downloadSaveContentBackup(
        client: okhttp3.OkHttpClient,
        origin: String,
        saveId: Long,
        deviceId: String,
    ): SaveDownloadResult {
        if (origin.isBlank()) return SaveDownloadResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        val base = apiUrl(origin, "saves/$saveId/content") ?: return SaveDownloadResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        val urlBuilder = base.toHttpUrlOrNull()?.newBuilder()
            ?: return SaveDownloadResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        urlBuilder.addQueryParameter("device_id", deviceId)
        urlBuilder.addQueryParameter("optimistic", "false")
        // Deliberately omit session_id — avoids session operation counting.

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

    /**
     * `GET /api/saves?rom_id=X&device_id=Y` — lists every save the user owns for a ROM,
     * across every slot/device (mirrors RomM's own web UI "All Saves" list). Used by the
     * native save picker (section 13 follow-up) so the user can choose an existing server
     * save to download-and-adopt before launch, instead of the app always negotiating its
     * own single "autosave" slot. [deviceId] is optional: when supplied, the response
     * includes this device's own sync status per save, but omitting it still returns the
     * full list (device-agnostic read, matches `get_saves`'s `device_id: str | None`).
     */
    fun listSaves(
        client: okhttp3.OkHttpClient,
        origin: String,
        romId: Long,
        deviceId: String? = null,
    ): SaveListResult {
        if (origin.isBlank()) return SaveListResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        val base = apiUrl(origin, "saves") ?: return SaveListResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        val urlBuilder = base.toHttpUrlOrNull()?.newBuilder()
            ?: return SaveListResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        urlBuilder.addQueryParameter("rom_id", romId.toString())
        deviceId?.let { urlBuilder.addQueryParameter("device_id", it) }

        val httpRequest = okhttp3.Request.Builder().url(urlBuilder.build()).get().build()

        return try {
            client.newCall(httpRequest).execute().use { response ->
                val classification = classifyResponse(response)
                if (classification != null) {
                    diagLog(android.util.Log.DEBUG, "RommSyncApi.listSaves: failure error=${classification.name} httpCode=${response.code}")
                    return SaveListResult.Failure(classification, response.code)
                }
                val responseBody = response.body?.string()
                val saves = responseBody?.let(::parseSaveSchemaList)
                if (saves == null) {
                    diagLog(android.util.Log.DEBUG, "RommSyncApi.listSaves: parseFailed httpCode=${response.code}")
                    SaveListResult.Failure(RommApiError.PARSE_ERROR, response.code)
                } else {
                    diagLog(android.util.Log.DEBUG, "RommSyncApi.listSaves: success count=${saves.size}")
                    SaveListResult.Success(saves)
                }
            }
        } catch (e: IOException) {
            val error = classifyIOException(e)
            diagLog(android.util.Log.WARN, "RommSyncApi.listSaves: ioError $error")
            SaveListResult.Failure(error)
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

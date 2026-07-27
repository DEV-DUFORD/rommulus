@file:OptIn(ExperimentalStdlibApi::class)

package com.romm.androidtv.network

import com.romm.androidtv.model.HeartbeatError
import com.romm.androidtv.model.HeartbeatResponse
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.KotlinJsonAdapterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import java.io.IOException

/**
 * Moshi data class for RomM 5.0.0+ nested heartbeat JSON deserialization.
 *
 * Actual server response shape (RomM 5):
 * {
 *   "SYSTEM": { "VERSION": "5.0.0", "SHOW_SETUP_WIZARD": false },
 *   "EMULATION": { "DISABLE_EMULATOR_JS": false, ... },
 *   "FRONTEND": { "DISABLE_USERPASS_LOGIN": false, ... },
 *   ...
 * }
 */
@JsonClass(generateAdapter = false)
data class HeartbeatJson(
    val SYSTEM: SystemSection? = null,
    val EMULATION: EmulationSection? = null,
    val FRONTEND: FrontendSection? = null,
    // Legacy flat fields (pre-RomM 5 compatibility)
    val version: String? = null,
    val setup_complete: Boolean? = null,
    val userpass_enabled: Boolean? = false,
    val emulatorjs_enabled: Boolean? = false,
    val message: String? = null
)

@JsonClass(generateAdapter = false)
data class SystemSection(
    @Json(name = "VERSION")
    val version: String? = null,
    @Json(name = "SHOW_SETUP_WIZARD")
    val showSetupWizard: Boolean = true
)

@JsonClass(generateAdapter = false)
data class EmulationSection(
    @Json(name = "DISABLE_EMULATOR_JS")
    val disableEmulatorJs: Boolean = true
)

@JsonClass(generateAdapter = false)
data class FrontendSection(
    @Json(name = "DISABLE_USERPASS_LOGIN")
    val disableUserpassLogin: Boolean = true
)

/**
 * Parses the raw JSON body from RomM's GET /api/heartbeat endpoint
 * into a [HeartbeatResponse].
 *
 * Supports both the RomM 5.0.0+ nested format (SYSTEM/EMULATION/FRONTEND sections)
 * and legacy flat-field responses for backward compatibility.
 */
object HeartbeatParser {

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
    private val jsonAdapter = moshi.adapter<HeartbeatJson>()

    /**
     * Parse heartbeat JSON. Returns Pair(response, error) where exactly one is non-null.
     */
    @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
    fun parse(body: String): HeartbeatParseResult {
        return try {
            val json = jsonAdapter.fromJson(body.trim())
            if (json == null) {
                HeartbeatParseResult(null, HeartbeatError.PARSE_ERROR)
            } else {
                // RomM 5.0.0+ nested format takes priority
                val version = json.SYSTEM?.version ?: json.version
                val setupComplete = when {
                    json.SYSTEM != null -> !json.SYSTEM.showSetupWizard
                    json.setup_complete != null -> json.setup_complete!!
                    else -> false
                }
                val userpassEnabled = when {
                    json.FRONTEND != null -> !json.FRONTEND.disableUserpassLogin
                    json.userpass_enabled != null -> json.userpass_enabled!!
                    else -> false
                }
                val emulatorJsEnabled = when {
                    json.EMULATION != null -> !json.EMULATION.disableEmulatorJs
                    json.emulatorjs_enabled != null -> json.emulatorjs_enabled!!
                    else -> false
                }

                HeartbeatParseResult(
                    HeartbeatResponse(
                        version = version,
                        setupComplete = setupComplete,
                        userpassEnabled = userpassEnabled,
                        emulatorJsEnabled = emulatorJsEnabled,
                        rawMessage = json.message
                    ),
                    null
                )
            }
        } catch (e: Exception) {
            HeartbeatParseResult(null, HeartbeatError.PARSE_ERROR)
        }
    }
}

/**
 * Result of parsing a heartbeat response.
 */
data class HeartbeatParseResult(
    val response: HeartbeatResponse?,
    val error: HeartbeatError?
)

/**
 * Result of executing a heartbeat HTTP call.
 */
sealed interface HeartbeatCallResult {
    data class Success(val response: HeartbeatResponse) : HeartbeatCallResult
    data class Failure(val error: HeartbeatError, val httpCode: Int? = null) : HeartbeatCallResult
}

/**
 * Executes GET /api/heartbeat against the given origin.
 */
fun executeHeartbeat(
    client: okhttp3.OkHttpClient,
    origin: String
): HeartbeatCallResult {
    if (origin.isBlank()) return HeartbeatCallResult.Failure(HeartbeatError.ORIGIN_NOT_CONFIGURED)

    val normalizedOrigin = RommOrigin.parse(origin)?.toUrl() ?: origin.removeSuffix("/")
    val url = "$normalizedOrigin/api/heartbeat"

    val request = okhttp3.Request.Builder()
        .url(url)
        .get()
        .build()

    return try {
        client.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> {
                    val body = response.body?.string()
                    if (body == null || body.isBlank()) {
                        HeartbeatCallResult.Failure(HeartbeatError.PARSE_ERROR, response.code)
                    } else {
                        val parseResult = HeartbeatParser.parse(body)
                        if (parseResult.response != null) {
                            HeartbeatCallResult.Success(parseResult.response)
                        } else {
                            HeartbeatCallResult.Failure(parseResult.error!!, response.code)
                        }
                    }
                }
                else -> HeartbeatCallResult.Failure(HeartbeatError.HTTP_ERROR, response.code)
            }
        }
    } catch (e: IOException) {
        val cause = e.cause ?: e
        if (cause is javax.net.ssl.SSLException ||
            cause.javaClass.name.contains("SSL", ignoreCase = true)) {
            HeartbeatCallResult.Failure(HeartbeatError.TLS_ERROR)
        } else {
            HeartbeatCallResult.Failure(HeartbeatError.NETWORK_ERROR)
        }
    }
}

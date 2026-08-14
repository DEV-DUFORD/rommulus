@file:OptIn(ExperimentalStdlibApi::class)

package com.romm.androidtv.network

import com.romm.androidtv.romm.ClientToken
import com.squareup.moshi.JsonClass
import com.squareup.moshi.KotlinJsonAdapterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI

data class DeviceAuthInitRequest(
    val clientDeviceIdentifier: String,
    val name: String,
    val client: String,
    val platform: String,
    val clientVersion: String,
    val requestedScopes: List<String>,
)

data class DeviceAuthInitInfo(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Int,
    val pollIntervalSeconds: Int,
)

sealed interface DeviceAuthInitResult {
    data class Success(val info: DeviceAuthInitInfo) : DeviceAuthInitResult
    data object Unsupported : DeviceAuthInitResult
    data object Failure : DeviceAuthInitResult
}

sealed interface DeviceAuthTokenResult {
    data object Pending : DeviceAuthTokenResult
    data object SlowDown : DeviceAuthTokenResult
    data class Approved(
        val token: ClientToken,
        val deviceId: String,
        val scopes: List<String>,
    ) : DeviceAuthTokenResult
    data object Denied : DeviceAuthTokenResult
    data object Expired : DeviceAuthTokenResult
    data object Failure : DeviceAuthTokenResult
}

@JsonClass(generateAdapter = false)
private data class DeviceAuthInitPayloadJson(
    val client_device_identifier: String,
    val name: String,
    val client: String,
    val platform: String,
    val client_version: String,
    val requested_scopes: List<String>,
)

@JsonClass(generateAdapter = false)
private data class DeviceAuthInitResponseJson(
    val device_code: String = "",
    val user_code: String = "",
    val verification_path_complete: String = "",
    val expires_in: Int = 0,
    val interval: Int = 0,
)

@JsonClass(generateAdapter = false)
private data class DeviceAuthTokenPayloadJson(val device_code: String)

@JsonClass(generateAdapter = false)
private data class DeviceAuthTokenResponseJson(
    val access_token: String = "",
    val device_id: String = "",
    val scopes: List<String> = emptyList(),
)

@JsonClass(generateAdapter = false)
private data class DeviceAuthErrorJson(val detail: String? = null)

object DeviceAuthService {
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val initPayloadAdapter = moshi.adapter<DeviceAuthInitPayloadJson>()
    private val initResponseAdapter = moshi.adapter<DeviceAuthInitResponseJson>()
    private val tokenPayloadAdapter = moshi.adapter<DeviceAuthTokenPayloadJson>()
    private val tokenResponseAdapter = moshi.adapter<DeviceAuthTokenResponseJson>()
    private val errorAdapter = moshi.adapter<DeviceAuthErrorJson>()

    fun initiate(
        client: okhttp3.OkHttpClient,
        origin: String,
        request: DeviceAuthInitRequest,
    ): DeviceAuthInitResult {
        val url = apiUrl(origin, "auth/device/init") ?: return DeviceAuthInitResult.Failure
        val payload = DeviceAuthInitPayloadJson(
            client_device_identifier = request.clientDeviceIdentifier,
            name = request.name,
            client = request.client,
            platform = request.platform,
            client_version = request.clientVersion,
            requested_scopes = request.requestedScopes,
        )
        val body = initPayloadAdapter.toJson(payload).toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder().url(url).post(body).build()

        return try {
            client.newCall(httpRequest).execute().use { response ->
                if (response.code == 404 || response.code == 405) {
                    return DeviceAuthInitResult.Unsupported
                }
                if (!response.isSuccessful) return DeviceAuthInitResult.Failure
                val parsed = response.body?.string()?.let(initResponseAdapter::fromJson)
                    ?: return DeviceAuthInitResult.Failure
                val verificationUrl = buildVerificationUrl(origin, parsed.verification_path_complete)
                    ?: return DeviceAuthInitResult.Failure
                if (
                    parsed.device_code.isBlank() ||
                    parsed.user_code.isBlank() ||
                    parsed.expires_in <= 0 ||
                    parsed.interval <= 0
                ) {
                    return DeviceAuthInitResult.Failure
                }
                DeviceAuthInitResult.Success(
                    DeviceAuthInitInfo(
                        deviceCode = parsed.device_code,
                        userCode = parsed.user_code,
                        verificationUrl = verificationUrl,
                        expiresInSeconds = parsed.expires_in,
                        pollIntervalSeconds = parsed.interval,
                    ),
                )
            }
        } catch (_: IOException) {
            DeviceAuthInitResult.Failure
        }
    }

    fun poll(
        client: okhttp3.OkHttpClient,
        origin: String,
        deviceCode: String,
    ): DeviceAuthTokenResult {
        val url = apiUrl(origin, "auth/device/token") ?: return DeviceAuthTokenResult.Failure
        val body = tokenPayloadAdapter.toJson(DeviceAuthTokenPayloadJson(deviceCode))
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).post(body).build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val parsed = response.body?.string()?.let(tokenResponseAdapter::fromJson)
                        ?: return DeviceAuthTokenResult.Failure
                    if (parsed.access_token.isBlank() || parsed.device_id.isBlank()) {
                        return DeviceAuthTokenResult.Failure
                    }
                    return DeviceAuthTokenResult.Approved(
                        token = ClientToken(parsed.access_token),
                        deviceId = parsed.device_id,
                        scopes = parsed.scopes,
                    )
                }

                val detail = response.body?.string()
                    ?.let { runCatching { errorAdapter.fromJson(it)?.detail }.getOrNull() }
                when (detail) {
                    "authorization_pending" -> DeviceAuthTokenResult.Pending
                    "slow_down" -> DeviceAuthTokenResult.SlowDown
                    "access_denied" -> DeviceAuthTokenResult.Denied
                    "expired_token" -> DeviceAuthTokenResult.Expired
                    else -> DeviceAuthTokenResult.Failure
                }
            }
        } catch (_: IOException) {
            DeviceAuthTokenResult.Failure
        }
    }

    fun fetchBearerUser(
        client: okhttp3.OkHttpClient,
        origin: String,
        token: ClientToken,
    ): VerifiedUser? {
        val url = apiUrl(origin, "users/me") ?: return null
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${token.raw}")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::parseVerifiedUser)
                    ?.takeIf { !it.username.isNullOrBlank() }
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun apiUrl(origin: String, path: String): okhttp3.HttpUrl? {
        val valid = RommServerAddress.parseAndNormalize(origin) as? ServerAddressResult.Valid
            ?: return null
        return RommServerAddress.toHttpUrl(valid).newBuilder()
            .addPathSegments("api/$path")
            .build()
    }

    private fun buildVerificationUrl(origin: String, relative: String): String? {
        val valid = RommServerAddress.parseAndNormalize(origin) as? ServerAddressResult.Valid
            ?: return null
        val uri = runCatching { URI(relative) }.getOrNull() ?: return null
        if (uri.isAbsolute || uri.host != null || !uri.path.startsWith("/")) return null

        return RommServerAddress.toHttpUrl(valid).newBuilder()
            .addEncodedPathSegments(uri.rawPath.trimStart('/'))
            .encodedQuery(uri.rawQuery)
            .build()
            .toString()
    }

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
}

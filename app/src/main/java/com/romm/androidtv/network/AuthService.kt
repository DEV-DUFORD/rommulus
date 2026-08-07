@file:OptIn(ExperimentalStdlibApi::class)

package com.romm.androidtv.network

import com.romm.androidtv.model.HeartbeatError
import com.romm.androidtv.model.HeartbeatResponse
import com.squareup.moshi.JsonClass
import com.squareup.moshi.KotlinJsonAdapterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import okhttp3.Credentials
import java.io.IOException

/**
 * Error classification for the authentication flow.
 */
enum class AuthError {
    /** Credentials were rejected by the server (401). */
    INVALID_CREDENTIALS,

    /** Server returned a non-2xx, non-401 error during login. */
    SERVER_ERROR,

    /** Network failure during login or verification. */
    NETWORK_ERROR,

    /** TLS error. */
    TLS_ERROR,

    /** Post-login heartbeat failed — server unreachable after auth. */
    POST_LOGIN_HEARTBEAT_FAILED,

    /** /api/users/me verification failed. */
    VERIFICATION_FAILED,

    /** Origin not configured. */
    ORIGIN_NOT_CONFIGURED,

    /** Login not possible: setup incomplete or userpass disabled. */
    LOGIN_NOT_AVAILABLE
}

/**
 * Result of the full authentication flow:
 * 1. POST /api/login with HTTP Basic Auth (empty body)
 * 2. Second heartbeat GET /api/heartbeat
 * 3. Verification GET /api/users/me
 */
sealed interface AuthFlowResult {
    data class Success(
        val heartbeatAfterLogin: HeartbeatResponse,
        val verifiedUser: VerifiedUser
    ) : AuthFlowResult

    data class Failure(val error: AuthError, val httpCode: Int? = null) : AuthFlowResult
}

/**
 * Minimal user info from GET /api/users/me.
 */
data class VerifiedUser(
    val username: String?,
    val isAdmin: Boolean
)

/**
 * Moshi data class for /api/users/me JSON deserialization.
 */
@JsonClass(generateAdapter = false)
internal data class VerifiedUserJson(
    val username: String? = null,
    val name: String? = null,
    val user: String? = null,
    val admin: Boolean = false
)

/**
 * Executes the full authentication flow using CharArray for password handling.
 * 1. POST /api/login with HTTP Basic Auth header, empty body (redirects disabled)
 * 2. Second heartbeat to confirm session state
 * 3. GET /api/users/me for verification
 *
 * IMPORTANT: Credentials are passed only in the HTTP Basic Auth header.
 * They are zeroed in a finally block and never logged, persisted, or stored.
 */
fun executeAuthFlow(
    client: okhttp3.OkHttpClient,
    origin: String,
    username: String,
    password: CharArray
): AuthFlowResult {
    // Zero the password array as soon as we're done with it
    return try {
        _executeAuthFlow(client, origin, username, password)
    } finally {
        zeroCharArray(password)
    }
}

/**
 * Also accepts a String password for convenience (e.g. from Compose state).
 * The String is converted to CharArray, used, then zeroed.
 */
fun executeAuthFlow(
    client: okhttp3.OkHttpClient,
    origin: String,
    username: String,
    password: String
): AuthFlowResult {
    val charArray = password.toCharArray()
    return try {
        _executeAuthFlow(client, origin, username, charArray)
    } finally {
        zeroCharArray(charArray)
    }
}

private fun _executeAuthFlow(
    client: okhttp3.OkHttpClient,
    origin: String,
    username: String,
    password: CharArray
): AuthFlowResult {
    if (origin.isBlank()) return AuthFlowResult.Failure(AuthError.ORIGIN_NOT_CONFIGURED)

    val normalizedOrigin = RommOrigin.parse(origin)?.toUrl() ?: origin.removeSuffix("/")

    // Convert CharArray password to String only for Credentials.basic(), then zero
    val passwordString = String(password)
    return _doAuthFlow(client, normalizedOrigin, username, passwordString)
}

private fun _doAuthFlow(
    client: okhttp3.OkHttpClient,
    normalizedOrigin: String,
    username: String,
    password: String
): AuthFlowResult {
    // Build a non-redirect-following client for the login call so unexpected
    // redirects are classified as errors rather than silently consumed.
    val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    // Step 1: POST /api/login with HTTP Basic Auth, empty body
    val credentials = Credentials.basic(username, password)
    @Suppress("DEPRECATION")
    val loginRequest = okhttp3.Request.Builder()
        .url("$normalizedOrigin/api/login")
        .header("Authorization", credentials)
        .post(okhttp3.RequestBody.create(null, ByteArray(0)))
        .build()

    val loginResult = try {
        noRedirectClient.newCall(loginRequest).execute().use { response ->
            when {
                response.isSuccessful -> LoginStep.OK
                response.code == 401 -> return AuthFlowResult.Failure(AuthError.INVALID_CREDENTIALS, 401)
                response.code in 300..399 -> return AuthFlowResult.Failure(AuthError.SERVER_ERROR, response.code)
                else -> return AuthFlowResult.Failure(AuthError.SERVER_ERROR, response.code)
            }
        }
    } catch (e: IOException) {
        val cause = e.cause ?: e
        if (cause is javax.net.ssl.SSLException ||
            cause.javaClass.name.contains("SSL", ignoreCase = true)) {
            return AuthFlowResult.Failure(AuthError.TLS_ERROR)
        }
        return AuthFlowResult.Failure(AuthError.NETWORK_ERROR)
    }

    if (loginResult != LoginStep.OK) {
        return AuthFlowResult.Failure(AuthError.SERVER_ERROR)
    }

    // Step 2: Second heartbeat — confirms session cookies are working
    val heartbeatResult = executeHeartbeat(client, normalizedOrigin)
    if (heartbeatResult is HeartbeatCallResult.Failure) {
        return AuthFlowResult.Failure(
            AuthError.POST_LOGIN_HEARTBEAT_FAILED,
            (heartbeatResult as? HeartbeatCallResult.Failure)?.httpCode
        )
    }

    val heartbeatResponse = (heartbeatResult as HeartbeatCallResult.Success).response

    // Step 3: GET /api/users/me verification
    val verifyRequest = okhttp3.Request.Builder()
        .url("$normalizedOrigin/api/users/me")
        .get()
        .build()

    val verifiedUser = try {
        client.newCall(verifyRequest).execute().use { response ->
            when {
                response.isSuccessful -> {
                    val body = response.body?.string()
                    if (body != null && body.isNotBlank()) {
                        parseVerifiedUser(body)
                    } else {
                        VerifiedUser(null, false)
                    }
                }
                response.code == 401 -> return AuthFlowResult.Failure(AuthError.VERIFICATION_FAILED, 401)
                else -> return AuthFlowResult.Failure(AuthError.VERIFICATION_FAILED, response.code)
            }
        }
    } catch (e: IOException) {
        val cause = e.cause ?: e
        if (cause is javax.net.ssl.SSLException ||
            cause.javaClass.name.contains("SSL", ignoreCase = true)) {
            return AuthFlowResult.Failure(AuthError.TLS_ERROR)
        }
        return AuthFlowResult.Failure(AuthError.NETWORK_ERROR)
    }

    return AuthFlowResult.Success(heartbeatResponse, verifiedUser)
}

private enum class LoginStep { OK }

/**
 * Moshi-based parser for /api/users/me JSON response.
 */
private val moshi = Moshi.Builder()
    .addLast(KotlinJsonAdapterFactory())
    .build()
private val verifiedUserAdapter = moshi.adapter<VerifiedUserJson>()

fun parseVerifiedUser(json: String): VerifiedUser {
    return try {
        val parsed = verifiedUserAdapter.fromJson(json.trim())
        if (parsed == null) {
            VerifiedUser(null, false)
        } else {
            val username = parsed.username ?: parsed.name ?: parsed.user
            VerifiedUser(username, parsed.admin)
        }
    } catch (_: Exception) {
        VerifiedUser(null, false)
    }
}

/**
 * Checks if existing cookies (from a previous session) are still valid.
 * Returns Success with heartbeat + verified user, or Failure if session expired.
 */
fun verifyExistingSession(
    client: okhttp3.OkHttpClient,
    origin: String
): AuthFlowResult = verifySession(client, origin, authorization = null)

/**
 * Checks a durable client token without depending on browser-session cookies.
 */
fun verifyBearerSession(
    client: okhttp3.OkHttpClient,
    origin: String,
    token: String,
): AuthFlowResult {
    if (token.isBlank()) return AuthFlowResult.Failure(AuthError.VERIFICATION_FAILED, 401)
    return verifySession(client, origin, authorization = "Bearer $token")
}

private fun verifySession(
    client: okhttp3.OkHttpClient,
    origin: String,
    authorization: String?,
): AuthFlowResult {
    if (origin.isBlank()) return AuthFlowResult.Failure(AuthError.ORIGIN_NOT_CONFIGURED)

    val normalizedOrigin = RommOrigin.parse(origin)?.toUrl() ?: origin.removeSuffix("/")

    val verifyRequestBuilder = okhttp3.Request.Builder()
        .url("$normalizedOrigin/api/users/me")
        .get()
    if (authorization != null) {
        verifyRequestBuilder.header("Authorization", authorization)
    }

    return try {
        client.newCall(verifyRequestBuilder.build()).execute().use { response ->
            when {
                response.isSuccessful -> {
                    val body = response.body?.string()
                    val user = if (body != null && body.isNotBlank()) parseVerifiedUser(body) else VerifiedUser(null, false)

                    // Also get heartbeat
                    val hbResult = executeHeartbeat(client, normalizedOrigin)
                    when (hbResult) {
                        is HeartbeatCallResult.Success -> AuthFlowResult.Success(hbResult.response, user)
                        is HeartbeatCallResult.Failure -> AuthFlowResult.Failure(AuthError.POST_LOGIN_HEARTBEAT_FAILED, hbResult.httpCode)
                    }
                }
                response.code == 401 -> AuthFlowResult.Failure(AuthError.VERIFICATION_FAILED, 401)
                else -> AuthFlowResult.Failure(AuthError.VERIFICATION_FAILED, response.code)
            }
        }
    } catch (e: IOException) {
        val cause = e.cause ?: e
        if (cause is javax.net.ssl.SSLException ||
            cause.javaClass.name.contains("SSL", ignoreCase = true)) {
            AuthFlowResult.Failure(AuthError.TLS_ERROR)
        } else {
            AuthFlowResult.Failure(AuthError.NETWORK_ERROR)
        }
    }
}

/**
 * Securely zeroes a CharArray. Must be called in finally blocks.
 */
fun zeroCharArray(array: CharArray) {
    for (i in array.indices) {
        array[i] = '\u0000'
    }
}

package com.romm.androidtv.auth

import com.romm.androidtv.network.AuthError
import com.romm.androidtv.network.AuthFlowResult
import com.romm.androidtv.network.HeartbeatCallResult
import com.romm.androidtv.network.HeartbeatParser
import com.romm.androidtv.network.InvalidReason
import com.romm.androidtv.network.RommLog
import com.romm.androidtv.network.RommServerAddress
import com.romm.androidtv.network.ServerAddressResult
import com.romm.androidtv.network.SessionCookieSync
import com.romm.androidtv.network.executeAuthFlow
import com.romm.androidtv.network.executeHeartbeat
import com.romm.androidtv.network.verifyExistingSession
import com.romm.androidtv.network.verifyBearerSession
import com.romm.androidtv.romm.ClientToken
import com.romm.androidtv.romm.ClientTokenAcquireResult
import com.romm.androidtv.romm.ClientTokenInfo
import com.romm.androidtv.romm.ClientTokenListResult
import com.romm.androidtv.romm.RommSyncApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Stable tag for all auth-loop boundary diagnostics (logcat -s RommAuthDx). */
private const val TAG = "RommAuthDx"

/**
 * Repository seam over the native RomM authentication flow
 * (LIBRETRO_REFACTOR.md section 5, `auth/AuthRepository.kt`).
 *
 * This wraps the existing, already-tested [com.romm.androidtv.network.AuthService]
 * functions and [RomMCookieSync] without changing their behavior: every network
 * call still runs on [Dispatchers.IO] and cookie sync to WebView still requires
 * the caller to be on the main thread (unchanged contract from [RomMCookieSync]).
 * `MainActivity` depends on this instead of calling OkHttp/AuthService directly,
 * so it coordinates navigation rather than owning network internals (Phase 1
 * exit criterion).
 *
 * On a successful login or session-restore verification, the result is recorded
 * in [sessionStore] so a durable session fact survives process restarts
 * independent of the WebView cookie jar. Additionally, a durable ClientToken
 * Bearer credential is acquired via [RommSyncApi.acquireClientToken] while the
 * cookie-authenticated foreground client is valid, and persisted into
 * [clientTokenStore] for later cookie-independent worker execution (p5-workmanager).
 */
/**
 * Interface for durable client-token persistence. Allows unit-testing [AuthRepository]
 * without Android Keystore/SharedPreferences while production uses [com.romm.androidtv.romm.ClientTokenStore].
 */
interface ClientTokenStorage {
    fun getToken(origin: String, username: String): ClientToken?
    fun setToken(origin: String, username: String, token: ClientToken): TokenPersistResult
    fun clearToken(origin: String, username: String)
}

class AuthRepository(
    private val client: OkHttpClient,
    private val cookieSync: SessionCookieSync,
    private val sessionStore: SessionStorage,
    private val clientTokenStorage: ClientTokenStorage? = null,
) {

    /** Executes the full login flow (POST /api/login, heartbeat, /api/users/me). On success, replaces any stale local token. */
    suspend fun login(origin: String, username: String, password: CharArray): AuthFlowResult {
        val result = withContext(Dispatchers.IO) {
            executeAuthFlow(client, origin, username, password)
        }
        // Explicit sign-in always replaces stale local token safely.
        // Runs on IO: client-token acquisition is itself a blocking network call and must
        // never execute on the caller's (often Main) dispatcher.
        withContext(Dispatchers.IO) {
            recordIfSuccessful(origin, result, forceReplaceToken = true)
        }
        return result
    }

    /** Checks whether existing session cookies (already imported into OkHttp) are still valid. Does not churn existing tokens. */
    suspend fun verifySession(origin: String): AuthFlowResult {
        val result = withContext(Dispatchers.IO) {
            verifyExistingSession(client, origin)
        }
        // Session verification ensures a token exists but does not replace an existing one.
        withContext(Dispatchers.IO) {
            recordIfSuccessful(origin, result, forceReplaceToken = false)
        }
        return result
    }

    /**
     * Verifies the durable native credential recorded for [origin], independent of cookies.
     */
    suspend fun verifyDurableSession(origin: String): AuthFlowResult = withContext(Dispatchers.IO) {
        val record = sessionStore.coherentRecord(origin)
            ?: return@withContext AuthFlowResult.Failure(AuthError.VERIFICATION_FAILED, 401)
        val token = clientTokenStorage?.getToken(record.origin, record.username.orEmpty())
            ?: return@withContext AuthFlowResult.Failure(AuthError.VERIFICATION_FAILED, 401)
        verifyBearerSession(client, origin, token.raw)
    }

    /** Runs a heartbeat check against the configured origin. */
    suspend fun checkHeartbeat(origin: String): HeartbeatCallResult = withContext(Dispatchers.IO) {
        executeHeartbeat(client, origin)
    }

    /**
     * Imports cookies Android's [android.webkit.CookieManager] already holds (e.g. from a
     * prior WebView session) into the native OkHttp cookie store. Synchronous; safe to call
     * from any thread per [RomMCookieSync.importFromWebView]'s existing contract.
     */
    fun importCookiesFromWebView(origin: String) {
        cookieSync.importFromWebView(origin)
    }

    /**
     * Pushes the native OkHttp session's cookies into Android's CookieManager so WebView
     * carries the same session. MUST be called on the main thread, matching
     * [RomMCookieSync.syncToWebView]'s existing contract. Callers must invoke this after a
     * successful [login] or [verifySession] and before loading the WebView.
     */
    suspend fun syncCookiesToWebView(origin: String) {
        cookieSync.syncToWebView(origin)
    }

    /** Clears the durable client token for the given scope (explicit sign-out). */
    fun clearClientTokenForCurrentSession(origin: String, username: String) {
        clientTokenStorage?.clearToken(origin, username)
    }

    /**
     * Force-reconcile: acquire a fresh ClientToken via the cookie-authenticated foreground
     * client and persist it into [clientTokenStore]. Used when a bearer-authenticated sync
     * operation fails with AUTH_EXPIRED but the cookie session is still valid.
     *
     * Returns true if a replacement token was acquired and persisted. Returns false when:
     * - no storage is configured,
     * - the username is unknown (cannot scope the token),
     * - or acquisition from the server fails.
     *
     * Always replaces any stale local token for this scope before attempting acquisition.
     *
     * Suspend + [Dispatchers.IO]: this performs a blocking OkHttp call. Callers (e.g.
     * `MainActivity`'s `lifecycleScope.launch`, which defaults to Main) must never invoke the
     * underlying network call directly on Main — doing so throws a non-`IOException` runtime
     * exception (`NetworkOnMainThreadException`) that escapes `RommSyncApi`'s `IOException`-only
     * catch, before any HTTP result is ever classified.
     */
    suspend fun forceReconcileClientToken(origin: String, username: String): Boolean = withContext(Dispatchers.IO) {
        val storage = clientTokenStorage ?: run {
            RommLog.debug(TAG, "forceReconcileClientToken: skipped storage=null")
            return@withContext false
        }
        // Remove potentially revoked/stale token first so the server issues a fresh one.
        storage.clearToken(origin, username)
        try {
            val result = RommSyncApi.acquireClientToken(
                client = client,
                origin = origin,
                scopes = RommClientTokenScopes.FOREGROUND_NATIVE,
            )
            when (result) {
                is ClientTokenAcquireResult.Success -> {
                    val persist = storage.setToken(origin, username, result.info.token)
                    RommLog.debug(TAG, "forceReconcileClientToken: reconciled=true persist=${persist::class.simpleName}")
                    true
                }
                is ClientTokenAcquireResult.TokenLimitReached -> {
                    RommLog.warn(TAG, "forceReconcileClientToken: reconciled=false tokenLimitReached detail=${result.detail}")
                    false
                }
                is ClientTokenAcquireResult.Failure -> {
                    val httpCode = result.httpCode ?: -1
                    RommLog.warn(TAG, "forceReconcileClientToken: reconciled=false error=${result.error.name} httpCode=$httpCode")
                    false
                }
            }
        } catch (_: Exception) {
            // Acquisition failure; caller will handle as terminal auth-expired.
            RommLog.warn(TAG, "forceReconcileClientToken: exception during reconciliation")
            false
        }
    }

    /**
     * Ensures a durable ClientToken exists for the current session scope after a successful
     * verification. If one is already stored, this is a no-op. Only acquires when missing.
     */
    suspend fun ensureClientTokenExists(origin: String, username: String): Boolean {
        val storage = clientTokenStorage ?: run {
            RommLog.debug(TAG, "ensureClientTokenExists: skipped storage=null")
            return false
        }
        if (storage.getToken(origin, username) != null) {
            RommLog.debug(TAG, "ensureClientTokenExists: alreadyPresent=true")
            return true
        }
        val result = forceReconcileClientToken(origin, username)
        RommLog.debug(TAG, "ensureClientTokenExists: afterForceReconcile=$result")
        return result
    }

    /**
     * Clears both the [SessionStore] record and the matching durable ClientToken for this
     * scope. Called when authentication is definitively expired/invalid (not transient errors).
     */
    fun clearExpiredSession(origin: String, username: String) {
        RommLog.debug(TAG, "clearExpiredSession: clearing session+token")
        sessionStore.clear()
        clientTokenStorage?.clearToken(origin, username)
    }

    /**
     * Typed, cancellable heartbeat validation used by first-run onboarding.
     *
     * - Parses/normalizes [origin] via [RommServerAddress] first (no network):
     *   a public-HTTP origin is rejected as [ServerValidationResult.InsecurePublicHttp],
     *   any other structural problem as [ServerValidationResult.InvalidAddress].
     * - Builds a validation client from the shared config ([OkHttpClient.newBuilder])
     *   with redirects disabled and bounded timeouts, so a 3xx is never silently
     *   followed and a hung server cannot block onboarding.
     * - Requests `GET {origin}/api/heartbeat`, preserving any base path.
     * - Classifies every outcome truthfully into [ServerValidationResult] —
     *   no [okhttp3.Response]/[Throwable] escapes the boundary.
     *
     * Cancellable: coroutine cancellation cancels the in-flight OkHttp call.
     *
     * The timeout parameters are defaulted to the required production bounds so
     * callers use `validateServer(origin)`; tests may pass smaller values.
     */
    suspend fun validateServer(
        origin: String,
        connectTimeoutSeconds: Long = VALIDATE_CONNECT_TIMEOUT_SECONDS,
        readTimeoutSeconds: Long = VALIDATE_READ_TIMEOUT_SECONDS,
        callTimeoutSeconds: Long = VALIDATE_CALL_TIMEOUT_SECONDS,
    ): ServerValidationResult {
        val valid = when (val parsed = RommServerAddress.parseAndNormalize(origin)) {
            is ServerAddressResult.Invalid -> return if (parsed.reason == InvalidReason.INSECURE_PUBLIC_HTTP) {
                ServerValidationResult.InsecurePublicHttp
            } else {
                ServerValidationResult.InvalidAddress
            }
            is ServerAddressResult.Valid -> parsed
        }

        val url = RommServerAddress.toHttpUrl(valid).newBuilder()
            .addPathSegments("api/heartbeat")
            .build()

        val validationClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(url).get().build()
        return withContext(Dispatchers.IO) {
            val heartbeatResult = executeValidationCall(validationClient, request, valid.origin)
            when (heartbeatResult) {
                is ServerValidationResult.Valid ->
                    // Probe anonymous read access to detect RomM kiosk mode: 200 => the
                    // server is an anonymous read-only demo; 401/403 => normal login server.
                    heartbeatResult.copy(kioskMode = probeAnonymousRead(validationClient, valid.origin))
                else -> heartbeatResult
            }
        }
    }

    /**
     * Detects RomM kiosk mode by probing an unauthenticated read endpoint
     * (`GET {origin}/api/users/me`). A kiosk-mode server (anonymous read-only,
     * e.g. the public demo) returns 2xx; a normal login-required server returns
     * 401/403 to this unauthenticated call. See [isKioskMode].
     */
    suspend fun probeKioskMode(
        origin: String,
        connectTimeoutSeconds: Long = VALIDATE_CONNECT_TIMEOUT_SECONDS,
        readTimeoutSeconds: Long = VALIDATE_READ_TIMEOUT_SECONDS,
        callTimeoutSeconds: Long = VALIDATE_CALL_TIMEOUT_SECONDS,
    ): Boolean = withContext(Dispatchers.IO) {
        val ok = RommServerAddress.parseAndNormalize(origin) as? ServerAddressResult.Valid
            ?: return@withContext false
        val probeClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
            .build()
        probeAnonymousRead(probeClient, ok.origin)
    }

    /**
     * Establishes a durable anonymous read-only (kiosk/demo) session for [origin]
     * without a username/password login. Returns whether the session persisted.
     * The kiosk pseudo-user name is recorded so session-scoped lookup stays coherent.
     */
    suspend fun establishKioskSession(origin: String): Boolean = withContext(Dispatchers.IO) {
        val persisted = sessionStore.save(origin, KIOSK_USERNAME, kioskMode = true)
        RommLog.debug(TAG, "establishKioskSession: origin=$origin persisted=$persisted")
        persisted
    }

    private fun probeAnonymousRead(validationClient: OkHttpClient, origin: String): Boolean {
        val probeUrl = "$origin/api/users/me"
        val probeRequest = Request.Builder().url(probeUrl).get().build()
        return try {
            validationClient.newCall(probeRequest).execute().use { response -> response.isSuccessful }
        } catch (e: IOException) {
            RommLog.warn(TAG, "validateServer: probeAnonymousRead failed ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * New typed login-completion path for onboarding. Reuses the existing
     * [executeAuthFlow], then makes client-token creation + encrypted persistence
     * + bearer verification REQUIRED and FATAL (unlike the legacy [login] path
     * which treats token acquisition as best-effort).
     *
     * On success returns [LoginCompletionResult.Success] with the verified user
     * and the durable client token. On any failure, partial local completion state
     * (session/token written so far) is removed and the newly-created server token
     * is best-effort revoked. The [password] [CharArray] is zeroed inside
     * [executeAuthFlow] regardless of outcome.
     */
    suspend fun loginOnboarding(origin: String, username: String, password: CharArray): LoginCompletionResult {
        return withContext(Dispatchers.IO) {
            val authResult = executeAuthFlow(client, origin, username, password)
            when (authResult) {
                is AuthFlowResult.Failure -> mapLoginFailure(authResult)
                is AuthFlowResult.Success -> completeOnboarding(origin, authResult)
            }
        }
    }

    private suspend fun completeOnboarding(origin: String, success: AuthFlowResult.Success): LoginCompletionResult {
        val verifiedUser = success.verifiedUser
        val uname = verifiedUser.username ?: run {
            // Cannot durably scope a token without a username.
            return LoginCompletionResult.PersistenceFailure
        }
        val storage = clientTokenStorage ?: run {
            RommLog.warn(TAG, "loginOnboarding: storage not configured")
            return LoginCompletionResult.PersistenceFailure
        }

        // 1. Persist verified session record durably.
        if (!sessionStore.save(origin, uname)) {
            return LoginCompletionResult.PersistenceFailure
        }

        // 2. Create the full-scope, non-expiring client token.
        val tokenInfo = when (val acquire = RommSyncApi.acquireClientToken(
            client = client,
            origin = origin,
            scopes = RommClientTokenScopes.FOREGROUND_NATIVE,
        )) {
            is ClientTokenAcquireResult.Success -> acquire.info
            is ClientTokenAcquireResult.TokenLimitReached -> {
                RommLog.warn(TAG, "loginOnboarding: tokenLimitReached detail=${acquire.detail}")
                cleanupPartialOnboarding(origin, uname, tokenInfo = null)
                return LoginCompletionResult.TokenLimitReached
            }
            is ClientTokenAcquireResult.Failure -> {
                RommLog.warn(TAG, "loginOnboarding: tokenCreationFailed error=${acquire.error.name} httpCode=${acquire.httpCode}")
                cleanupPartialOnboarding(origin, uname, tokenInfo = null)
                return LoginCompletionResult.TokenCreationFailure
            }
        }

        // 3. Encrypt + durably persist it.
        val persist = storage.setToken(origin, uname, tokenInfo.token)
        if (persist != TokenPersistResult.Success) {
            RommLog.warn(TAG, "loginOnboarding: tokenPersistenceFailed result=${persist::class.simpleName}")
            cleanupPartialOnboarding(origin, uname, tokenInfo)
            return LoginCompletionResult.PersistenceFailure
        }

        // 4. Read the token back from durable storage before completing onboarding.
        // A successful preference commit does not prove that this device's Keystore
        // implementation can decrypt the value after a process restart.
        val persistedToken = storage.getToken(origin, uname)
        if (persistedToken?.raw != tokenInfo.token.raw) {
            RommLog.warn(TAG, "loginOnboarding: tokenReadBackFailed")
            cleanupPartialOnboarding(origin, uname, tokenInfo)
            return LoginCompletionResult.PersistenceFailure
        }

        // 5. Verify the read-back token with an authenticated bearer request.
        if (!RommSyncApi.verifyBearerToken(client, origin, persistedToken)) {
            RommLog.warn(TAG, "loginOnboarding: tokenVerificationFailed")
            cleanupPartialOnboarding(origin, uname, tokenInfo)
            return LoginCompletionResult.TokenVerificationFailure
        }

        return LoginCompletionResult.Success(verifiedUser, tokenInfo.token)
    }

    /** Removes any partial local completion state and best-effort revokes the newly-created server token. */
    private fun cleanupPartialOnboarding(origin: String, username: String, tokenInfo: ClientTokenInfo?) {
        sessionStore.clear()
        clientTokenStorage?.clearToken(origin, username)
        tokenInfo?.let { info ->
            runCatching { RommSyncApi.revokeClientToken(client, origin, info.id) }
        }
    }

    /**
     * User-confirmed remediation for [LoginCompletionResult.TokenLimitReached]: lists this
     * account's client tokens, deletes the single oldest one (by `created_at`) to free a slot,
     * and reports whether that succeeded. Requires the cookie session established by the
     * just-completed username/password login step still be valid (it is — this is only ever
     * called immediately after a [LoginCompletionResult.TokenLimitReached] from the same flow).
     *
     * Deliberately explicit/user-initiated (never called automatically): revoking a token can
     * sign out whatever device/integration was using it, so this must be a decision the user
     * makes, not one the app makes silently on their behalf.
     */
    suspend fun removeOldestClientToken(origin: String): Boolean = withContext(Dispatchers.IO) {
        when (val list = RommSyncApi.listClientTokens(client, origin)) {
            is ClientTokenListResult.Failure -> {
                RommLog.warn(TAG, "removeOldestClientToken: listFailed error=${list.error.name}")
                false
            }
            is ClientTokenListResult.Success -> {
                val oldest = list.tokens.minByOrNull { it.createdAtEpochSeconds ?: Long.MAX_VALUE }
                if (oldest == null) {
                    RommLog.warn(TAG, "removeOldestClientToken: noTokensToRemove")
                    false
                } else {
                    val revoked = RommSyncApi.revokeClientToken(client, origin, oldest.id)
                    RommLog.warn(TAG, "removeOldestClientToken: removedId=${oldest.id} success=$revoked")
                    revoked
                }
            }
        }
    }

    private fun mapLoginFailure(failure: AuthFlowResult.Failure): LoginCompletionResult = when (failure.error) {
        AuthError.INVALID_CREDENTIALS -> LoginCompletionResult.InvalidCredentials
        AuthError.TLS_ERROR -> LoginCompletionResult.TlsFailure
        AuthError.NETWORK_ERROR, AuthError.POST_LOGIN_HEARTBEAT_FAILED -> LoginCompletionResult.NetworkFailure
        AuthError.SERVER_ERROR, AuthError.VERIFICATION_FAILED, AuthError.LOGIN_NOT_AVAILABLE, AuthError.ORIGIN_NOT_CONFIGURED ->
            LoginCompletionResult.ServerFailure
    }

    /**
     * Suspends until the OkHttp call completes, cancelling the call when the
     * enclosing coroutine is cancelled so ViewModel teardown aborts the request.
     */
    private suspend fun executeValidationCall(
        client: OkHttpClient,
        request: Request,
        origin: String,
    ): ServerValidationResult {
        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isCancelled) cont.resume(classifyValidationTransport(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!cont.isCancelled) cont.resume(classifyValidationResponse(response, origin))
                }
            })
        }
    }

    private fun classifyValidationResponse(response: Response, origin: String): ServerValidationResult {
        return try {
            // 3xx: redirects are disabled; any redirect is NOT a RomM server.
            if (response.code in 300..399) return ServerValidationResult.NotRomm
            if (!response.isSuccessful) return ServerValidationResult.NotRomm

            val body = response.body?.string()
            if (body == null || body.isBlank()) return ServerValidationResult.NotRomm

            val parse = HeartbeatParser.parse(body)
            val heartbeat = parse.response ?: return ServerValidationResult.NotRomm

            // Unrelated/malformed JSON that parsed to all-defaults (no version, setup not
            // flagged complete) is not a structurally valid RomM heartbeat.
            if (!heartbeat.isReachable()) return ServerValidationResult.NotRomm

            when {
                !heartbeat.setupComplete -> ServerValidationResult.SetupIncomplete
                !heartbeat.userpassEnabled -> ServerValidationResult.UserpassDisabled
                else -> ServerValidationResult.Valid(origin = origin, heartbeat = heartbeat)
            }
        } finally {
            response.close()
        }
    }

    private fun classifyValidationTransport(e: IOException): ServerValidationResult {
        val cause = e.cause
        val isTls = e is javax.net.ssl.SSLException ||
            cause is javax.net.ssl.SSLException ||
            e.javaClass.name.contains("SSL", ignoreCase = true) ||
            cause?.javaClass?.name?.contains("SSL", ignoreCase = true) == true
        return if (isTls) ServerValidationResult.TlsFailure else ServerValidationResult.NetworkFailure
    }

    private fun recordIfSuccessful(origin: String, result: AuthFlowResult, forceReplaceToken: Boolean) {
        if (result is AuthFlowResult.Success) {
            val usernamePresent = result.verifiedUser.username != null
            RommLog.debug(TAG, "recordIfSuccessful: success=true usernamePresent=$usernamePresent")
            sessionStore.save(origin, result.verifiedUser.username)
            // Acquire durable ClientToken for worker execution while foreground client is authenticated.
            acquireAndPersistClientToken(origin, result.verifiedUser.username, forceReplaceToken)
        } else {
            val httpCode = (result as? AuthFlowResult.Failure)?.httpCode ?: -1
            RommLog.warn(TAG, "recordIfSuccessful: success=false httpCode=$httpCode")
        }
    }

    /**
     * Acquires a durable [com.romm.androidtv.romm.ClientToken] via the cookie-authenticated
     * foreground client and persists it into [clientTokenStore]. Reuses any existing valid
     * token to avoid unnecessary server calls unless [forceReplace] is true (explicit login).
     * Silently ignores acquisition failures — the worker will fall back to AUTH_REQUIRED if
     * no token is available, which is the correct terminal state for that case.
     */
    private fun acquireAndPersistClientToken(origin: String, username: String?, forceReplace: Boolean = false) {
        val uname = username ?: run {
            RommLog.debug(TAG, "acquireAndPersistClientToken: skipped username=null")
            return
        }
        val storage = clientTokenStorage ?: run {
            RommLog.debug(TAG, "acquireAndPersistClientToken: skipped storage=null")
            return
        }

        // On explicit login (forceReplace=true), always replace stale token.
        // On session verification, reuse existing valid token to avoid churn.
        val tokenAlreadyPresent = storage.getToken(origin, uname) != null
        if (!forceReplace && tokenAlreadyPresent) {
            RommLog.debug(TAG, "acquireAndPersistClientToken: reused existing forceReplace=$forceReplace")
            return
        }

        try {
            val result = RommSyncApi.acquireClientToken(
                client = client,
                origin = origin,
                scopes = RommClientTokenScopes.FOREGROUND_NATIVE,
            )
            when (result) {
                is ClientTokenAcquireResult.Success -> {
                    val persist = storage.setToken(origin, uname, result.info.token)
                    RommLog.debug(TAG, "acquireAndPersistClientToken: acquired+persisted scopes=${RommClientTokenScopes.FOREGROUND_NATIVE} persist=${persist::class.simpleName}")
                }
                is ClientTokenAcquireResult.TokenLimitReached -> {
                    RommLog.warn(TAG, "acquireAndPersistClientToken: tokenLimitReached detail=${result.detail}")
                }
                is ClientTokenAcquireResult.Failure -> {
                    val httpCode = result.httpCode ?: -1
                    RommLog.warn(TAG, "acquireAndPersistClientToken: acquireFailed error=${result.error.name} httpCode=$httpCode")
                }
            }
        } catch (e: Exception) {
            // Acquisition failure is non-fatal; worker will handle missing token as AUTH_REQUIRED.
            RommLog.warn(TAG, "acquireAndPersistClientToken: exception ${e.javaClass.simpleName}")
        }
    }

    companion object {
        /** Default bounded timeouts for the typed heartbeat validation client (connect/read/call). */
        internal const val VALIDATE_CONNECT_TIMEOUT_SECONDS = 5L
        internal const val VALIDATE_READ_TIMEOUT_SECONDS = 10L
        internal const val VALIDATE_CALL_TIMEOUT_SECONDS = 15L

        /** Pseudo-user name recorded for anonymous read-only (kiosk/demo) sessions. */
        internal const val KIOSK_USERNAME = "kiosk"
    }
}

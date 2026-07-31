package com.romm.androidtv.auth

import com.romm.androidtv.network.AuthFlowResult
import com.romm.androidtv.network.HeartbeatCallResult
import com.romm.androidtv.network.RomMCookieSync
import com.romm.androidtv.network.executeAuthFlow
import com.romm.androidtv.network.executeHeartbeat
import com.romm.androidtv.network.verifyExistingSession
import com.romm.androidtv.romm.ClientToken
import com.romm.androidtv.romm.ClientTokenAcquireResult
import com.romm.androidtv.romm.RommSyncApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Stable tag for all auth-loop boundary diagnostics (logcat -s RommAuthDx). */
private const val TAG = "RommAuthDx"

/** Safe diagnostic logger: swallows unmocked android.util.Log in JVM unit tests. */
private fun diagLog(priority: Int, message: String) {
    try { android.util.Log.println(priority, TAG, message) } catch (_: Exception) { /* JVM test env */ }
}

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
    fun setToken(origin: String, username: String, token: ClientToken)
    fun clearToken(origin: String, username: String)
}

class AuthRepository(
    private val client: OkHttpClient,
    private val cookieSync: RomMCookieSync,
    private val sessionStore: SessionStore,
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
            diagLog(android.util.Log.DEBUG, "forceReconcileClientToken: skipped storage=null")
            return@withContext false
        }
        // Remove potentially revoked/stale token first so the server issues a fresh one.
        storage.clearToken(origin, username)
        try {
            val result = RommSyncApi.acquireClientToken(
                client = client,
                origin = origin,
                scopes = CLIENT_TOKEN_SCOPES,
            )
            when (result) {
                is ClientTokenAcquireResult.Success -> {
                    storage.setToken(origin, username, result.info.token)
                    diagLog(android.util.Log.DEBUG, "forceReconcileClientToken: reconciled=true")
                    true
                }
                is ClientTokenAcquireResult.Failure -> {
                    val httpCode = result.httpCode ?: -1
                    diagLog(android.util.Log.WARN, "forceReconcileClientToken: reconciled=false error=${result.error.name} httpCode=$httpCode")
                    false
                }
            }
        } catch (_: Exception) {
            // Acquisition failure; caller will handle as terminal auth-expired.
            diagLog(android.util.Log.WARN, "forceReconcileClientToken: exception during reconciliation")
            false
        }
    }

    /**
     * Ensures a durable ClientToken exists for the current session scope after a successful
     * verification. If one is already stored, this is a no-op. Only acquires when missing.
     */
    suspend fun ensureClientTokenExists(origin: String, username: String): Boolean {
        val storage = clientTokenStorage ?: run {
            diagLog(android.util.Log.DEBUG, "ensureClientTokenExists: skipped storage=null")
            return false
        }
        if (storage.getToken(origin, username) != null) {
            diagLog(android.util.Log.DEBUG, "ensureClientTokenExists: alreadyPresent=true")
            return true
        }
        val result = forceReconcileClientToken(origin, username)
        diagLog(android.util.Log.DEBUG, "ensureClientTokenExists: afterForceReconcile=$result")
        return result
    }

    /**
     * Clears both the [SessionStore] record and the matching durable ClientToken for this
     * scope. Called when authentication is definitively expired/invalid (not transient errors).
     */
    fun clearExpiredSession(origin: String, username: String) {
        diagLog(android.util.Log.DEBUG, "clearExpiredSession: clearing session+token")
        sessionStore.clear()
        clientTokenStorage?.clearToken(origin, username)
    }

    private fun recordIfSuccessful(origin: String, result: AuthFlowResult, forceReplaceToken: Boolean) {
        if (result is AuthFlowResult.Success) {
            val usernamePresent = result.verifiedUser.username != null
            diagLog(android.util.Log.DEBUG, "recordIfSuccessful: success=true usernamePresent=$usernamePresent")
            sessionStore.save(origin, result.verifiedUser.username)
            // Acquire durable ClientToken for worker execution while foreground client is authenticated.
            acquireAndPersistClientToken(origin, result.verifiedUser.username, forceReplaceToken)
        } else {
            val httpCode = (result as? AuthFlowResult.Failure)?.httpCode ?: -1
            diagLog(android.util.Log.WARN, "recordIfSuccessful: success=false httpCode=$httpCode")
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
            diagLog(android.util.Log.DEBUG, "acquireAndPersistClientToken: skipped username=null")
            return
        }
        val storage = clientTokenStorage ?: run {
            diagLog(android.util.Log.DEBUG, "acquireAndPersistClientToken: skipped storage=null")
            return
        }

        // On explicit login (forceReplace=true), always replace stale token.
        // On session verification, reuse existing valid token to avoid churn.
        val tokenAlreadyPresent = storage.getToken(origin, uname) != null
        if (!forceReplace && tokenAlreadyPresent) {
            diagLog(android.util.Log.DEBUG, "acquireAndPersistClientToken: reused existing forceReplace=$forceReplace")
            return
        }

        try {
            val result = RommSyncApi.acquireClientToken(
                client = client,
                origin = origin,
                scopes = CLIENT_TOKEN_SCOPES,
            )
            when (result) {
                is ClientTokenAcquireResult.Success -> {
                    storage.setToken(origin, uname, result.info.token)
                    diagLog(android.util.Log.DEBUG, "acquireAndPersistClientToken: acquired+persisted scopes=${CLIENT_TOKEN_SCOPES}")
                }
                is ClientTokenAcquireResult.Failure -> {
                    val httpCode = result.httpCode ?: -1
                    diagLog(android.util.Log.WARN, "acquireAndPersistClientToken: acquireFailed error=${result.error.name} httpCode=$httpCode")
                }
            }
        } catch (e: Exception) {
            // Acquisition failure is non-fatal; worker will handle missing token as AUTH_REQUIRED.
            diagLog(android.util.Log.WARN, "acquireAndPersistClientToken: exception ${e.javaClass.simpleName}")
        }
    }

    companion object {
        /**
         * Minimum scopes required for device registration, save upload via Bearer token, and
         * play-session reporting (needed for "Continue Playing").
         * RomM's `POST /api/client-tokens` requires exact scope strings; the generic
         * `assets`/`device` shorthand is rejected by the pinned backend contract.
         */
        private val CLIENT_TOKEN_SCOPES = listOf(
            "assets.read", "assets.write",
            "devices.read", "devices.write",
            "roms.user.read", "roms.user.write",
        )
    }
}

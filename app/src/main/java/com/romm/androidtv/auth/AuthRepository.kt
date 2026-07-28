package com.romm.androidtv.auth

import com.romm.androidtv.network.AuthFlowResult
import com.romm.androidtv.network.HeartbeatCallResult
import com.romm.androidtv.network.RomMCookieSync
import com.romm.androidtv.network.executeAuthFlow
import com.romm.androidtv.network.executeHeartbeat
import com.romm.androidtv.network.verifyExistingSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

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
 * independent of the WebView cookie jar.
 */
class AuthRepository(
    private val client: OkHttpClient,
    private val cookieSync: RomMCookieSync,
    private val sessionStore: SessionStore,
) {

    /** Executes the full login flow (POST /api/login, heartbeat, /api/users/me). */
    suspend fun login(origin: String, username: String, password: CharArray): AuthFlowResult {
        val result = withContext(Dispatchers.IO) {
            executeAuthFlow(client, origin, username, password)
        }
        recordIfSuccessful(origin, result)
        return result
    }

    /** Checks whether existing session cookies (already imported into OkHttp) are still valid. */
    suspend fun verifySession(origin: String): AuthFlowResult {
        val result = withContext(Dispatchers.IO) {
            verifyExistingSession(client, origin)
        }
        recordIfSuccessful(origin, result)
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

    private fun recordIfSuccessful(origin: String, result: AuthFlowResult) {
        if (result is AuthFlowResult.Success) {
            sessionStore.save(origin, result.verifiedUser.username)
        }
    }
}

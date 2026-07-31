package com.romm.androidtv.romm

import okhttp3.Interceptor
import okhttp3.Response

/** Stable tag for all auth-loop boundary diagnostics (logcat -s RommAuthDx). */
private const val TAG = "RommAuthDx"

/** Safe diagnostic logger: swallows unmocked android.util.Log in JVM unit tests. */
private fun diagLog(priority: Int, message: String) {
    try { android.util.Log.println(priority, TAG, message) } catch (_: Exception) { /* JVM test env */ }
}

/**
 * OkHttp interceptor that injects `Authorization: Bearer <token>` on every request.
 * Used exclusively by the worker's cookie-independent HTTP client — never imports
 * WebView cookies or session state into background execution.
 *
 * If the stored token is absent, requests are sent unauthenticated (the server
 * will respond 401, which the executor maps to [PendingOperationStatus.AUTH_REQUIRED]).
 */
class BearerAuthInterceptor(private val tokenProvider: () -> String?) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider()
        val tokenPresent = token != null
        diagLog(android.util.Log.DEBUG, "BearerAuthInterceptor: tokenPresent=$tokenPresent")
        val request = if (tokenPresent) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}

package com.romm.androidtv.romm

import com.romm.androidtv.network.RommOrigin
import com.romm.androidtv.network.ServerAddressResult
import okhttp3.Interceptor
import okhttp3.Response

/** Stable tag for all auth-loop boundary diagnostics (logcat -s RommAuthDx). */
private const val TAG = "RommAuthDx"

/** Safe diagnostic logger: swallows unmocked android.util.Log in JVM unit tests. */
private fun diagLog(priority: Int, message: String) {
    try { android.util.Log.println(priority, TAG, message) } catch (_: Exception) { /* JVM test env */ }
}

/**
 * OkHttp interceptor that injects `Authorization: Bearer <token>` ONLY for
 * requests whose URL is under the configured RomM origin (scheme + host +
 * effective port + base path all match, via [RommOrigin.containsUri]).
 *
 * Used exclusively by the foreground native client — never imports WebView
 * cookies or session state into background execution.
 *
 * Credential-leak guard: requests to any other host (e.g. third-party cover
 * image URLs) never receive the token. Any `Authorization` header already
 * present on such a request (e.g. carried over by a cross-origin redirect) is
 * STRIPPED so the credential is never forwarded cross-origin.
 */
class BearerAuthInterceptor(
    private val origin: ServerAddressResult.Valid,
    private val tokenProvider: () -> String?,
) : Interceptor {

    /** Resolved origin scope for the same-origin guard. */
    private val rommOrigin: RommOrigin =
        requireNotNull(RommOrigin.parse(origin.origin)) { "Invalid origin: ${origin.origin}" }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = tokenProvider()
        val tokenPresent = token != null
        // Only requests under the configured RomM origin carry the credential.
        val matchesOrigin = rommOrigin.containsUri(request.url.toUri())
        diagLog(
            android.util.Log.DEBUG,
            "BearerAuthInterceptor: tokenPresent=$tokenPresent matchesOrigin=$matchesOrigin",
        )

        val builder = request.newBuilder()
        if (matchesOrigin && tokenPresent) {
            // Same-origin native API request: attach the credential.
            builder.header("Authorization", "Bearer $token")
        } else {
            // Non-origin (e.g. third-party cover URL) or no token: strip any
            // credential a prior hop (redirect) may have carried.
            builder.removeHeader("Authorization")
        }
        return chain.proceed(builder.build())
    }

}

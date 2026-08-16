package com.romm.androidtv.romm

import com.romm.androidtv.network.RommLog
import com.romm.androidtv.network.RommOrigin
import com.romm.androidtv.network.ServerAddressResult
import okhttp3.Interceptor
import okhttp3.Response

/** Stable tag for all auth-loop boundary diagnostics (logcat -s RommAuthDx). */
private const val TAG = "RommAuthDx"

/** Safe diagnostic logger: routes to [RommLog], which no-ops when no sink is wired (JVM unit tests). */
private fun diagLog(priority: Int, message: String) {
    RommLog.log(priority, TAG, message)
}

/**
 * OkHttp interceptor that injects `Authorization: Bearer <token>` ONLY for
 * requests whose URL is under the configured RomM origin (scheme + host +
 * effective port + base path all match, via [RommOrigin.containsUri]).
 *
 * Used exclusively by the foreground native client — never imports WebView
 * cookies or session state into background execution.
 *
 * The same-origin target is resolved PER REQUEST via [originProvider] rather
 * than pinned at construction, so a single client can safely serve a session
 * that reconnects to a different origin (e.g. switching from a demo/kiosk
 * server to one's own instance) without being rebuilt.
 *
 * An explicit same-origin `Authorization` header (such as Basic credentials
 * on `/api/login`) takes precedence over bearer injection. Requests to any
 * other host have authorization stripped so credentials cannot follow a
 * cross-origin redirect.
 */
class BearerAuthInterceptor(
    private val originProvider: () -> ServerAddressResult.Valid?,
    private val tokenProvider: () -> String?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = tokenProvider()
        val tokenPresent = token != null
        // Resolve the current same-origin target per request; null when there is
        // no valid profile origin (no credential should be attached).
        val currentOrigin = originProvider()?.let { RommOrigin.parse(it.origin) }
        // Only requests under the resolved RomM origin carry the credential.
        val matchesOrigin = currentOrigin != null && currentOrigin.containsUri(request.url.toUri())
        diagLog(
            RommLog.DEBUG,
            "BearerAuthInterceptor: tokenPresent=$tokenPresent matchesOrigin=$matchesOrigin",
        )

        val builder = request.newBuilder()
        if (!matchesOrigin) {
            builder.removeHeader("Authorization")
        } else if (request.header("Authorization") == null && tokenPresent) {
            // Same-origin API request without explicit credentials: attach the durable token.
            builder.header("Authorization", "Bearer $token")
        }
        return chain.proceed(builder.build())
    }

}

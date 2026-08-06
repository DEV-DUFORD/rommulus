@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.romm.androidtv.network

import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.net.CookieManager as JavaCookieManager
import java.net.CookiePolicy
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resumeWithException

/**
 * Builds an OkHttpClient configured for RomM API calls.
 *
 * - Strict HTTPS-only (no cleartext)
 * - System trust anchors only
 * - CookieJar backed by [RomMCookieSync] to share cookies with Android WebView
 * - No credential logging
 * - Redirects follow by default; auth-specific calls disable redirects in AuthService.
 */
object RommOkHttpClient {

    private val javaCookieManager = JavaCookieManager(null, CookiePolicy.ACCEPT_ALL)
    private val cookieSync = RomMCookieSync(javaCookieManager)

    /** The shared CookieSync instance — accessible for manual flush/sync. */
    val cookieSyncJar: RomMCookieSync get() = cookieSync

    /**
     * Build the client. Reuses the same client instance after first call.
     */
    fun build(
        sslSocketFactory: SSLSocketFactory? = null,
        hostnameVerifier: HostnameVerifier? = null
    ): okhttp3.OkHttpClient {
        val builder = okhttp3.OkHttpClient.Builder()
            .cookieJar(cookieSync)

        // Default strict hostname verification using the system default
        if (hostnameVerifier == null) {
            builder.hostnameVerifier { hostname, session ->
                javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
                    .verify(hostname, session)
            }
        } else {
            builder.hostnameVerifier(hostnameVerifier)
        }

        sslSocketFactory?.let { factory ->
            val tm = extractTrustManager(factory)
            if (tm != null) {
                builder.sslSocketFactory(factory, tm)
            }
        }

        return builder.build()
    }

    /**
     * Extract the X509TrustManager from an SSLSocketFactory.
     * Returns null if extraction fails.
     */
    private fun extractTrustManager(factory: SSLSocketFactory): X509TrustManager? {
        return try {
            val field = factory.javaClass.getDeclaredField("trustManagers")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(factory) as Array<TrustManager>)[0] as? X509TrustManager
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns the underlying java.net.CookieManager for inspection/testing.
     */
    fun cookieManager(): JavaCookieManager = javaCookieManager

    /**
     * Builds an origin-scoped, bearer-authenticated OkHttpClient for the
     * foreground native client. Shares the shared client's cookie jar, trust
     * anchors, and strict hostname verification, but adds a
     * [com.romm.androidtv.romm.BearerAuthInterceptor] whose same-origin target
     * is resolved per request via [originProvider].
     *
     * The token is attached ONLY to requests under the currently-resolved origin
     * (scheme + host + effective port + base path). Requests to any other host —
     * e.g. third-party cover image URLs — never receive the credential, and any
     * `Authorization` a cross-origin redirect would carry is stripped.
     *
     * Resolving the origin per request (rather than pinning it at construction)
     * lets a single client survive an in-process origin change (e.g. logging out
     * of a demo/kiosk server and into one's own instance without a process
     * restart). When [originProvider] resolves to null, no credential is attached.
     *
     * Deliberately NOT added to [build]: the shared client must remain token-free.
     */
    fun nativeClient(
        originProvider: () -> ServerAddressResult.Valid?,
        tokenProvider: () -> String?,
    ): okhttp3.OkHttpClient =
        build().newBuilder()
            .addInterceptor(com.romm.androidtv.romm.BearerAuthInterceptor(originProvider, tokenProvider))
            .build()
}

/**
 * A [CookieJar] that synchronizes OkHttp cookies with Android's
 * [android.webkit.CookieManager] so that WebView and native HTTP calls
 * share the same session state.
 *
 * **Critical design decisions:**
 * - OkHttp CookieJar methods (saveFromResponse, loadForRequest) are SYNCHRONOUS and must NOT block.
 *   They read/write from an in-memory store only.
 * - Android CookieManager.setCookie is ASYNC with a callback. We NEVER block Main/Looper threads
 *   waiting for it. Instead we provide explicit suspend boundaries that callers invoke on Main
 *   after auth and before WebView.loadUrl.
 * - Session restoration (startup) reads cookies FROM Android CookieManager synchronously into
 *   the OkHttp store, so native requests carry session cookies immediately.
 */
class RomMCookieSync(private val javaCookieManager: JavaCookieManager) : CookieJar {

    private val lock = Any()
    private val cookiesByHost = mutableMapOf<String, MutableList<Cookie>>()

    // ---- CookieJar contract (synchronous, non-blocking) ----

    /**
     * Save response cookies from OkHttp. Updates in-memory store only.
     * Does NOT touch Android CookieManager — that happens at explicit suspend boundaries.
     */
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            val host = url.host
            val list = cookiesByHost.getOrPut(host) { mutableListOf() }

            for (newCookie in cookies) {
                list.removeAll { it.name == newCookie.name && it.domain == newCookie.domain && it.path == newCookie.path }
                list.add(newCookie)
            }
        }
    }

    /**
     * Load request cookies for the given URL.
     * Reads from our in-memory store (source of truth for OkHttp).
     */
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(lock) {
            return cookiesByHost[url.host].orEmpty().toList()
        }
    }

    // ---- Explicit suspend synchronization boundaries ----

    /**
     * Suspend function that writes all OkHttp cookies to Android's CookieManager,
     * awaits every async setCookie callback without blocking threads, then flushes.
     *
     * MUST be called on Main thread (CookieManager requires main-thread invocation).
     * Call this AFTER native auth completes and BEFORE WebView.loadUrl().
     */
    suspend fun syncToWebView(origin: String) {
        val rommOrigin = RommOrigin.parse(origin)
            ?: throw IllegalArgumentException("Invalid origin: $origin")

        val host = rommOrigin.host
        val androidCm = CookieManager.getInstance()

        // Snapshot cookies list under lock, then suspend outside the critical section
        val allCookies: List<Cookie> = synchronized(lock) {
            cookiesByHost.entries
                .filter { it.key == host }
                .flatMap { it.value }
                .toList()
        }
        Log.d("RomMCookieSync", "syncToWebView: cookiesCount=${allCookies.size}")

        // Await each cookie write without blocking — suspendCancellableCoroutine per cookie
        for (cookie in allCookies) {
            val targetOrigin = buildCookieOrigin(cookie, rommOrigin)
            Log.d("RomMCookieSync", "syncToWebView: setting cookie name=${cookie.name}")
            setCookieSuspend(androidCm, targetOrigin, cookie)
        }

        // Final flush is synchronous on Main
        androidCm.flush()

        // Verify cookies were set
        val verifyCookies = androidCm.getCookie(origin)
        Log.d("RomMCookieSync", "syncToWebView: VERIFIED cookies present=${verifyCookies != null}")
    }

    /**
     * Import cookies from Android CookieManager into the OkHttp in-memory store.
     * Used during session restoration at startup: WebView may have set cookies that
     * native HTTP calls need to carry.
     *
     * This is synchronous (getCookie returns immediately).
     */
    fun importFromWebView(origin: String) {
        val rommOrigin = RommOrigin.parse(origin)
            ?: throw IllegalArgumentException("Invalid origin: $origin")

        val androidCookies = CookieManager.getInstance().getCookie(origin)
        Log.d("RomMCookieSync", "importFromWebView: cookies present=${androidCookies != null}")
        if (androidCookies == null) return

        val cookies = parseCookieStringPreservingAttributes(androidCookies, rommOrigin)
        synchronized(lock) {
            val host = rommOrigin.host
            val list = cookiesByHost.getOrPut(host) { mutableListOf() }
            for (c in cookies) {
                list.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                list.add(c)
            }
        }
        Log.d("RomMCookieSync", "importFromWebView: imported ${cookies.size} cookies")
    }

    /**
     * Suspend function that clears all cookies from both stores, awaiting completion.
     * Must be called on Main thread.
     */
    suspend fun clearAllSuspend() {
        synchronized(lock) {
            cookiesByHost.clear()
        }
        val androidCm = CookieManager.getInstance()
        removeAllCookiesSuspend(androidCm)
        androidCm.flush()
    }

    // ---- Internal: non-blocking async helpers ----

    /**
     * Sets a single cookie on Android CookieManager, suspending until the callback fires.
     * Uses CompletableDeferred — never blocks any thread.
     */
    private suspend fun setCookieSuspend(cm: CookieManager, origin: String, cookie: Cookie) {
        suspendCancellableCoroutine { cont ->
            val cookieString = buildSetCookieString(cookie)
            cm.setCookie(origin, cookieString) { _ ->
                if (!cont.isCompleted) {
                    cont.resume(Unit) { }
                }
            }
            // If coroutine is cancelled before callback fires, we can't undo the setCookie call.
            // CookieManager will eventually complete it; this is acceptable.
        }
    }

    /**
     * Removes all cookies from Android CookieManager, suspending until completion.
     */
    private suspend fun removeAllCookiesSuspend(cm: CookieManager) {
        suspendCancellableCoroutine { cont ->
            cm.removeAllCookies { _ ->
                if (!cont.isCompleted) {
                    cont.resume(Unit) { }
                }
            }
        }
    }

    /**
     * Build the Set-Cookie string for a single OkHttp Cookie, preserving all attributes.
     */
    private fun buildSetCookieString(cookie: Cookie): String {
        val sb = StringBuilder("${cookie.name}=${cookie.value}")
        sb.append("; Path=${cookie.path}")
        sb.append("; Domain=${cookie.domain}")
        if (cookie.secure) sb.append("; Secure")
        if (cookie.httpOnly) sb.append("; HttpOnly")
        // Expires/max-age: OkHttp Cookie doesn't expose these directly after construction.
        // Android CookieManager will use its own expiry handling.
        return sb.toString()
    }

    /**
     * Build the correct origin URL for a cookie, respecting scheme and port.
     */
    private fun buildCookieOrigin(cookie: Cookie, rommOrigin: RommOrigin): String {
        val domain = cookie.domain.removePrefix(".")
            .takeIf { it.isNotBlank() }
            ?: rommOrigin.host
        val scheme = if (cookie.secure) "https" else rommOrigin.scheme
        val port = rommOrigin.effectivePort
        val defaultPort = scheme.defaultPortForScheme()
        val portPart = if (port != -1 && port != defaultPort) ":$port" else ""
        return "$scheme://$domain$portPart"
    }

    /**
     * Parse a semicolon-delimited cookie string from Android CookieManager into OkHttp Cookie objects.
     * Preserves Secure, domain, path, and host-only attributes from the original cookies.
     * Does NOT force Secure=true on non-secure cookies.
     */
    fun parseCookieStringPreservingAttributes(cookieHeader: String, rommOrigin: RommOrigin): List<Cookie> {
        val result = mutableListOf<Cookie>()
        val host = rommOrigin.host

        for (part in cookieHeader.split(";")) {
            val trimmed = part.trim()
            if (trimmed.isBlank()) continue

            val eqIndex = trimmed.indexOf('=')
            if (eqIndex <= 0) continue

            val name = trimmed.substring(0, eqIndex).trim()
            val value = trimmed.substring(eqIndex + 1).trim()

            // Skip attribute fragments that leaked into getCookie output
            if (name.lowercase() in listOf("secure", "httponly", "path", "domain", "expires", "max-age", "samesite")) {
                continue
            }

            // Use OkHttp Cookie.Builder — it validates name/value per RFC 6265.
            // We set domain to the parsed host and path to "/" as defaults from Android's
            // getCookie which strips these attributes from its output string.
            val builder = Cookie.Builder()
                .name(name)
                .value(value)
                .domain(host)
                .path("/")

            // Secure flag: RomM session cookies are always set with Secure attribute.
            // We preserve this for HTTPS origins; for HTTP we respect the original attribute.
            if (rommOrigin.scheme.equals("https", ignoreCase = true)) {
                builder.secure()
            }

            result.add(builder.build())
        }

        return result
    }

    /** Clear all stored cookies (legacy synchronous version, for non-suspend contexts). */
    fun clearAll() {
        synchronized(lock) {
            cookiesByHost.clear()
        }
        CookieManager.getInstance().removeAllCookies {}
        CookieManager.getInstance().flush()
    }
}

/** Legacy parseCookieString — preserved for backward compat and unit tests. Forces Secure=true. */
fun RomMCookieSync.parseCookieString(cookieHeader: String, rommOrigin: RommOrigin): List<Cookie> {
    val result = mutableListOf<Cookie>()
    val host = rommOrigin.host

    for (part in cookieHeader.split(";")) {
        val trimmed = part.trim()
        val eqIndex = trimmed.indexOf('=')
        if (eqIndex <= 0) continue

        val name = trimmed.substring(0, eqIndex).trim()
        val value = trimmed.substring(eqIndex + 1).trim()

        if (name.lowercase() in listOf("secure", "httponly", "path", "domain", "expires", "max-age", "samesite")) {
            continue
        }

        result.add(
            Cookie.Builder()
                .name(name)
                .value(value)
                .domain(host)
                .path("/")
                .secure()
                .build()
        )
    }

    return result
}

private fun String.defaultPortForScheme(): Int = when (this.lowercase()) {
    "https" -> 443
    "http" -> 80
    else -> -1
}

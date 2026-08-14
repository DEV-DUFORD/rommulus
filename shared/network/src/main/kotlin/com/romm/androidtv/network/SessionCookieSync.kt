package com.romm.androidtv.network

/**
 * Platform-neutral seam over the WebView cookie-sync boundary used by
 * [com.romm.androidtv.auth.AuthRepository].
 *
 * Production Android is backed by `RomMCookieSync` (inside `RommOkHttpClient`)
 * via the app-side `AndroidSessionCookieSync` adapter. A plain JVM supplies its
 * own implementation (or no-op). Behavior mirrors `RomMCookieSync`'s two methods
 * exactly so [com.romm.androidtv.auth.AuthRepository] sees no change.
 */
interface SessionCookieSync {

    /**
     * Writes the native OkHttp session's cookies into the platform cookie store.
     * MUST be called on the main thread. Mirrors `RomMCookieSync.syncToWebView`.
     */
    suspend fun syncToWebView(origin: String)

    /**
     * Imports cookies the platform store already holds into the native OkHttp
     * cookie store. Mirrors `RomMCookieSync.importFromWebView`.
     */
    fun importFromWebView(origin: String)
}

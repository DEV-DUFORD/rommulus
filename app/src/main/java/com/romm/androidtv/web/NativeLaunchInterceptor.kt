package com.romm.androidtv.web

import com.romm.androidtv.network.RommOrigin

/**
 * A strictly-parsed, same-origin native-launch candidate extracted from a
 * WebView navigation URL.
 *
 * Constructing this value does **not** grant permission to start native
 * playback. In this build, native launch stays disabled everywhere
 * (LIBRETRO_REFACTOR.md sections 4.3 and 6): [NativeLaunchInterceptor] only
 * recognizes the URL shape so a later phase can wire it up, and callers must
 * keep letting WebView handle the navigation normally until then.
 */
data class NativeLaunchCandidate(val romId: Long)

/**
 * Recognizes RomM's EmulatorJS launch URL, `/rom/{id}/ejs`, and extracts only
 * the numeric ROM ID — never any other part of the URL, and never anything
 * that is not exactly this shape under the configured origin.
 *
 * Deliberately strict: rejects extra path segments, non-numeric or signed/
 * leading-zero-looking ambiguous IDs, cross-origin URLs, and query/fragment
 * trickery beyond a well-formed suffix. When in doubt, this returns null and
 * the caller must fall back to normal WebView handling.
 */
object NativeLaunchInterceptor {

    private val ROM_EJS_PATH = Regex("^/rom/(0|[1-9][0-9]*)/ejs$")

    /**
     * Returns a [NativeLaunchCandidate] only if [urlString] is a same-origin,
     * exact `/rom/{id}/ejs` navigation under [origin]. Returns null for
     * anything else, including malformed URLs, cross-origin URLs, or paths
     * that merely start with the expected prefix.
     */
    fun parse(urlString: String, origin: RommOrigin): NativeLaunchCandidate? {
        val uri = RommOrigin.parseUrl(urlString) ?: return null
        if (!origin.containsUri(uri)) return null
        // Reject any query string or fragment rather than silently ignoring it.
        if (!uri.rawQuery.isNullOrEmpty() || !uri.rawFragment.isNullOrEmpty()) return null

        val basePath = origin.path
        val fullPath = uri.path ?: return null
        if (!fullPath.startsWith(basePath)) return null

        val relativePath = fullPath.substring(basePath.length)
        val match = ROM_EJS_PATH.matchEntire(relativePath) ?: return null

        val romId = match.groupValues[1].toLongOrNull() ?: return null
        if (romId <= 0) return null

        return NativeLaunchCandidate(romId)
    }
}

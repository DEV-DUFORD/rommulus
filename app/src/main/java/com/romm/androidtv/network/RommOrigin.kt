package com.romm.androidtv.network

import java.net.URI

/**
 * Parsed representation of a RomM origin URL for deterministic same-origin checks.
 *
 * Uses java.net.URI to parse scheme, host, and effective port so that:
 * - https://example.com == https://example.com:443
 * - case-insensitive host comparison (per RFC 3986)
 * - exact path matching for /login detection (no substring spoofing)
 */
data class RommOrigin(
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String
) {
    /** The effective port: 443 for https, 80 for http, or explicit. */
    val effectivePort: Int
        get() = if (port == -1) scheme.defaultPort() else port

    /** Reconstructs the origin URL without trailing slash. */
    fun toUrl(): String {
        val ep = effectivePort
        val portPart = if (ep != scheme.defaultPort()) ":$ep" else ""
        return "$scheme://$host$portPart$path"
    }

    /**
     * Checks whether [other] is the same origin (same scheme, host, effective port).
     */
    fun isSameOrigin(other: RommOrigin): Boolean {
        return scheme.equals(other.scheme, ignoreCase = true) &&
                host.equals(other.host, ignoreCase = true) &&
                effectivePort == other.effectivePort
    }

    /**
     * Checks whether [uri] represents a URL under this origin.
     * The URI must have the same scheme/host/effective-port AND its path
     * must start with our base path (or be exactly it).
     */
    fun containsUri(uri: URI): Boolean {
        if (!uri.scheme.equals(scheme, ignoreCase = true)) return false
        val otherHost = uri.host
        if (otherHost == null || !otherHost.equals(host, ignoreCase = true)) return false
        val otherPort = uri.port.takeIf { it >= 0 } ?: uri.scheme?.defaultPort() ?: -1
        val ourPort = effectivePort
        if (ourPort != otherPort) return false
        // Path must start with our path or be exactly our path + "/"
        val otherPath = uri.path ?: ""
        if (path.isEmpty()) return true
        if (!otherPath.startsWith(path)) return false
        val remainder = otherPath.substring(path.length)
        return remainder.isEmpty() || remainder.startsWith("/")
    }

    /**
     * Checks whether [uri] navigates exactly to the /login path under this origin.
     * Handles query strings and fragments — they are ignored for path matching.
     * Rejects spoofed paths like /loginpage, /api/login, /admin/login.
     */
    fun isLoginPath(uri: URI): Boolean {
        if (!containsUri(uri)) return false
        val otherPath = uri.path ?: "/"
        // Exact match: path must be "/login" or base+"/login"
        val expectedPath = if (path.endsWith("/")) "$path/login" else "$path/login"
        return otherPath == expectedPath || otherPath == "/login"
    }

    companion object {
        /**
         * Parses a RomM origin URL. Normalizes trailing slashes from path.
         * Returns null for invalid URIs.
         *
         * Delegates to [RommServerAddress.parseStructure] so parsing agrees with
         * the shared canonicalizer on scheme/host/port/base-path (including IPv6
         * literals and IDN). Structural only — private-LAN HTTP enforcement is a
         * separate concern handled by [RommServerAddress.parseAndNormalize] at the
         * configuration/request boundary, so same-origin/cookie checks here accept
         * any valid http/https origin.
         */
        fun parse(origin: String): RommOrigin? {
            return when (val result = RommServerAddress.parseStructure(origin)) {
                is ServerAddressResult.Invalid -> null
                is ServerAddressResult.Valid -> RommOrigin(
                    scheme = result.scheme,
                    host = result.host,
                    port = result.port ?: -1,
                    path = result.basePath,
                )
            }
        }

        /**
         * Parses any URL for origin/path inspection. Returns null for invalid URIs.
         */
        fun parseUrl(urlString: String): URI? {
            return try {
                URI(urlString)
            } catch (_: Exception) {
                null
            }
        }
    }
}

private fun String.defaultPort(): Int = when (this.lowercase()) {
    "https" -> 443
    "http" -> 80
    else -> -1
}

package com.romm.androidtv.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress

/**
 * Result of parsing a RomM server address.
 *
 * Only a [Valid] result carries a usable [Valid.origin]; an [Invalid] result can
 * never be turned into a network call (see [RommServerAddress.toHttpUrl]).
 */
sealed interface ServerAddressResult {
    data class Valid(
        /** Single canonical origin string: `scheme://host[:port]/basePath`. */
        val origin: String,
        val scheme: String,
        val host: String,
        /** Explicit non-default port, or null when it is the scheme default (omitted). */
        val port: Int?,
        /** Base path without redundant trailing slash(es); empty when none. */
        val basePath: String,
    ) : ServerAddressResult

    data class Invalid(val reason: InvalidReason) : ServerAddressResult
}

enum class InvalidReason {
    BLANK,
    MISSING_SCHEME,
    UNSUPPORTED_SCHEME,
    MISSING_HOST,
    INVALID_PORT,
    CREDENTIALS_PRESENT,
    QUERY_OR_FRAGMENT,
    INVALID_FORMAT,
    /** HTTP to a non-private (public) host, which is never allowed. */
    INSECURE_PUBLIC_HTTP,
}

/** Security classification of a parsed, valid address. */
enum class AddressClassification {
    /** HTTPS (any valid host) or HTTP to a loopback address. */
    SECURE,
    /** HTTP to a private-LAN host (drives the amber warning). */
    PRIVATE_LAN_HTTP,
}

/**
 * Shared, pure canonicalizer for RomM server addresses.
 *
 * This is the single source of truth for the canonical origin string used by
 * Settings, SessionStore, heartbeat, login, error copy, and scope keys. It uses
 * OkHttp's [HttpUrl] as the parser so that every subsequent request agrees on
 * host/path/IDN normalization. Pure and JVM-testable (no Android framework
 * objects, no I/O).
 *
 * Policy: HTTPS is accepted for any valid host; HTTP is accepted only for
 * private-LAN hosts (loopback, RFC1918, link-local, ULA, `.local`). Public HTTP
 * is rejected as [InvalidReason.INSECURE_PUBLIC_HTTP]. HTTPS is never silently
 * downgraded to HTTP.
 */
object RommServerAddress {

    /**
     * Full entry point: structurally parses [input] and enforces the
     * private-LAN-only HTTP policy. Use this everywhere a server address is
     * configured or used to build network requests.
     */
    fun parseAndNormalize(input: String?): ServerAddressResult =
        when (val parsed = parseStructure(input)) {
            is ServerAddressResult.Invalid -> parsed
            is ServerAddressResult.Valid -> enforceHttpPolicy(parsed)
        }

    /**
     * Structural parse only (no private-LAN HTTP enforcement). Shared with
     * [RommOrigin], which performs same-origin/cookie checks on already-validated
     * origins and must accept public HTTP origins structurally.
     *
     * When the input has no explicit scheme, one is inferred: HTTPS for DNS
     * hostnames, HTTP for IP literals (matching browser address-bar behavior).
     */
    fun parseStructure(input: String?): ServerAddressResult {
        if (input == null) return ServerAddressResult.Invalid(InvalidReason.BLANK)
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ServerAddressResult.Invalid(InvalidReason.BLANK)

        // Scheme inference for bare inputs (no explicit http:// or https://).
        val withScheme = if (hasExplicitScheme(trimmed)) trimmed else inferScheme(trimmed) + trimmed

        // Parse the scheme (inferred if absent). Detect via the first "://".
        val schemeEnd = withScheme.indexOf("://")
        if (schemeEnd < 0) return ServerAddressResult.Invalid(InvalidReason.INVALID_FORMAT)
        val scheme = withScheme.substring(0, schemeEnd)
        val schemeLower = scheme.lowercase()
        if (schemeLower != "http" && schemeLower != "https") {
            return ServerAddressResult.Invalid(InvalidReason.UNSUPPORTED_SCHEME)
        }

        // Explicitly reject any control character anywhere in the URL.
        if (withScheme.any { it.isISOControl() }) {
            return ServerAddressResult.Invalid(InvalidReason.INVALID_FORMAT)
        }

        // HttpUrl rejects internal whitespace, out-of-range/invalid ports, and
        // malformed hosts (returns null).
        val url = withScheme.toHttpUrlOrNull()
            ?: return ServerAddressResult.Invalid(InvalidReason.INVALID_FORMAT)

        if (url.host.isEmpty()) return ServerAddressResult.Invalid(InvalidReason.MISSING_HOST)

        // Reject embedded credentials.
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return ServerAddressResult.Invalid(InvalidReason.CREDENTIALS_PRESENT)
        }
        // Reject query parameters and fragments.
        if (url.querySize > 0 || url.fragment != null) {
            return ServerAddressResult.Invalid(InvalidReason.QUERY_OR_FRAGMENT)
        }

        val defaultPort = if (schemeLower == "https") 443 else 80
        val canonicalPort = if (url.port == defaultPort) null else url.port
        val basePath = stripTrailingSlashes(url.encodedPath)

        return ServerAddressResult.Valid(
            origin = buildCanonicalOrigin(schemeLower, url.host, canonicalPort, basePath),
            scheme = schemeLower,
            host = url.host,
            port = canonicalPort,
            basePath = basePath,
        )
    }

    /**
     * Classifies a valid address as SECURE or PRIVATE_LAN_HTTP. Because public
     * HTTP is rejected during parsing, any valid HTTP address is private-LAN.
     */
    fun classify(valid: ServerAddressResult.Valid): AddressClassification =
        if (valid.scheme == "http") AddressClassification.PRIVATE_LAN_HTTP
        else AddressClassification.SECURE

    /** Convenience: true when [valid] is HTTP to a private-LAN host. */
    fun isPrivateLanHttp(valid: ServerAddressResult.Valid): Boolean = valid.scheme == "http"

    /**
     * THE ONLY path that yields a network-ready [HttpUrl] from a parsed address.
     * Takes a [ServerAddressResult.Valid] so a rejected origin cannot become a
     * network call.
     */
    fun toHttpUrl(valid: ServerAddressResult.Valid): HttpUrl =
        valid.origin.toHttpUrlOrNull() ?: error("Cannot build URL from origin: ${valid.origin}")

    private fun enforceHttpPolicy(valid: ServerAddressResult.Valid): ServerAddressResult {
        if (valid.scheme == "https") return valid
        return if (isPrivateLanHost(valid.host)) valid
        else ServerAddressResult.Invalid(InvalidReason.INSECURE_PUBLIC_HTTP)
    }

    private fun buildCanonicalOrigin(
        scheme: String,
        host: String,
        port: Int?,
        basePath: String,
    ): String {
        val portPart = port?.let { ":$it" } ?: ""
        // HttpUrl.host returns IPv6 literals without brackets; re-bracket them so
        // the canonical origin string is a valid URL.
        val hostPart = if (host.contains(':')) "[$host]" else host
        return "$scheme://$hostPart$portPart$basePath"
    }

    private fun stripTrailingSlashes(path: String): String {
        var end = path.length
        while (end > 0 && path[end - 1] == '/') end--
        return path.substring(0, end)
    }

    private fun isPrivateLanHost(host: String): Boolean = when {
        host.equals("localhost", ignoreCase = true) -> true
        host.endsWith(".local", ignoreCase = true) -> true
        host.contains(':') -> isPrivateIpv6(host)
        host.matches(IPV4_LITERAL_REGEX) -> isPrivateIpv4(host)
        else -> false
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val octets = host.split('.').map { it.toInt() }
        val a = octets[0]
        val b = octets[1]
        return a == 127 ||                                        // loopback 127/8
            a == 10 ||                                            // RFC1918 10/8
            (a == 172 && b in 16..31) ||                          // RFC1918 172.16/12
            (a == 192 && b == 168) ||                             // RFC1918 192.168/16
            (a == 169 && b == 254)                                // IPv4 link-local 169.254/16
    }

    private fun isPrivateIpv6(host: String): Boolean = try {
        val address = InetAddress.getByName(stripBrackets(host))
        val bytes = address.address
        if (bytes.size != 16) {
            false
        } else {
            val b0 = bytes[0].toInt() and 0xff
            val b1 = bytes[1].toInt() and 0xff
            val loopback = bytes.take(15).all { it.toInt() == 0 } && bytes[15].toInt() == 1
            val uniqueLocal = (b0 and 0xfe) == 0xfc                        // fc00::/7
            val linkLocal = b0 == 0xfe && (b1 and 0xc0) == 0x80            // fe80::/10
            loopback || uniqueLocal || linkLocal
        }
    } catch (_: Exception) {
        false
    }

    private fun stripBrackets(host: String): String =
        if (host.startsWith("[") && host.endsWith("]")) host.substring(1, host.length - 1) else host

    private val IPV4_LITERAL_REGEX = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")

    // ---- Scheme inference for bare inputs (no explicit ://) ----

    /** True when [input] already carries any scheme prefix (e.g. `http://`, `ftp://`). */
    private fun hasExplicitScheme(input: String): Boolean = input.indexOf("://") >= 0

    /**
     * Returns the scheme prefix (`http://` or `https://`) to prepend to a bare
     * input that lacks an explicit scheme. IP literals → `http://`, DNS names →
     * `https://`.
     */
    private fun inferScheme(input: String): String {
        val authority = extractAuthority(input)
        val host = extractHostFromAuthority(authority)
        return if (isIpLiteral(host)) "http://" else "https://"
    }

    /** Everything before the first `/`, `?`, or `#` in [input]. */
    private fun extractAuthority(input: String): String {
        val limit = minOf(
            input.indexOf('/', 0).takeIf { it >= 0 } ?: input.length,
            input.indexOf('?', 0).takeIf { it >= 0 } ?: input.length,
            input.indexOf('#', 0).takeIf { it >= 0 } ?: input.length,
        )
        return input.substring(0, limit)
    }

    /**
     * Extracts the host token from an authority string, stripping an optional
     * trailing `:port`. Handles bracketed IPv6 (`[::1]:8080`) correctly.
     */
    private fun extractHostFromAuthority(authority: String): String {
        if (authority.startsWith('[')) {
            val closeBracket = authority.indexOf(']')
            if (closeBracket < 0) return authority // malformed, let parser handle it
            return authority.substring(1, closeBracket)
        }
        val lastColon = authority.lastIndexOf(':')
        if (lastColon > 0) {
            val potentialPort = authority.substring(lastColon + 1)
            if (potentialPort.isNotEmpty() && potentialPort.all { it.isDigit() }) {
                return authority.substring(0, lastColon)
            }
        }
        return authority
    }

    /** True when [host] is an IP literal (IPv4 dotted-decimal or IPv6). */
    private fun isIpLiteral(host: String): Boolean {
        if (host.isEmpty()) return false
        if (host.matches(IPV4_LITERAL_REGEX)) return true
        if (host.contains(':')) return true
        return false
    }
}

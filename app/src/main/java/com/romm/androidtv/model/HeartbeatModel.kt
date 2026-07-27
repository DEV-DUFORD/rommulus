package com.romm.androidtv.model

/**
 * Parsed response from RomM's GET /api/heartbeat endpoint.
 *
 * Reflects RomM 5 capability decisions that affect the TV client:
 * - version: server version string
 * - setupComplete: whether initial setup wizard is finished
 * - userpassEnabled: username/password auth is active
 * - emulatorJsEnabled: EmulatorJS web emulation is available
 */
data class HeartbeatResponse(
    val version: String?,
    val setupComplete: Boolean,
    val userpassEnabled: Boolean,
    val emulatorJsEnabled: Boolean,
    val rawMessage: String? = null
) {
    /** Whether the server is reachable and returned a structurally valid heartbeat. */
    fun isReachable(): Boolean = version != null || setupComplete != false

    /** Whether we can proceed to login (setup done + userpass enabled). */
    fun canLogin(): Boolean = setupComplete && userpassEnabled

    /** Human-readable status summary for the TV screen. */
    fun statusSummary(): String {
        val parts = mutableListOf<String>()
        if (version != null) parts.add("v$version")
        if (!setupComplete) parts.add("setup incomplete")
        if (!userpassEnabled) parts.add("userpass disabled")
        if (!emulatorJsEnabled) parts.add("EmulatorJS off")
        return parts.joinToString(" | ")
    }
}

/**
 * Classified error from a heartbeat call.
 */
enum class HeartbeatError {
    /** Network unreachable, DNS failure, connection refused, timeout. */
    NETWORK_ERROR,

    /** Server responded but TLS handshake failed or certificate rejected. */
    TLS_ERROR,

    /** HTTP response was not 2xx (e.g. 403, 500). */
    HTTP_ERROR,

    /** Response body was not valid JSON or missing expected fields. */
    PARSE_ERROR,

    /** Origin URL is not configured. */
    ORIGIN_NOT_CONFIGURED
}

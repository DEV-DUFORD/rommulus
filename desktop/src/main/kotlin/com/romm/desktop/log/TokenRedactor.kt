/*
 * Copyright (c) 2025 Romm Android TV contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.romm.desktop.log

/**
 * Pure, side-effect-free secret redactor.
 *
 * All redaction rules are deterministic string transforms — no I/O, no logging, no threading.
 * This object is safe to use in any context (tests, handlers, filters, or external wrappers).
 *
 * Coverage (best-effort, not crypto-strict):
 *  - `Authorization: Bearer <value>` / `Authorization:Bearer <value>` → `Authorization: Bearer [REDACTED]`
 *  - Bare `Bearer <value>` anywhere → `Bearer [REDACTED]`
 *  - JWT-style three-segment base64url tokens (a.b.c) → `[REDACTED]`
 *  - Hex tokens ≥ [minHexTokenLength] chars → `[REDACTED]`
 *  - Cookie headers (`Cookie: <name>=<value>` — all pairs in the header redacted)
 *  - `password=<non-whitespace>` anywhere → `password=[REDACTED]`
 *
 * These rules are intentionally conservative — they accept slightly wider matches than strictly
 * required to defend against common accidental leaks (cURL dumps, OAuth callbacks, etc.).
 */
object TokenRedactor {

    /** Minimum hex token length to consider redaction (e.g. 32 = SHA-256). */
    const val MIN_HEX_TOKEN_LENGTH: Int = 32

    /** `Authorization: Bearer <token>` anywhere in the message. */
    private val HEADER_AUTHORIZATION_BEARER = Regex(
        """(?i)Authorization:\s*Bearer\s+(\S+)""",
    )

    /** Bare `Bearer <token>` (no Authorization: prefix) anywhere in the message. */
    private val BARE_BEARER = Regex(
        """(?i)\bBearer\s+(\S+)""",
    )

    /** Three base64url segments separated by `.` with reasonable payload on each side. */
    private val JWT_SEGMENT = Regex(
        """[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}""",
    )

    /** Hex (or base64url) tokens of length ≥ [MIN_HEX_TOKEN_LENGTH] not embedded in a URL path. */
    private val HEX_TOKEN = Regex(
        """(?:^|[^A-Za-z0-9/._-])([A-Fa-f0-9]{${MIN_HEX_TOKEN_LENGTH},})(?=$|[^A-Za-z0-9/._-])""",
    )

    /**
     * Full `Cookie:` header with any number of `name=value` pairs (≥ 3 char values).
     * Replaces the entire header block with `Cookie: [REDACTED]`.
     */
    private val COOKIE_HEADER = Regex(
        """(?i)Cookie:\s*([A-Za-z0-9_$%\-]+=[^;\s]{3,}(?:\s*;\s*[A-Za-z0-9_$%\-]+=[^;\s]{3,})*)""",
    )

    /** password=value anywhere in the message (case-insensitive). */
    private val PASSWORD_ASSIGNMENT = Regex(
        """(?i)password(?:64)?=\S+""",
    )

    /** Redact any message string, returning a new redacted copy. */
    fun redact(message: String): String {
        var out = message
        // Order matters: more specific patterns first to avoid partial matches by broader ones.
        out = HEADER_AUTHORIZATION_BEARER.replace(out) { "Authorization: Bearer [REDACTED]" }
        out = BARE_BEARER.replace(out) { "Bearer [REDACTED]" }
        out = JWT_SEGMENT.replace(out) { "[REDACTED]" }
        out = PASSWORD_ASSIGNMENT.replace(out) { "password=[REDACTED]" }
        out = COOKIE_HEADER.replace(out) { "Cookie: [REDACTED]" }
        out = HEX_TOKEN.replace(out) { m ->
            m.groupValues[0].let { raw ->
                val pad = raw.startsWith(" ")
                val replacement = "[REDACTED]"
                if (pad) " $replacement" else replacement
            }
        }
        return out
    }
}

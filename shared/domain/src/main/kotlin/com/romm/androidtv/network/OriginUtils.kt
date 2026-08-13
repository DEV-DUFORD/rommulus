package com.romm.androidtv.network

/**
 * Parses a full RomM origin URL into its host component for use as a scope key.
 * Called exactly once in domain orchestration; callers should not re-parse the same origin.
 *
 * @param origin Full origin URL (e.g. "https://example.com") or bare host.
 * @return The parsed host, or the original string if parsing fails.
 */
fun extractServerKey(origin: String): String =
    RommOrigin.parse(origin)?.host ?: origin

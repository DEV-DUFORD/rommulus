package com.romm.androidtv.gamepad

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit test for the probe one-shot callback/result policy.
 *
 * Validates the guard state machine in isolation: once probeGuardCompleted is
 * set to true, all subsequent "onPageFinished" callbacks are rejected regardless of
 * URL. This is the contract the instrumented probe relies on to prevent duplicate
 * page-load races.
 */
class GamepadProbeOneShotPolicyTest {

    /**
     * Simulates the guard state machine used in GamepadHttpsProbeTest.
     * Pure-Kotlin stand-in that can run as a unit test without any Android dependencies.
     */
    private class ProbeGuard(private val normalizer: (String?) -> String?) {
        var completed: Boolean = false
            private set

        fun markComplete() { completed = true }
        fun isCompleted(): Boolean = completed

        /**
         * Validate callback: returns true if the callback should be processed.
         * Mirrors the contract: skipped if already completed OR URL doesn't match expected.
         */
        fun shouldProcess(url: String, expected: String): Boolean {
            if (completed) return false
            return normalizer(url) == normalizer(expected)
        }
    }

    private fun normalizeUrlOrigin(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val parsed = java.net.URI(url)
            val scheme = parsed.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            val host = (parsed.host ?: "").lowercase()
            if (host.isEmpty()) return null
            val port = parsed.port
            if (port == -1 || (scheme == "http" && port == 80) || (scheme == "https" && port == 443)) {
                "$scheme://$host"
            } else {
                "$scheme://$host:$port"
            }
        } catch (_: Exception) {
            null
        }
    }

    private val urlNormalizer: (String?) -> String? = ::normalizeUrlOrigin

    // --- One-shot guard semantics ---

    @Test
    fun `guardAllowsFirstCallback`() {
        val guard = ProbeGuard(urlNormalizer)
        assertThat(guard.shouldProcess("https://romm.example.com", "https://romm.example.com"))
            .isTrue()
    }

    @Test
    fun `guardRejectsAfterCompletion`() {
        val guard = ProbeGuard(urlNormalizer)
        guard.markComplete()
        assertThat(guard.shouldProcess("https://romm.example.com", "https://romm.example.com"))
            .isFalse()
        assertThat(guard.shouldProcess("https://other.example.com", "https://other.example.com"))
            .isFalse()
    }

    @Test
    fun `guardRejectsDifferentOriginsAfterPartialProcessing`() {
        val guard = ProbeGuard(urlNormalizer)
        assertThat(guard.shouldProcess("https://a.example.com", "https://a.example.com"))
            .isTrue()
        guard.markComplete()
        assertThat(guard.shouldProcess("https://b.example.com", "https://b.example.com"))
            .isFalse()
    }

    // --- URL normalization (redirects/intermediate URLs) ---

    @Test
    fun `normalizationStripsDefaultPorts`() {
        assertThat(normalizeUrlOrigin("https://romm.example.com:443"))
            .isEqualTo("https://romm.example.com")
        assertThat(normalizeUrlOrigin("http://romm.example.com:80"))
            .isEqualTo("http://romm.example.com")
    }

    @Test
    fun `normalizationRetainsNonDefaultPorts`() {
        assertThat(normalizeUrlOrigin("https://romm.example.com:8443"))
            .isEqualTo("https://romm.example.com:8443")
    }

    @Test
    fun `normalizationLowercasesHost`() {
        assertThat(normalizeUrlOrigin("https://ROMM.EXAMPLE.COM"))
            .isEqualTo("https://romm.example.com")
    }

    @Test
    fun `normalizationIgnoresPathQueryFragment`() {
        assertThat(normalizeUrlOrigin("https://romm.example.com/path?key=val#frag"))
            .isEqualTo("https://romm.example.com")
    }

    @Test
    fun `normalizationRejectsNonHttpSchemes`() {
        assertThat(normalizeUrlOrigin("file:///etc/hosts")).isNull()
        assertThat(normalizeUrlOrigin("about:blank")).isNull()
        assertThat(normalizeUrlOrigin("")).isNull()
        assertThat(normalizeUrlOrigin(null)).isNull()
    }

    @Test
    fun `redirectHttpToHttpsIsRejectionByDesign`() {
        val guard = ProbeGuard(urlNormalizer)
        // NOTE: the probe does NOT perform scheme transformation (http→https).
        // During an HTTP→HTTPS redirect the intermediate http:// URL will NOT match
        // the final configured https:// origin — this is by design. The probe treats
        // the configured origin as the canonical final URL, so intermediate redirects
        // are correctly skipped rather than accidentally processed as the final page.
        assertThat(guard.shouldProcess("http://romm.example.com", "https://romm.example.com"))
            .isFalse()
    }

    @Test
    fun `redirectWithwwwToNonwwwIsNotMatchedExplicitly`() {
        val guard = ProbeGuard(urlNormalizer)
        // NOTE: normalization does NOT perform www→non-www transformation because
        // the host values differ literally. This is by design — the probe uses
        // the exact configured origin as the canonical URL, and redirects from
        // www.romm.example.com to romm.example.com will be rejected (correct).
        assertThat(guard.shouldProcess("https://www.romm.example.com", "https://romm.example.com"))
            .isFalse()
    }

    @Test
    fun `intermediateUrlSkippedWhenOriginDiffers`() {
        val guard = ProbeGuard(urlNormalizer)
        // Simulate a redirect chain: http→https. The intermediate http URL
        // is NOT the configured final https origin, so it should be skipped.
        assertThat(guard.shouldProcess("http://romm.example.com", "https://romm.example.com"))
            .isFalse()
    }
}

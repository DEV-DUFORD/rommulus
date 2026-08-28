package com.romm.androidtv.onboarding

import com.romm.androidtv.onboarding.OnboardingRoutingDecision.AppMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pure JVM tests for the shared initial-route decision (spec section 5.1).
 * Ported verbatim from the Android `OnboardingRoutingPolicyTest` when the decision
 * moved into `:shared:domain` (rule 16: tests travel with the code).
 */
@DisplayName("OnboardingRoutingDecision — initial route decision")
class OnboardingRoutingDecisionTest {

    private fun decide(
        recordOrigin: String? = "https://romm.example.com",
        recordUsername: String? = "zack",
        recordKioskMode: Boolean = false,
        profileOrigin: String? = "https://romm.example.com",
        hasMatchingToken: Boolean = true,
    ): AppMode = OnboardingRoutingDecision.decide(
        recordOrigin = recordOrigin,
        recordUsername = recordUsername,
        recordKioskMode = recordKioskMode,
        profileOrigin = profileOrigin,
        hasMatchingToken = hasMatchingToken,
    )

    @Test
    fun `no record - onboarding`() {
        assertThat(decide(recordOrigin = null)).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `coherent record and matching token - main`() {
        assertThat(decide()).isEqualTo(AppMode.MAIN)
    }

    @Test
    fun `coherent record but no token - onboarding`() {
        assertThat(decide(hasMatchingToken = false)).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `coherent kiosk record enters main even without a token`() {
        assertThat(decide(
            recordOrigin = "https://demo.romm.app",
            recordKioskMode = true,
            profileOrigin = "https://demo.romm.app",
            hasMatchingToken = false,
        )).isEqualTo(AppMode.MAIN)
    }

    @Test
    fun `kiosk record with mismatched origin - onboarding`() {
        assertThat(decide(
            recordOrigin = "https://demo.romm.app",
            recordKioskMode = true,
            profileOrigin = "https://romm.example.com",
        )).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `kiosk record with blank username - onboarding`() {
        assertThat(decide(
            recordOrigin = "https://demo.romm.app",
            recordUsername = "",
            recordKioskMode = true,
        )).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `blank username - onboarding`() {
        assertThat(decide(recordUsername = "")).isEqualTo(AppMode.ONBOARDING)
        assertThat(decide(recordUsername = null)).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `blank record origin - onboarding`() {
        assertThat(decide(recordOrigin = "  ")).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `origin mismatch different host - onboarding`() {
        assertThat(decide(
            recordOrigin = "https://other.example.com",
            profileOrigin = "https://romm.example.com",
        )).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `origin mismatch different scheme - onboarding`() {
        assertThat(decide(
            recordOrigin = "http://romm.example.com",
            profileOrigin = "https://romm.example.com",
        )).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `origin mismatch different base path - onboarding`() {
        assertThat(decide(
            recordOrigin = "https://romm.example.com/romm",
            profileOrigin = "https://romm.example.com/other",
        )).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `base path same-origin variants - main`() {
        assertThat(decide(
            recordOrigin = "https://romm.example.com/romm",
            profileOrigin = "https://romm.example.com/romm",
        )).isEqualTo(AppMode.MAIN)
    }

    @Test
    fun `trailing slash normalized to same base path - main`() {
        assertThat(decide(
            recordOrigin = "https://romm.example.com/romm",
            profileOrigin = "https://romm.example.com/romm/",
        )).isEqualTo(AppMode.MAIN)
    }

    @Test
    fun `default port omitted is same origin - main`() {
        assertThat(decide(
            recordOrigin = "https://romm.example.com:443",
            profileOrigin = "https://romm.example.com",
        )).isEqualTo(AppMode.MAIN)
    }

    @Test
    fun `unparseable record or profile origin - onboarding`() {
        assertThat(decide(
            recordOrigin = "not-a-valid-url",
            profileOrigin = "https://romm.example.com",
        )).isEqualTo(AppMode.ONBOARDING)

        assertThat(decide(
            recordOrigin = "https://romm.example.com",
            profileOrigin = "not-a-valid-url",
        )).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `null profile origin - onboarding`() {
        assertThat(decide(profileOrigin = null)).isEqualTo(AppMode.ONBOARDING)
    }
}

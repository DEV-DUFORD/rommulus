package com.romm.androidtv.onboarding

import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.onboarding.OnboardingRoutingPolicy.AppMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pure JVM tests for the initial-route algorithm (spec section 5.1).
 */
@DisplayName("OnboardingRoutingPolicy — initial route decision")
class OnboardingRoutingPolicyTest {

    private fun record(
        origin: String,
        username: String? = "zack",
        kioskMode: Boolean = false,
    ) = SessionStore.Record(
        origin = origin,
        username = username,
        verifiedAtEpochMillis = 123L,
        kioskMode = kioskMode,
    )

    private fun decide(
        record: SessionStore.Record? = null,
        profileOrigin: String? = "https://romm.example.com",
        hasMatchingToken: Boolean = true,
    ): AppMode = OnboardingRoutingPolicy.decide(record, profileOrigin, hasMatchingToken)

    @Test
    fun `no record - onboarding`() {
        assertThat(decide(record = null)).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `coherent record and matching token - main`() {
        assertThat(decide(record = record("https://romm.example.com"))).isEqualTo(AppMode.MAIN)
    }

    @Test
    fun `coherent record but no token - onboarding`() {
        assertThat(decide(record = record("https://romm.example.com"), hasMatchingToken = false))
            .isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `coherent kiosk record enters main even without a token`() {
        assertThat(decide(
            record = record("https://demo.romm.app", kioskMode = true),
            profileOrigin = "https://demo.romm.app",
            hasMatchingToken = false,
        )).isEqualTo(AppMode.MAIN)
    }

    @Test
    fun `kiosk record with mismatched origin - onboarding`() {
        assertThat(decide(
            record = record("https://demo.romm.app", kioskMode = true),
            profileOrigin = "https://romm.example.com",
        )).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `kiosk record with blank username - onboarding`() {
        assertThat(decide(
            record = record("https://demo.romm.app", username = "", kioskMode = true),
        )).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `blank username - onboarding`() {
        assertThat(decide(record = record("https://romm.example.com", username = "")))
            .isEqualTo(AppMode.ONBOARDING)
        assertThat(decide(record = record("https://romm.example.com", username = null)))
            .isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `blank record origin - onboarding`() {
        assertThat(decide(record = record(origin = "  "))).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `origin mismatch different host - onboarding`() {
        assertThat(decide(
            record = record("https://other.example.com"),
            profileOrigin = "https://romm.example.com",
        )).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `origin mismatch different scheme - onboarding`() {
        assertThat(decide(
            record = record("http://romm.example.com"),
            profileOrigin = "https://romm.example.com",
        )).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `origin mismatch different base path - onboarding`() {
        assertThat(decide(
            record = record("https://romm.example.com/romm"),
            profileOrigin = "https://romm.example.com/other",
        )).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `base path same-origin variants - main`() {
        assertThat(decide(
            record = record("https://romm.example.com/romm"),
            profileOrigin = "https://romm.example.com/romm",
        )).isEqualTo(AppMode.MAIN)
    }

    @Test
    fun `trailing slash normalized to same base path - main`() {
        assertThat(decide(
            record = record("https://romm.example.com/romm"),
            profileOrigin = "https://romm.example.com/romm/",
        )).isEqualTo(AppMode.MAIN)
    }

    @Test
    fun `default port omitted is same origin - main`() {
        assertThat(decide(
            record = record("https://romm.example.com:443"),
            profileOrigin = "https://romm.example.com",
        )).isEqualTo(AppMode.MAIN)
    }

    @Test
    fun `unparseable record or profile origin - onboarding`() {
        assertThat(decide(
            record = record("not-a-valid-url"),
            profileOrigin = "https://romm.example.com",
        )).isEqualTo(AppMode.ONBOARDING)

        assertThat(decide(
            record = record("https://romm.example.com"),
            profileOrigin = "not-a-valid-url",
        )).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `null profile origin - onboarding`() {
        assertThat(decide(record = record("https://romm.example.com"), profileOrigin = null))
            .isEqualTo(AppMode.ONBOARDING)
    }
}

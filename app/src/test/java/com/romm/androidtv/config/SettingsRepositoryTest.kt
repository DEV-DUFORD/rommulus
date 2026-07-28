package com.romm.androidtv.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SettingsRepositoryTest {

    @Test
    fun `currentProfile falls back to the compiled-in default when nothing is persisted`() {
        val repo = SettingsRepository(FakeSharedPreferences(), defaultOrigin = "https://build-default.example.com")

        assertThat(repo.currentProfile()).isEqualTo(ServerProfile(origin = "https://build-default.example.com"))
    }

    @Test
    fun `setOrigin persists an override that currentProfile then returns`() {
        val repo = SettingsRepository(FakeSharedPreferences(), defaultOrigin = "https://build-default.example.com")

        repo.setOrigin("https://override.example.com")

        assertThat(repo.currentProfile()).isEqualTo(ServerProfile(origin = "https://override.example.com"))
    }

    @Test
    fun `clearOverride reverts to the compiled-in default`() {
        val repo = SettingsRepository(FakeSharedPreferences(), defaultOrigin = "https://build-default.example.com")
        repo.setOrigin("https://override.example.com")

        repo.clearOverride()

        assertThat(repo.currentProfile()).isEqualTo(ServerProfile(origin = "https://build-default.example.com"))
    }

    @Test
    fun `setOrigin with a blank string marks the profile explicitly unconfigured`() {
        val repo = SettingsRepository(FakeSharedPreferences(), defaultOrigin = "https://build-default.example.com")

        repo.setOrigin("")

        assertThat(repo.currentProfile().isConfigured).isFalse()
    }

    @Test
    fun `an empty compiled-in default with no override is unconfigured`() {
        val repo = SettingsRepository(FakeSharedPreferences(), defaultOrigin = "")

        assertThat(repo.currentProfile()).isEqualTo(ServerProfile.UNCONFIGURED)
    }
}

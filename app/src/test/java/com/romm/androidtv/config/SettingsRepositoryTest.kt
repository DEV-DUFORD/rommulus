package com.romm.androidtv.config

import kotlinx.coroutines.runBlocking
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

    @Test
    fun `persistValidatedOrigin persists and reads back the canonical value`() {
        val repo = SettingsRepository(FakeSharedPreferences(), defaultOrigin = "https://build-default.example.com")

        val persisted = runBlocking { repo.persistValidatedOrigin("HTTPS://Romm.Example.com") }

        assertThat(persisted).isTrue()
        assertThat(repo.currentProfile().origin).isEqualTo("https://romm.example.com")
    }

    @Test
    fun `persistValidatedOrigin rejects public http origin`() {
        val repo = SettingsRepository(FakeSharedPreferences(), defaultOrigin = "https://build-default.example.com")

        val persisted = runBlocking { repo.persistValidatedOrigin("http://romm.example.com") }

        assertThat(persisted).isFalse()
        assertThat(repo.currentProfile().origin).isEqualTo("https://build-default.example.com")
    }

    @Test
    fun `persistValidatedOrigin rejects blank and malformed origins`() {
        val repo = SettingsRepository(FakeSharedPreferences(), defaultOrigin = "https://build-default.example.com")

        assertThat(runBlocking { repo.persistValidatedOrigin("") }).isFalse()
        assertThat(runBlocking { repo.persistValidatedOrigin("ftp://romm.example.com") }).isFalse()
        assertThat(repo.currentProfile().origin).isEqualTo("https://build-default.example.com")
    }

    @Test
    fun `persistValidatedOrigin preserves base path in canonical origin`() {
        val repo = SettingsRepository(FakeSharedPreferences(), defaultOrigin = "https://build-default.example.com")

        val persisted = runBlocking { repo.persistValidatedOrigin("https://romm.example.com/romm/") }

        assertThat(persisted).isTrue()
        assertThat(repo.currentProfile().origin).isEqualTo("https://romm.example.com/romm")
    }

    @Test
    fun `verifySha1OnLaunch defaults to false`() {
        val repo = SettingsRepository(FakeSharedPreferences(), defaultOrigin = "https://example.com")

        assertThat(repo.verifySha1OnLaunch()).isFalse()
    }

    @Test
    fun `setVerifySha1OnLaunch persists the toggle`() {
        val repo = SettingsRepository(FakeSharedPreferences(), defaultOrigin = "https://example.com")

        repo.setVerifySha1OnLaunch(true)
        assertThat(repo.verifySha1OnLaunch()).isTrue()

        repo.setVerifySha1OnLaunch(false)
        assertThat(repo.verifySha1OnLaunch()).isFalse()
    }

    @Test
    fun `scanlinesEnabled defaults to false when no value is persisted`() {
        val repo = SettingsRepository(FakeSharedPreferences(), defaultOrigin = "https://example.com")

        assertThat(repo.scanlinesEnabled()).isFalse()
    }

    @Test
    fun `scanlinesEnabled persists true and reads back from a second repository backed by the same fake prefs`() {
        val prefs = FakeSharedPreferences()
        val repo1 = SettingsRepository(prefs, defaultOrigin = "https://example.com")
        val repo2 = SettingsRepository(prefs, defaultOrigin = "https://example.com")

        repo1.setScanlinesEnabled(true)

        assertThat(repo1.scanlinesEnabled()).isTrue()
        assertThat(repo2.scanlinesEnabled()).isTrue()
    }

    @Test
    fun `scanlinesEnabled persists false after true`() {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs, defaultOrigin = "https://example.com")

        repo.setScanlinesEnabled(true)
        assertThat(repo.scanlinesEnabled()).isTrue()

        repo.setScanlinesEnabled(false)
        assertThat(repo.scanlinesEnabled()).isFalse()
    }

    @Test
    fun `setScanlinesEnabled reports commit success`() {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs, defaultOrigin = "https://example.com")

        val result = repo.setScanlinesEnabled(true)

        assertThat(result).isTrue()
    }

    @Test
    fun `setScanlinesEnabled returns false when commit fails`() {
        val prefs = FakeSharedPreferences()
        prefs.commitResult = false
        val repo = SettingsRepository(prefs, defaultOrigin = "https://example.com")

        val result = repo.setScanlinesEnabled(true)

        assertThat(result).isFalse()
        assertThat(repo.scanlinesEnabled()).isFalse()
    }

    @Test
    fun `on-screen game controls default on and persist globally`() {
        val prefs = FakeSharedPreferences()
        val repo1 = SettingsRepository(prefs, defaultOrigin = "https://example.com")
        val repo2 = SettingsRepository(prefs, defaultOrigin = "https://example.com")

        assertThat(repo1.onScreenGameControlsEnabled()).isTrue()

        repo1.setOnScreenGameControlsEnabled(false)

        assertThat(repo2.onScreenGameControlsEnabled()).isFalse()
    }

    @Test
    fun `sharpFilterEnabled defaults to false and persists synchronously`() {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs, defaultOrigin = "https://example.com")

        assertThat(repo.sharpFilterEnabled()).isFalse()
        assertThat(repo.setSharpFilterEnabled(true)).isTrue()
        assertThat(SettingsRepository(prefs, defaultOrigin = "https://example.com").sharpFilterEnabled()).isTrue()
    }
}

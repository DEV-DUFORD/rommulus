package com.romm.desktop

import com.romm.androidtv.auth.SessionStorage
import com.romm.androidtv.onboarding.OnboardingRoutingDecision.AppMode
import com.romm.androidtv.storage.AppPaths
import com.romm.androidtv.storage.TestAppPaths
import com.romm.androidtv.storage.fakes.InMemorySessionRecordStore
import com.romm.desktop.settings.DesktopSettingsAdapter
import com.romm.desktop.storage.DesktopSessionStorage
import com.romm.desktop.storage.secret.FakeSecretBackend
import com.romm.desktop.storage.settings.JsonSettingsStore
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@DisplayName("DesktopAppCoordinator — Phase 6 keystone")
class DesktopAppCoordinatorTest {

    private fun coordinator(paths: AppPaths) = DesktopAppCoordinator(
        paths = paths,
        secretBackend = FakeSecretBackend(),
        appVersion = "test",
        buildDefaultOrigin = "https://demo.romm.app",
    )

    private fun record(
        origin: String = "https://romm.example.com",
        username: String? = "zack",
        kioskMode: Boolean = false,
    ) = SessionStorage.Record(
        origin = origin,
        username = username,
        verifiedAtEpochMillis = 123L,
        kioskMode = kioskMode,
    )

    // ---------------------------------------------------------------- decideAppMode

    @Test
    fun `decideAppMode - no session - onboarding`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        assertThat(c.decideAppMode(null, "https://romm.example.com", true)).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `decideAppMode - coherent session matching origin plus token - main`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        assertThat(c.decideAppMode(record(), "https://romm.example.com", true)).isEqualTo(AppMode.MAIN)
    }

    @Test
    fun `decideAppMode - coherent session but no token - onboarding`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        assertThat(c.decideAppMode(record(), "https://romm.example.com", false)).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `decideAppMode - kiosk session no token - main`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        assertThat(c.decideAppMode(
            record(origin = "https://demo.romm.app", kioskMode = true),
            "https://demo.romm.app",
            false,
        )).isEqualTo(AppMode.MAIN)
    }

    @Test
    fun `decideAppMode - origin mismatch - onboarding`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        assertThat(c.decideAppMode(
            record(origin = "https://other.example.com"),
            "https://romm.example.com",
            true,
        )).isEqualTo(AppMode.ONBOARDING)
    }

    // ---------------------------------------------------------------- construction

    @Test
    fun `coordinator constructs with fake secret backend and temp paths`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        assertThat(c.database.schemaVersion).isGreaterThanOrEqualTo(1)
        assertThat(c.computeStartupAppMode()).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `appMode starts onboarding when no session`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        assertThat(c.appMode).isEqualTo(AppMode.ONBOARDING)
        assertThat(c.computeStartupAppMode()).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `coordinator can enter main mode and route to home`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        c.enterMainMode()
        assertThat(c.appMode).isEqualTo(AppMode.MAIN)
        assertThat(c.currentScreen).isEqualTo(Screen.HOME)
    }

    // ---------------------------------------------------------------- navigation

    @Test
    fun `back from game detail returns to its parent`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        c.appMode = AppMode.MAIN
        c.openGameDetail(romId = 7L, parent = Screen.PLATFORM_DETAIL)
        assertThat(c.currentScreen).isEqualTo(Screen.GAME_DETAIL)
        c.onBack()
        assertThat(c.currentScreen).isEqualTo(Screen.PLATFORM_DETAIL)
        assertThat(c.exitRequested).isFalse()
    }

    @Test
    fun `back from platform detail returns home`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        c.appMode = AppMode.MAIN
        c.openPlatformDetail(1L)
        c.onBack()
        assertThat(c.currentScreen).isEqualTo(Screen.HOME)
    }

    @Test
    fun `back from platform detail returns to platforms when opened there`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        c.appMode = AppMode.MAIN
        c.navigate(Screen.PLATFORMS)
        c.openPlatformDetail(1L)

        c.onBack()

        assertThat(c.currentScreen).isEqualTo(Screen.PLATFORMS)
    }

    @Test
    fun `back at home stays home without requesting exit`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        c.appMode = AppMode.MAIN
        c.currentScreen = Screen.HOME
        c.onBack()
        assertThat(c.currentScreen).isEqualTo(Screen.HOME)
        assertThat(c.exitRequested).isFalse()
    }

    // ---------------------------------------------------------------- settings adapter

    private fun adapter(dir: Path): DesktopSettingsAdapter {
        val store = JsonSettingsStore(dir.resolve("settings.json"))
        val session = DesktopSessionStorage(InMemorySessionRecordStore())
        return DesktopSettingsAdapter(store, session, buildDefaultOrigin = "https://demo.romm.app")
    }

    @Test
    fun `settings adapter origin defaults from buildDefaultOrigin`(@TempDir dir: Path) {
        assertThat(adapter(dir).currentProfile().origin).isEqualTo("https://demo.romm.app")
    }

    @Test
    fun `settings adapter persistValidatedOrigin writes through to the store`(@TempDir dir: Path) = runBlocking {
        val a = adapter(dir)
        assertThat(a.persistValidatedOrigin("https://romm.example.com")).isTrue()
        assertThat(a.currentProfile().origin).isEqualTo("https://romm.example.com")
        // Invalid origin is rejected and does not overwrite the stored value.
        assertThat(a.persistValidatedOrigin("not-a-valid-url")).isFalse()
        assertThat(a.currentProfile().origin).isEqualTo("https://romm.example.com")
    }

    @Test
    fun `settings adapter hideUnsupportedSystems round-trips`(@TempDir dir: Path) {
        val a = adapter(dir)
        assertThat(a.hideUnsupportedSystems()).isTrue() // default true
        a.setHideUnsupportedSystems(false)
        assertThat(a.hideUnsupportedSystems()).isFalse()
        a.setHideUnsupportedSystems(true)
        assertThat(a.hideUnsupportedSystems()).isTrue()
    }

    private fun Path.testRoot(): AppPaths = TestAppPaths(this)
}

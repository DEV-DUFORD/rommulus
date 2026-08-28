package com.romm.desktop

import com.romm.androidtv.storage.TestAppPaths
import com.romm.androidtv.storage.ports.SettingsKeys
import com.romm.desktop.storage.secret.FakeSecretBackend
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Coordinator-level tests for the save-status session-scope derivation
 * ([DesktopAppCoordinator.currentSaveSessionKeys]): the sanitized origin/username pair the
 * [com.romm.desktop.ui.screens.detail.SaveSyncStatusPresenter] queries, and the no-session cases
 * (blank origin, kiosk/anonymous record, mismatched origin) that must map to NoSave in the UI.
 */
@DisplayName("DesktopAppCoordinator — save-status session scope")
class SaveSessionKeysTest {

    private companion object {
        const val ORIGIN = "https://demo.romm.app"
        /** SavePathPolicy.sanitizeSegment maps each of the two '/' in "https://" to '_'. */
        const val SANITIZED_ORIGIN = "https:__demo.romm.app"
    }

    private fun coordinator(dir: Path, defaultOrigin: String = ORIGIN): DesktopAppCoordinator =
        DesktopAppCoordinator(
            paths = TestAppPaths(dir),
            secretBackend = FakeSecretBackend(),
            appVersion = "test",
            buildDefaultOrigin = defaultOrigin,
        )

    @Test
    fun `coherent session yields the sanitized origin and username keys`(@TempDir dir: Path) {
        val c = coordinator(dir)
        c.settingsStore.write(mapOf(SettingsKeys.ORIGIN to ORIGIN))
        check(c.sessionStorage.save(ORIGIN, "zack", 123L, kioskMode = false))

        assertThat(c.currentSaveSessionKeys()).isEqualTo(SANITIZED_ORIGIN to "zack")
    }

    @Test
    fun `blank origin yields no keys`(@TempDir dir: Path) {
        val c = coordinator(dir, defaultOrigin = "")

        assertThat(c.currentSaveSessionKeys()).isNull()
    }

    @Test
    fun `kiosk session (no username) yields no keys`(@TempDir dir: Path) {
        val c = coordinator(dir)
        c.settingsStore.write(mapOf(SettingsKeys.ORIGIN to ORIGIN))
        check(c.sessionStorage.save(ORIGIN, null, 123L, kioskMode = true))

        assertThat(c.currentSaveSessionKeys()).isNull()
    }

    @Test
    fun `session for a different origin than the profile yields no keys`(@TempDir dir: Path) {
        val c = coordinator(dir)
        c.settingsStore.write(mapOf(SettingsKeys.ORIGIN to "https://other.romm.app"))
        check(c.sessionStorage.save(ORIGIN, "zack", 123L, kioskMode = false))

        assertThat(c.currentSaveSessionKeys()).isNull()
    }

    @Test
    fun `no session record at all yields no keys`(@TempDir dir: Path) {
        val c = coordinator(dir)
        c.settingsStore.write(mapOf(SettingsKeys.ORIGIN to ORIGIN))

        assertThat(c.currentSaveSessionKeys()).isNull()
    }
}

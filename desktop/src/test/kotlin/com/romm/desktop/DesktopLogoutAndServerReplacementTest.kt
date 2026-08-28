package com.romm.desktop

import com.romm.androidtv.onboarding.OnboardingRoutingDecision.AppMode
import com.romm.androidtv.onboarding.OnboardingStep
import com.romm.androidtv.romm.ClientToken
import com.romm.androidtv.storage.AppPaths
import com.romm.androidtv.storage.TestAppPaths
import com.romm.androidtv.storage.ports.SettingsKeys
import com.romm.desktop.storage.secret.FakeSecretBackend
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Logout + server-replacement parity tests (Android audit follow-up): the desktop must expose
 * the same session-invalidation behavior as Android's `SettingsViewModel`
 * `clearSessionFn` + `onSessionInvalidated` pair —
 *
 *  - **Log Out**: clears the durable client token for the current session, clears the session
 *    record, and routes the app back to onboarding (no confirmation dialog, as on Android);
 *  - **Server replacement**: saving a changed origin (or restoring the default) invalidates the
 *    old origin's session + token and re-onboards with the NEW origin prefilled at the SERVER
 *    step — mirroring Android's `enterOnboarding(startStep = OnboardingStep.SERVER)` (Phase 5a,
 *    spec §5.3).
 */
@DisplayName("Desktop logout + server replacement (Android parity)")
class DesktopLogoutAndServerReplacementTest {

    private fun coordinator(paths: AppPaths) = DesktopAppCoordinator(
        paths = paths,
        secretBackend = FakeSecretBackend(),
        appVersion = "test",
        buildDefaultOrigin = "https://demo.romm.app",
    )

    /** Establishes a logged-in state: origin persisted, session record, durable client token. */
    private fun establishSession(c: DesktopAppCoordinator, origin: String, username: String = "zack") {
        c.settingsStore.write(mapOf(SettingsKeys.ORIGIN to origin))
        c.sessionStorage.save(origin, username, verifiedAtEpochMillis = 123L)
        c.clientTokenStorage.setToken(origin, username, ClientToken(raw = "tok-$username"))
    }

    /** Blocks until [condition] holds (10ms poll, [timeoutMs] deadline). */
    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(10)
        }
    }

    // ---------------------------------------------------------------- logout

    @Test
    fun `logout clears the client token, the session record, and routes to onboarding`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        establishSession(c, "https://romm.example.com")
        c.appMode = AppMode.MAIN

        c.logout()

        assertThat(c.clientTokenStorage.getToken("https://romm.example.com", "zack")).isNull()
        assertThat(c.sessionStorage.coherentRecord("https://romm.example.com")).isNull()
        assertThat(c.appMode).isEqualTo(AppMode.ONBOARDING)
        // Logout is NOT a server replacement: the origin profile itself is untouched.
        assertThat(c.settingsAdapter.currentProfile().origin).isEqualTo("https://romm.example.com")
    }

    @Test
    fun `logout re-onboards at the server step with the current origin prefilled`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        establishSession(c, "https://romm.example.com")
        c.appMode = AppMode.MAIN
        // A presenter built BEFORE the logout must NOT be the one the next onboarding entry uses
        // (Android builds a fresh OnboardingViewModel on every onboarding entry).
        val stale = c.onboardingPresenter()

        c.logout()

        val fresh = c.onboardingPresenter()
        assertThat(fresh).isNotSameAs(stale)
        val state = fresh.uiState.value
        assertThat(state.step).isEqualTo(OnboardingStep.SERVER)
        assertThat(state.serverInput).isEqualTo("https://romm.example.com")
    }

    @Test
    fun `logout with no active session is a safe no-op that still routes to onboarding`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        c.appMode = AppMode.MAIN

        c.logout()

        assertThat(c.appMode).isEqualTo(AppMode.ONBOARDING)
        assertThat(c.sessionStorage.coherentRecord(c.settingsAdapter.currentProfile().origin)).isNull()
    }

    // ---------------------------------------------------------------- server replacement

    @Test
    fun `saving a changed origin clears the old session and token and re-onboards at the new origin`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        establishSession(c, "https://old.example.com")
        c.appMode = AppMode.MAIN
        val stale = c.onboardingPresenter()

        val presenter = c.settingsPresenter()
        presenter.onOriginTextChanged("https://new.example.com")
        presenter.onSave()

        // onSave invalidates asynchronously on the coordinator scope.
        waitFor { c.appMode == AppMode.ONBOARDING }

        assertThat(c.settingsAdapter.currentProfile().origin).isEqualTo("https://new.example.com")
        // The OLD origin's durable token is gone (the crux of the parity gap).
        assertThat(c.clientTokenStorage.getToken("https://old.example.com", "zack")).isNull()
        // The session record is cleared (no coherent record for the new origin either).
        assertThat(c.sessionStorage.coherentRecord("https://new.example.com")).isNull()
        // The next onboarding entry is a FRESH presenter prefilled with the NEW origin at SERVER.
        val fresh = c.onboardingPresenter()
        assertThat(fresh).isNotSameAs(stale)
        val state = fresh.uiState.value
        assertThat(state.step).isEqualTo(OnboardingStep.SERVER)
        assertThat(state.serverInput).isEqualTo("https://new.example.com")
    }

    @Test
    fun `restoring the default origin invalidates the session when it differs`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        establishSession(c, "https://old.example.com")
        c.appMode = AppMode.MAIN

        val presenter = c.settingsPresenter()
        presenter.onRestoreDefault()

        waitFor { c.appMode == AppMode.ONBOARDING }

        assertThat(c.settingsAdapter.currentProfile().origin).isEqualTo("https://demo.romm.app")
        assertThat(c.clientTokenStorage.getToken("https://old.example.com", "zack")).isNull()
        assertThat(c.sessionStorage.coherentRecord("https://demo.romm.app")).isNull()
        val state = c.onboardingPresenter().uiState.value
        assertThat(state.step).isEqualTo(OnboardingStep.SERVER)
        assertThat(state.serverInput).isEqualTo("https://demo.romm.app")
    }

    @Test
    fun `saving an unchanged origin keeps the session and token`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        establishSession(c, "https://romm.example.com")
        c.appMode = AppMode.MAIN

        val presenter = c.settingsPresenter()
        presenter.onOriginTextChanged("https://romm.example.com")
        presenter.onSave()

        // No invalidation (synchronous "No changes" branch): session + token survive, app stays MAIN.
        assertThat(presenter.uiState.value.saveSuccessMessage).isEqualTo("No changes")
        assertThat(c.clientTokenStorage.getToken("https://romm.example.com", "zack")).isNotNull()
        assertThat(c.sessionStorage.coherentRecord("https://romm.example.com")).isNotNull()
        assertThat(c.appMode).isEqualTo(AppMode.MAIN)
    }
}

private fun Path.testRoot(): AppPaths = TestAppPaths(this)

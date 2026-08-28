package com.romm.desktop.ui.screens.onboarding

import com.romm.androidtv.onboarding.AsyncActionState
import com.romm.androidtv.onboarding.OnboardingLoginError
import com.romm.androidtv.onboarding.OnboardingPresenter
import com.romm.androidtv.onboarding.OnboardingServerError
import com.romm.androidtv.onboarding.OnboardingStep
import com.romm.androidtv.onboarding.OnboardingUiState
import com.romm.androidtv.storage.TestAppPaths
import com.romm.desktop.DesktopAppCoordinator
import com.romm.desktop.storage.secret.FakeSecretBackend
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Unit tests for the pure decision logic behind the desktop [OnboardingScreen] composable,
 * plus a wiring test proving the coordinator's pre-wired presenter is a stable singleton
 * seeded from the persisted settings profile. The composables themselves are UI-only and are
 * covered by integration tests in a later wave.
 */
@DisplayName("Desktop OnboardingScreen — pure logic + presenter wiring")
class OnboardingScreenTest {

    // ------------------------------------------------------------------ HTTP warning

    @Nested
    @DisplayName("isPrivateLanHttpWarning")
    inner class PrivateLanHttpWarning {

        @Test
        fun `private-lan http shows the warning`() {
            assertThat(isPrivateLanHttpWarning("http://192.168.1.10")).isTrue()
            assertThat(isPrivateLanHttpWarning("http://localhost:1337/romm")).isTrue()
            assertThat(isPrivateLanHttpWarning("http://10.0.0.5")).isTrue()
        }

        @Test
        fun `https never shows the warning`() {
            assertThat(isPrivateLanHttpWarning("https://romm.example.com")).isFalse()
            assertThat(isPrivateLanHttpWarning("https://192.168.1.10:8443")).isFalse()
        }

        @Test
        fun `invalid or public-http input shows no warning`() {
            // Public HTTP is rejected during parsing, so it never reaches the warning check.
            assertThat(isPrivateLanHttpWarning("http://public.example.com")).isFalse()
            assertThat(isPrivateLanHttpWarning("")).isFalse()
            assertThat(isPrivateLanHttpWarning("not a url")).isFalse()
        }
    }

    // ------------------------------------------------------------------ error mapping

    @Nested
    @DisplayName("serverErrorMessage")
    inner class ServerErrorMapping {

        private val allErrors = listOf(
            OnboardingServerError.InvalidAddress,
            OnboardingServerError.NotRomm,
            OnboardingServerError.SetupIncomplete,
            OnboardingServerError.UserpassDisabled,
            OnboardingServerError.InsecurePublicHttp,
            OnboardingServerError.PersistenceFailure,
        )

        @Test
        fun `every server error maps to a distinct non-blank message`() {
            val messages = allErrors.map(::serverErrorMessage)
            assertThat(messages).allSatisfy { assertThat(it).isNotBlank() }
            assertThat(messages.distinct()).hasSize(allErrors.size)
        }
    }

    @Nested
    @DisplayName("loginErrorMessage")
    inner class LoginErrorMapping {

        private val allErrors = listOf(
            OnboardingLoginError.InvalidCredentials,
            OnboardingLoginError.NetworkFailure,
            OnboardingLoginError.TlsFailure,
            OnboardingLoginError.ServerFailure,
            OnboardingLoginError.VerificationFailure,
            OnboardingLoginError.DeviceCredentialFailure,
            OnboardingLoginError.TokenLimitReached,
            OnboardingLoginError.RequiredFields,
        )

        @Test
        fun `every login error maps to a distinct non-blank message`() {
            val origin = "https://romm.example.com"
            val messages = allErrors.map { loginErrorMessage(it, origin) }
            assertThat(messages).allSatisfy { assertThat(it).isNotBlank() }
            assertThat(messages.distinct()).hasSize(allErrors.size)
        }

        @Test
        fun `origin-specific errors interpolate the origin`() {
            val origin = "https://romm.example.com"
            assertThat(loginErrorMessage(OnboardingLoginError.InvalidCredentials, origin)).contains(origin)
            assertThat(loginErrorMessage(OnboardingLoginError.NetworkFailure, origin)).contains(origin)
            assertThat(loginErrorMessage(OnboardingLoginError.TlsFailure, origin)).contains(origin)
        }

        @Test
        fun `origin-specific errors survive a null origin`() {
            // A null origin must not render "null" into the message.
            assertThat(loginErrorMessage(OnboardingLoginError.InvalidCredentials, null)).doesNotContain("null")
            assertThat(loginErrorMessage(OnboardingLoginError.NetworkFailure, null)).isNotBlank()
        }
    }

    // ------------------------------------------------------------------ user code

    @Nested
    @DisplayName("formatUserCode")
    inner class FormatUserCode {

        @Test
        fun `eight-char code is grouped as XXXX-XXXX`() {
            assertThat(formatUserCode("abcd1234")).isEqualTo("ABCD-1234")
        }

        @Test
        fun `QR image encodes the verification URL at the requested size`() {
            val image = createQrImageBitmap("https://romm.example.com/device?code=ABCD1234", 96)

            assertThat(image.width).isEqualTo(96)
            assertThat(image.height).isEqualTo(96)
        }

        @Test
        fun `existing dashes are normalized`() {
            assertThat(formatUserCode("ABCD-1234")).isEqualTo("ABCD-1234")
            assertThat(formatUserCode("a-b-c-d-1-2-3-4")).isEqualTo("ABCD-1234")
        }

        @Test
        fun `other lengths pass through upper-cased`() {
            assertThat(formatUserCode("abc")).isEqualTo("ABC")
            assertThat(formatUserCode("")).isEmpty()
        }
    }

    // ------------------------------------------------------------------ remediation button

    @Nested
    @DisplayName("showRemoveOldestDeviceButton")
    inner class RemoveOldestButtonVisibility {

        private fun state(
            step: OnboardingStep,
            loginError: OnboardingLoginError?,
        ) = OnboardingUiState(step = step, loginError = loginError)

        @Test
        fun `shown only on credentials with token-limit error`() {
            assertThat(
                showRemoveOldestDeviceButton(
                    state(OnboardingStep.CREDENTIALS, OnboardingLoginError.TokenLimitReached),
                ),
            ).isTrue()
        }

        @Test
        fun `hidden on other steps or other errors`() {
            assertThat(
                showRemoveOldestDeviceButton(
                    state(OnboardingStep.WELCOME, OnboardingLoginError.TokenLimitReached),
                ),
            ).isFalse()
            assertThat(
                showRemoveOldestDeviceButton(
                    state(OnboardingStep.CREDENTIALS, OnboardingLoginError.InvalidCredentials),
                ),
            ).isFalse()
            assertThat(
                showRemoveOldestDeviceButton(state(OnboardingStep.CREDENTIALS, null)),
            ).isFalse()
        }
    }

    // ------------------------------------------------------------------ presenter wiring

    @Nested
    @DisplayName("coordinator presenter wiring")
    inner class PresenterWiring {

        private fun coordinator(dir: Path): DesktopAppCoordinator = DesktopAppCoordinator(
            paths = TestAppPaths(dir),
            secretBackend = FakeSecretBackend(),
            appVersion = "test",
            buildDefaultOrigin = "https://demo.romm.app",
        )

        @Test
        fun `onboardingPresenter is a stable singleton seeded from settings`(@TempDir dir: Path) {
            val c = coordinator(dir)
            // Persist an origin first, then obtain the presenter: its initial server input must
            // reflect the persisted profile (proves the initialServerInput wiring).
            runBlocking {
                assertThat(c.settingsAdapter.persistValidatedOrigin("https://romm.example.com")).isTrue()
            }

            val presenter = c.onboardingPresenter()
            assertThat(c.onboardingPresenter()).isSameAs(presenter)

            val state = presenter.uiState.value
            assertThat(state.step).isEqualTo(OnboardingStep.WELCOME)
            assertThat(state.serverInput).isEqualTo("https://romm.example.com")
            assertThat(state.serverAction).isEqualTo(AsyncActionState.Idle)
            assertThat(state.loginAction).isEqualTo(AsyncActionState.Idle)
        }

        @Test
        fun `presenter starts at build default origin when nothing persisted`(@TempDir dir: Path) {
            val c = coordinator(dir)
            val state = c.onboardingPresenter().uiState.value
            assertThat(state.step).isEqualTo(OnboardingStep.WELCOME)
            assertThat(state.serverInput).isEqualTo("https://demo.romm.app")
        }

        @Test
        fun `presenter is an OnboardingPresenter driven by the shared state machine`(@TempDir dir: Path) {
            val c = coordinator(dir)
            val presenter: OnboardingPresenter = c.onboardingPresenter()
            // WELCOME → onContinue advances to SERVER (state-machine contract).
            presenter.onContinue()
            assertThat(presenter.uiState.value.step).isEqualTo(OnboardingStep.SERVER)
        }
    }
}

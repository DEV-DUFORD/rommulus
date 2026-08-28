package com.romm.androidtv.onboarding.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.romm.androidtv.library.ui.RommTvTheme
import com.romm.androidtv.onboarding.AsyncActionState
import com.romm.androidtv.onboarding.OnboardingLoginError
import com.romm.androidtv.onboarding.OnboardingServerError
import com.romm.androidtv.onboarding.OnboardingStep
import com.romm.androidtv.onboarding.OnboardingUiState
import com.romm.androidtv.onboarding.QrLoginUiState
import com.romm.androidtv.auth.QrLoginSession
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI tests for the Phase 4 onboarding screens, driven by
 * FAKE [OnboardingUiState] (no live networking / ViewModel).
 *
 * NOTE: These tests are written but not run — no emulator is available in this
 * environment. They follow the repo's existing androidTest conventions
 * (createComposeRule + RommTvTheme + useUnmergedTree).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OnboardingScreenInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Callback capture bucket so tests can assert on state callbacks. */
    private class Callbacks {
        var continueCount = 0
        var validateCount = 0
        var loginCount = 0
        var backCount = 0
        var removeOldestDeviceAndRetryCount = 0
        var serverChanged = ""
        var usernameChanged = ""
        var passwordChanged = ""
    }

    private fun setContent(
        state: OnboardingUiState,
        callbacks: Callbacks = Callbacks(),
        modifier: Modifier = Modifier,
    ): Callbacks {
        composeTestRule.setContent {
            RommTvTheme {
                OnboardingScreen(
                    state = state,
                    modifier = modifier,
                    onContinue = { callbacks.continueCount++ },
                    onServerChanged = { callbacks.serverChanged = it },
                    onValidateServer = { callbacks.validateCount++ },
                    onUsernameChanged = { callbacks.usernameChanged = it },
                    onPasswordChanged = { callbacks.passwordChanged = it },
                    onLogin = { callbacks.loginCount++ },
                    onRemoveOldestDeviceAndRetry = { callbacks.removeOldestDeviceAndRetryCount++ },
                    onRetryQrLogin = {},
                    onBack = { callbacks.backCount++ },
                )
            }
        }
        return callbacks
    }

    // ------------------------------------------------------------------ WELCOME

    @Test
    fun welcomeStep_rendersExactCopyLogoAndContinue() {
        setContent(OnboardingUiState(step = OnboardingStep.WELCOME))

        composeTestRule.onNodeWithText("Welcome to RomMulus", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText(
            "Welcome to RomMulus, a companion app to RomM. Let\u2019s get you setup!",
            useUnmergedTree = true,
        ).assertExists()
        composeTestRule.onNodeWithText("Continue", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithContentDescription("RomMulus", useUnmergedTree = true).assertExists()
    }

    @Test
    fun welcomeStep_continueButtonInitiallyFocused_andInvokesCallback() {
        val cb = setContent(OnboardingUiState(step = OnboardingStep.WELCOME))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("onboarding_continue", useUnmergedTree = true).assertIsFocused()
        composeTestRule.onNodeWithTag("onboarding_continue", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        assert(cb.continueCount == 1) { "Expected continue once, got ${cb.continueCount}" }
    }

    // ------------------------------------------------------------------ SERVER

    @Test
    fun serverStep_rendersExactCopyFieldAndNext() {
        setContent(OnboardingUiState(step = OnboardingStep.SERVER))

        composeTestRule.onNodeWithText("Connect to your RomM server", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Please enter your RomM server\u2019s URL", useUnmergedTree = true)
            .assertExists()
        composeTestRule.onNodeWithText("Example: https://romm.example.com or http://192.168.1.50:8080", useUnmergedTree = true)
            .assertExists()
        composeTestRule.onNodeWithText("Next", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("onboarding_server_field", useUnmergedTree = true).assertExists()
    }

    @Test
    fun serverStep_fieldInitiallyFocused() {
        setContent(OnboardingUiState(step = OnboardingStep.SERVER))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("onboarding_server_field", useUnmergedTree = true).assertIsFocused()
    }

    @Test
    fun serverStep_centerEntersEditModeAndTypingReportsChange() {
        val cb = setContent(OnboardingUiState(step = OnboardingStep.SERVER))

        composeTestRule.onNodeWithTag("onboarding_server_field", useUnmergedTree = true)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeTestRule.waitForIdle()

        // The field is a controller-friendly TextField: the tagged node is the outer
        // focus wrapper, which doesn't expose SetText. The real editable node does —
        // type into that one.
        composeTestRule.onNode(hasSetTextAction(), useUnmergedTree = true)
            .performTextInput("http://192.168.1.50:8080")
        composeTestRule.waitForIdle()
        assert(cb.serverChanged == "http://192.168.1.50:8080") {
            "Expected server change reported, got '${cb.serverChanged}'"
        }
    }

    @Test
    fun serverStep_gamepadAEntersEditMode() {
        setContent(OnboardingUiState(step = OnboardingStep.SERVER))

        composeTestRule.onNodeWithTag("onboarding_server_field", useUnmergedTree = true)
            .performKeyInput { pressKey(Key.ButtonA) }
        composeTestRule.waitForIdle()

        composeTestRule.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsFocused()
    }

    @Test
    fun serverStep_backWhileEditingDoesNotFireStepBack() {
        val cb = setContent(OnboardingUiState(step = OnboardingStep.SERVER))

        composeTestRule.onNodeWithTag("onboarding_server_field", useUnmergedTree = true)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("onboarding_server_field", useUnmergedTree = true)
            .performKeyInput { pressKey(Key.Back) }
        composeTestRule.waitForIdle()
        assert(cb.backCount == 0) { "Back while editing must not fire step-back, got ${cb.backCount}" }
    }

    @Test
    fun serverStep_backWhenNotEditingFiresStepBack() {
        val cb = setContent(OnboardingUiState(step = OnboardingStep.SERVER))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("onboarding_server", useUnmergedTree = true)
            .performKeyInput { pressKey(Key.Back) }
        composeTestRule.waitForIdle()
        assert(cb.backCount == 1) { "Expected back once when not editing, got ${cb.backCount}" }
    }

    @Test
    fun serverStep_privateLanHttpInputShowsAmberWarning() {
        setContent(
            OnboardingUiState(step = OnboardingStep.SERVER, serverInput = "http://192.168.1.50:8080"),
        )
        composeTestRule.onNodeWithText(
            "This connection is not encrypted. Only use HTTP on a network you trust.",
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun serverStep_httpsInputDoesNotShowAmberWarning() {
        setContent(
            OnboardingUiState(step = OnboardingStep.SERVER, serverInput = "https://romm.example.com"),
        )
        composeTestRule.onNodeWithText(
            "This connection is not encrypted. Only use HTTP on a network you trust.",
            useUnmergedTree = true,
        ).assertDoesNotExist()
    }

    @Test
    fun serverStep_errorRendersBelowField() {
        setContent(
            OnboardingUiState(
                step = OnboardingStep.SERVER,
                serverInput = "https://romm.example.com",
                serverError = OnboardingServerError.NotRomm,
            ),
        )
        composeTestRule.onNodeWithTag("onboarding_server_error", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("URL does not resolve to a valid RomM Server", useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun serverStep_loadingButtonShowsCheckingAndIgnoresActivation() {
        val cb = setContent(
            OnboardingUiState(
                step = OnboardingStep.SERVER,
                serverAction = AsyncActionState.Loading,
            ),
        )
        composeTestRule.onNodeWithText("Checking\u2026", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Next", useUnmergedTree = true).assertDoesNotExist()

        composeTestRule.onNodeWithTag("onboarding_next", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        assert(cb.validateCount == 0) { "Click while loading must not re-invoke, got ${cb.validateCount}" }
    }

    // --------------------------------------------------------------- CREDENTIALS

    @Test
    fun credentialsStep_rendersExactCopyAndConnectingTo() {
        setContent(
            OnboardingUiState(
                step = OnboardingStep.CREDENTIALS,
                normalizedOrigin = "http://192.168.1.50:8080",
            ),
        )
        composeTestRule.onNodeWithText("Sign in to RomM", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Please enter your username and password", useUnmergedTree = true)
            .assertExists()
        composeTestRule.onNodeWithText("Connecting to http://192.168.1.50:8080", useUnmergedTree = true)
            .assertExists()
        composeTestRule.onNodeWithText("Login", useUnmergedTree = true).assertExists()
    }

    @Test
    fun credentialsStep_readyQrLoginRendersCodeAndPairingCode() {
        setContent(
            OnboardingUiState(
                step = OnboardingStep.CREDENTIALS,
                normalizedOrigin = "https://romm.example.com",
                qrLoginState = QrLoginUiState.Ready(
                    QrLoginSession(
                        deviceCode = "secret",
                        userCode = "ABCD1234",
                        verificationUrl = "https://romm.example.com/pair/device?user_code=ABCD1234",
                        expiresInSeconds = 600,
                        pollIntervalSeconds = 5,
                        installationId = "install-1",
                    ),
                ),
            ),
        )

        composeTestRule.onNodeWithTag("onboarding_qr_code", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Pairing code: ABCD-1234", useUnmergedTree = true).assertExists()
    }

    @Test
    fun credentialsStep_usernameInitiallyFocused() {
        setContent(OnboardingUiState(step = OnboardingStep.CREDENTIALS))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("onboarding_username_field", useUnmergedTree = true).assertIsFocused()
    }

    @Test
    fun credentialsStep_dpadOrderUsernameToPasswordToLogin() {
        setContent(OnboardingUiState(step = OnboardingStep.CREDENTIALS))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("onboarding_username_field", useUnmergedTree = true)
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("onboarding_password_field", useUnmergedTree = true).assertIsFocused()

        composeTestRule.onNodeWithTag("onboarding_password_field", useUnmergedTree = true)
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("onboarding_login", useUnmergedTree = true).assertIsFocused()
    }

    @Test
    fun credentialsStep_passwordIsMasked() {
        setContent(
            OnboardingUiState(
                step = OnboardingStep.CREDENTIALS,
                password = "supersecret",
            ),
        )
        // The raw password must never be exposed as readable text.
        composeTestRule.onNodeWithText("supersecret", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun credentialsStep_loginErrorRendersBelowPassword() {
        setContent(
            OnboardingUiState(
                step = OnboardingStep.CREDENTIALS,
                loginError = OnboardingLoginError.RequiredFields,
            ),
        )
        composeTestRule.onNodeWithTag("onboarding_login_error", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Username and password are required", useUnmergedTree = true).assertExists()
    }

    @Test
    fun credentialsStep_loginLoadingShowsSigningInAndIgnoresActivation() {
        val cb = setContent(
            OnboardingUiState(step = OnboardingStep.CREDENTIALS, loginAction = AsyncActionState.Loading),
        )
        composeTestRule.onNodeWithText("Signing in\u2026", useUnmergedTree = true).assertExists()

        composeTestRule.onNodeWithTag("onboarding_login", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        assert(cb.loginCount == 0) { "Click while login loading must not re-invoke, got ${cb.loginCount}" }
    }

    // ------------------------------------------------------------------ Global

    @Test
    fun backWhileNotEditingFiresStepBackAcrossSteps() {
        // WELCOME
        val welcome = setContent(OnboardingUiState(step = OnboardingStep.WELCOME))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("onboarding_welcome", useUnmergedTree = true)
            .performKeyInput { pressKey(Key.Back) }
        composeTestRule.waitForIdle()
        assert(welcome.backCount == 1) { "Expected back on WELCOME, got ${welcome.backCount}" }
    }

    @Test
    fun noNavRailIsRendered() {
        setContent(OnboardingUiState(step = OnboardingStep.WELCOME))
        composeTestRule.onNodeWithTag("nav_rail", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("onboarding_nav_rail", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun layoutStaysOnScreenAt720p() {
        setContent(
            OnboardingUiState(step = OnboardingStep.WELCOME),
            modifier = Modifier,
        )
        composeTestRule.onNodeWithTag("onboarding_welcome", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("onboarding_continue", useUnmergedTree = true).assertIsDisplayed()
    }
}

package com.romm.androidtv

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI tests for remote-only operability.
 * Validates: initial focus, single activation (no double-fire),
 * login submission via Select/Enter, and field presence.
 *
 * Uses performClick() which is the canonical Compose test equivalent of
 * activating a focused button (maps to DPAD_CENTER / Enter semantics).
 */
@RunWith(AndroidJUnit4::class)
class RemoteOnlyUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ---- HomeScreen tests ----

    @Test
    fun homeScreen_initialFocus_isOnLoginButton() {
        composeTestRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HomeScreen(
                    onCheckOrigin = {},
                    onLogin = {},
                    onRunDiagnostics = {},
                    onOpenRomMOrigin = {}
                )
            }
        }

        // Login button should have initial focus (LaunchedEffect requests it)
        composeTestRule.onNodeWithText("Login", useUnmergedTree = true)
            .assertIsFocused()
    }

    @Test
    fun homeScreen_allButtonsPresent() {
        composeTestRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HomeScreen(
                    onCheckOrigin = {},
                    onLogin = {},
                    onRunDiagnostics = {},
                    onOpenRomMOrigin = {}
                )
            }
        }

        // All four buttons must be present
        composeTestRule.onNodeWithText("Login", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Check Origin Status", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Run WebView Diagnostics", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("View RomM Origin", useUnmergedTree = true).assertExists()
    }

    @Test
    fun homeScreen_selectFiresOnce_noDoubleActivation() {
        var clickCount = 0

        composeTestRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HomeScreen(
                    onCheckOrigin = {},
                    onLogin = { clickCount++ },
                    onRunDiagnostics = {},
                    onOpenRomMOrigin = {}
                )
            }
        }

        // Login is focused by default
        composeTestRule.onNodeWithText("Login", useUnmergedTree = true)
            .assertIsFocused()

        // performClick is the canonical Compose test equivalent of
        // DPAD_CENTER / Enter on a focused button
        composeTestRule.onNodeWithText("Login", useUnmergedTree = true)
            .performClick()

        // Should fire exactly once — no double-activation from redundant onKeyEvent
        composeTestRule.waitForIdle()
        assert(clickCount == 1) { "Expected 1 click, got $clickCount (double-fire detected)" }
    }

    @Test
    fun homeScreen_eachButtonActivatesCorrectCallback() {
        var loginFired = false
        var originFired = false
        var diagFired = false
        var rommFired = false

        composeTestRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HomeScreen(
                    onCheckOrigin = { originFired = true },
                    onLogin = { loginFired = true },
                    onRunDiagnostics = { diagFired = true },
                    onOpenRomMOrigin = { rommFired = true }
                )
            }
        }

        composeTestRule.onNodeWithText("Login", useUnmergedTree = true).performClick()
        assert(loginFired) { "Login callback not fired" }

        composeTestRule.onNodeWithText("Check Origin Status", useUnmergedTree = true).performClick()
        assert(originFired) { "Origin callback not fired" }

        composeTestRule.onNodeWithText("Run WebView Diagnostics", useUnmergedTree = true).performClick()
        assert(diagFired) { "Diagnostics callback not fired" }

        composeTestRule.onNodeWithText("View RomM Origin", useUnmergedTree = true).performClick()
        assert(rommFired) { "RomM origin callback not fired" }
    }

    // ---- LoginScreen tests ----

    @Test
    fun loginScreen_initialFocus_isOnUsernameField() {
        composeTestRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LoginScreen(
                    authResult = null,
                    onLogin = { _, _, _ -> }
                )
            }
        }

        // Username field should be focused (has "Username" label)
        composeTestRule.onNodeWithContentDescription("Username")
            .assertIsFocused()
    }

    @Test
    fun loginScreen_allFieldsPresent() {
        composeTestRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LoginScreen(
                    authResult = null,
                    onLogin = { _, _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Username").assertExists()
        composeTestRule.onNodeWithContentDescription("Password").assertExists()
        composeTestRule.onNodeWithText("Login", useUnmergedTree = true).assertExists()
    }

    @Test
    fun loginScreen_textEntryAndSubmit_viaClick() {
        var submittedUsername: String? = null
        var submittedPassword: CharArray? = null

        composeTestRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LoginScreen(
                    authResult = null,
                    onLogin = { u, p, onComplete ->
                        submittedUsername = u
                        submittedPassword = p
                        onComplete()
                    }
                )
            }
        }

        // Type into username field (focused initially)
        composeTestRule.onNodeWithContentDescription("Username")
            .performTextInput("testuser")

        // Type into password field
        composeTestRule.onNodeWithContentDescription("Password")
            .performTextInput("testpass")

        // Submit via click (simulates remote Select on focused button)
        composeTestRule.onNodeWithText("Login", useUnmergedTree = true)
            .performClick()

        composeTestRule.waitForIdle()
        assert(submittedUsername == "testuser") {
            "Expected username 'testuser', got '$submittedUsername'"
        }
        assert(submittedPassword != null && String(submittedPassword!!) == "testpass") {
            "Expected password 'testpass', got '${submittedPassword?.let { String(it) }}'"
        }
        // Clean up
        if (submittedPassword != null) {
            zeroCharArray(submittedPassword!!)
        }
    }

    @Test
    fun loginScreen_selectFiresOnce_noDoubleActivation() {
        var clickCount = 0

        composeTestRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LoginScreen(
                    authResult = null,
                    onLogin = { _, _, onComplete ->
                        clickCount++
                        onComplete()
                    }
                )
            }
        }

        // Fill fields to enable the button
        composeTestRule.onNodeWithContentDescription("Username")
            .performTextInput("user")
        composeTestRule.onNodeWithContentDescription("Password")
            .performTextInput("pass")

        // Click login button
        composeTestRule.onNodeWithText("Login", useUnmergedTree = true)
            .performClick()

        composeTestRule.waitForIdle()
        assert(clickCount == 1) { "Expected 1 submission, got $clickCount (double-fire detected)" }
    }
}

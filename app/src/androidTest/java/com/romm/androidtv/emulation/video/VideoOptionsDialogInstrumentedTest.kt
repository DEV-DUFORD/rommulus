package com.romm.androidtv.emulation.video

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.romm.androidtv.library.ui.RommTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI tests for [VideoOptionsDialog].
 *
 * Mounts the dialog directly under [RommTvTheme] with hoisted state so we can
 * assert toggle semantics, focus behaviour, persistence-error feedback, and
 * back-dismiss — all without a real emulation session.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class VideoOptionsDialogInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ------------------------------------------------------------------ helpers

    private fun pressSystemBack() {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
    }

    /** Locate the Scanlines toggle row by its content description (substring match). */
    private fun scanlinesNode() =
        composeTestRule.onNode(hasContentDescription("Scanlines", substring = true), useUnmergedTree = true)

    /**
     * Mount the dialog with controllable callback behaviour.
     *
     * @param commitShouldSucceed  when true the callback returns true (state updates);
     *                             when false it returns false (error is shown).
     * @param initialEnabled       the initial scanlines value.
     */
    private fun setContent(
        commitShouldSucceed: () -> Boolean = { true },
        initialEnabled: Boolean = false,
    ): StateBucket {
        val bucket = StateBucket()
        composeTestRule.setContent {
            RommTvTheme {
                val enabled = remember { mutableStateOf(initialEnabled) }
                val error = remember { mutableStateOf(false) }

                VideoOptionsDialog(
                    scanlinesEnabled = enabled.value,
                    persistenceError = error.value,
                    onScanlinesChanged = { requested ->
                        val ok = commitShouldSucceed()
                        if (ok) {
                            enabled.value = requested
                            error.value = false
                        } else {
                            error.value = true
                        }
                        bucket.lastRequested = requested
                        bucket.commitCount++
                        ok
                    },
                    onDismiss = { bucket.dismissed = true },
                )
            }
        }
        composeTestRule.waitForIdle()
        return bucket
    }

    private class StateBucket {
        var commitCount = 0
        var lastRequested: Boolean? = null
        var dismissed = false
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun switchRow_exactlyOneFocusableSwitch_withCheckedState() {
        setContent()

        val node = scanlinesNode()
        node.assertExists()
            .assertIsDisplayed()
            .assert(isToggleable())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
    }

    @Test
    fun initialFocus_landsOnScanlinesRow() {
        setContent()
        scanlinesNode().assertIsFocused()
    }

    @Test
    fun selectTogglesOffToOn() {
        val bucket = setContent(initialEnabled = false)
        scanlinesNode().performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, bucket.commitCount)
        assertEquals(true, bucket.lastRequested)
        // After callback returns true, state flips → contentDescription updates
        composeTestRule.onNodeWithContentDescription(
            "Scanlines - On",
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun selectTogglesOnToOff() {
        val bucket = setContent(initialEnabled = true)
        scanlinesNode().performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, bucket.commitCount)
        assertEquals(false, bucket.lastRequested)
        composeTestRule.onNodeWithContentDescription(
            "Scanlines - Off",
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun rejectedPersistence_leavesStateUnchanged_andShowsError() {
        val bucket = setContent(commitShouldSucceed = { false }, initialEnabled = false)
        scanlinesNode().performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, bucket.commitCount)
        assertEquals(true, bucket.lastRequested)
        // State did NOT change (callback returned false)
        scanlinesNode().assertExists()
        // Error text appears
        composeTestRule.onNodeWithText(
            "Couldn't save this video option. Try again.",
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun successfulRetry_clearsError() {
        val shouldSucceed = mutableStateOf(false)

        composeTestRule.setContent {
            RommTvTheme {
                val enabled = remember { mutableStateOf(false) }
                val error = remember { mutableStateOf(false) }

                VideoOptionsDialog(
                    scanlinesEnabled = enabled.value,
                    persistenceError = error.value,
                    onScanlinesChanged = { requested ->
                        val ok = shouldSucceed.value
                        if (ok) {
                            enabled.value = requested
                            error.value = false
                        } else {
                            error.value = true
                        }
                        ok
                    },
                    onDismiss = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        // First click: rejected → error appears
        scanlinesNode().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(
            "Couldn't save this video option. Try again.",
            useUnmergedTree = true,
        ).assertExists()

        // Retry: succeed → error disappears, state updates
        shouldSucceed.value = true
        scanlinesNode().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(
            "Couldn't save this video option. Try again.",
            useUnmergedTree = true,
        ).assertDoesNotExist()
    }

    @Test
    fun backInvokesOnDismiss() {
        val bucket = setContent()
        pressSystemBack()
        composeTestRule.waitForIdle()
        assertTrue(bucket.dismissed)
    }

    @Test
    fun switchSemantics_exposeRoleAndCheckedState() {
        setContent(initialEnabled = false)
        scanlinesNode().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
    }

    @Test
    fun noApplySaveDoneOrCancelButtonExists() {
        setContent()
        for (label in listOf("Apply", "Save", "Done", "Cancel")) {
            composeTestRule.onNodeWithText(label, useUnmergedTree = true)
                .assertDoesNotExist()
        }
    }
}

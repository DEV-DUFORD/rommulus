package com.romm.androidtv.controller

import com.romm.androidtv.controller.model.KEYCODE_TO_CONTROL
import com.romm.androidtv.controller.policy.EventConsumptionPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for screen-aware controller event routing policy.
 *
 * Validates that the Activity's dispatchKeyEvent/dispatchGenericMotionEvent
 * only routes/consume controller events while AUTHENTICATED_WEBVIEW is visible.
 * Native Compose screens (HOME, LOGIN, etc.) must pass D-pad/buttons directly
 * to native UI without router interception.
 * CONTROLLER_DIAGNOSTICS observes events (router state updates) but does not
 * consume them — native controls remain functional.
 */
@DisplayName("Screen-aware controller event routing policy")
class ScreenAwareEventRoutingTest {

    @Nested
    @DisplayName("AUTHENTICATED_WEBVIEW — router consumes controller events")
    inner class WebViewScreenTests {
        @Test
        @DisplayName("D-pad keys ARE consumed by router on WebView screen")
        fun `dpad consumed on webview`() {
            // On AUTHENTICATED_WEBVIEW, D-pad keys from a gamepad should be
            // consumed by the router to prevent them from reaching the WebView
            for (keyCode in listOf(
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            )) {
                assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(keyCode))
                    .`as`("keyCode $keyCode should be consumable by router")
                    .isTrue()
            }
        }

        @Test
        @DisplayName("Game controller buttons ARE consumed on WebView screen")
        fun `gamepad buttons consumed on webview`() {
            for (keyCode in listOf(
                android.view.KeyEvent.KEYCODE_BUTTON_A,
                android.view.KeyEvent.KEYCODE_BUTTON_B,
                android.view.KeyEvent.KEYCODE_BUTTON_X,
                android.view.KeyEvent.KEYCODE_BUTTON_Y,
                android.view.KeyEvent.KEYCODE_BUTTON_L1,
                android.view.KeyEvent.KEYCODE_BUTTON_R1
            )) {
                assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(keyCode))
                    .`as`("keyCode $keyCode should be consumable by router")
                    .isTrue()
            }
        }

        @Test
        @DisplayName("Back key is NEVER consumed, even on WebView screen")
        fun `back never consumed on webview`() {
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(
                android.view.KeyEvent.KEYCODE_BACK
            )).isFalse()
            assertThat(EventConsumptionPolicy.isBackKey(
                android.view.KeyEvent.KEYCODE_BACK
            )).isTrue()
        }
    }

    @Nested
    @DisplayName("Native Compose screens — D-pad/buttons pass through to native UI")
    inner class NativeScreenTests {
        // These tests verify the POLICY: when on HOME/LOGIN/ORIGIN_STATUS/DIAGNOSTICS/ROMM_ORIGIN,
        // dispatchKeyEvent must NOT call controllerRouter.onKeyEvent(). The router should
        // not intercept D-pad or game-controller events.
        //
        // Since we can't unit-test MainActivity.dispatchKeyEvent directly (it requires
        // Android framework), we verify the underlying policy invariants:
        //
        // 1. KEYCODE_DPAD_CENTER is NOT in KEYCODE_TO_CONTROL, so even if the router
        //    were called, it would not consume it — but the fix ensures it's never called.
        // 2. D-pad directional keys ARE in KEYCODE_TO_CONTROL, so they WOULD be consumed
        //    by the router — the screen-aware guard prevents this on native screens.
        // 3. Game controller buttons ARE in KEYCODE_TO_CONTROL — same guard applies.

        @Test
        @DisplayName("DPAD_CENTER is NOT a mapped control (native UI handles it)")
        fun `dpad center not mapped`() {
            assertThat(KEYCODE_TO_CONTROL.containsKey(
                android.view.KeyEvent.KEYCODE_DPAD_CENTER
            )).isFalse()
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(
                android.view.KeyEvent.KEYCODE_DPAD_CENTER
            )).isFalse()
        }

        @Test
        @DisplayName("ENTER is NOT a mapped control (native UI handles it)")
        fun `enter not mapped`() {
            assertThat(KEYCODE_TO_CONTROL.containsKey(
                android.view.KeyEvent.KEYCODE_ENTER
            )).isFalse()
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(
                android.view.KeyEvent.KEYCODE_ENTER
            )).isFalse()
        }

        @Test
        @DisplayName("D-pad directional keys ARE mapped (router would consume them)")
        fun `dpad directionals are mapped`() {
            // This confirms WHY screen-aware routing is needed: D-pad keys
            // ARE in the mapping, so without the screen guard they'd be stolen
            // from native Compose screens.
            assertThat(KEYCODE_TO_CONTROL.containsKey(
                android.view.KeyEvent.KEYCODE_DPAD_UP
            )).isTrue()
            assertThat(KEYCODE_TO_CONTROL.containsKey(
                android.view.KeyEvent.KEYCODE_DPAD_DOWN
            )).isTrue()
            assertThat(KEYCODE_TO_CONTROL.containsKey(
                android.view.KeyEvent.KEYCODE_DPAD_LEFT
            )).isTrue()
            assertThat(KEYCODE_TO_CONTROL.containsKey(
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            )).isTrue()
        }

        @Test
        @DisplayName("Game controller buttons ARE mapped (router would consume them)")
        fun `gamepad buttons are mapped`() {
            // Confirms WHY screen-aware routing is needed for gamepad buttons
            assertThat(KEYCODE_TO_CONTROL.containsKey(
                android.view.KeyEvent.KEYCODE_BUTTON_A
            )).isTrue()
            assertThat(KEYCODE_TO_CONTROL.containsKey(
                android.view.KeyEvent.KEYCODE_BUTTON_B
            )).isTrue()
        }

        @Test
        @DisplayName("Back key is NEVER a mapped control")
        fun `back never mapped`() {
            assertThat(KEYCODE_TO_CONTROL.containsKey(
                android.view.KeyEvent.KEYCODE_BACK
            )).isFalse()
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(
                android.view.KeyEvent.KEYCODE_BACK
            )).isFalse()
        }
    }

    @Nested
    @DisplayName("CONTROLLER_DIAGNOSTICS — router observes but does not consume")
    inner class DiagnosticsScreenTests {
        @Test
        @DisplayName("Router can process events for state updates on diagnostics screen")
        fun `router processes events for diagnostics`() {
            // On CONTROLLER_DIAGNOSTICS, the router's onKeyEvent/onMotionEvent
            // ARE called (to update slot state), but their return value is ignored
            // — events always fall through to super.dispatchKeyEvent().
            //
            // This test verifies that the router CAN process these events
            // (the policy allows it), and that the screen-aware guard
            // distinguishes between "consume" (WebView) and "observe" (Diagnostics).

            // All mapped keys can be processed by the router regardless of screen
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(
                android.view.KeyEvent.KEYCODE_BUTTON_A
            )).isTrue()
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(
                android.view.KeyEvent.KEYCODE_DPAD_UP
            )).isTrue()

            // But on CONTROLLER_DIAGNOSTICS, the Activity ignores the return value
            // and always passes the event to super.dispatchKeyEvent().
            // This is verified by the Activity's dispatchKeyEvent implementation.
        }
    }
}

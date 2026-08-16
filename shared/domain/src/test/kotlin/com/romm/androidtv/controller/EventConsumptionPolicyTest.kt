package com.romm.androidtv.controller

import com.romm.androidtv.controller.policy.EventConsumptionPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for EventConsumptionPolicy.
 * Uses raw platform key-code constants (Android KeyEvent values) without the
 * Android framework on the classpath.
 */
@DisplayName("EventConsumptionPolicy — key event routing decisions")
class EventConsumptionPolicyTest {

    private companion object {
        // Android KeyEvent key codes (platform constants).
        const val KEYCODE_BUTTON_A = 96
        const val KEYCODE_BUTTON_B = 97
        const val KEYCODE_BUTTON_X = 99
        const val KEYCODE_BUTTON_Y = 100
        const val KEYCODE_BUTTON_L1 = 102
        const val KEYCODE_BUTTON_R1 = 103
        const val KEYCODE_BUTTON_SELECT = 109
        const val KEYCODE_BUTTON_START = 108
        const val KEYCODE_BUTTON_THUMBL = 106
        const val KEYCODE_BUTTON_THUMBR = 107
        const val KEYCODE_DPAD_UP = 19
        const val KEYCODE_DPAD_DOWN = 20
        const val KEYCODE_DPAD_LEFT = 21
        const val KEYCODE_DPAD_RIGHT = 22
        const val KEYCODE_BACK = 4
        const val KEYCODE_HOME = 3
        const val KEYCODE_A = 29
        const val KEYCODE_ENTER = 66
        const val KEYCODE_DPAD_CENTER = 23
        const val KEYCODE_APP_SWITCH = 187
    }

    @Nested
    @DisplayName("shouldConsumeKeyEvent — controller button keys")
    inner class ButtonKeyTests {
        @Test
        @DisplayName("KEYCODE_BUTTON_A is consumed")
        fun `button a`() {
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(KEYCODE_BUTTON_A)).isTrue()
        }

        @Test
        @DisplayName("KEYCODE_BUTTON_B is consumed")
        fun `button b`() {
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(KEYCODE_BUTTON_B)).isTrue()
        }

        @Test
        @DisplayName("KEYCODE_BUTTON_X is consumed")
        fun `button x`() {
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(KEYCODE_BUTTON_X)).isTrue()
        }

        @Test
        @DisplayName("KEYCODE_BUTTON_Y is consumed")
        fun `button y`() {
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(KEYCODE_BUTTON_Y)).isTrue()
        }

        @Test
        @DisplayName("KEYCODE_BUTTON_L1 is consumed")
        fun `button l1`() {
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(KEYCODE_BUTTON_L1)).isTrue()
        }

        @Test
        @DisplayName("KEYCODE_BUTTON_R1 is consumed")
        fun `button r1`() {
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(KEYCODE_BUTTON_R1)).isTrue()
        }

        @Test
        @DisplayName("KEYCODE_BUTTON_SELECT is consumed")
        fun `button select`() {
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(KEYCODE_BUTTON_SELECT)).isTrue()
        }

        @Test
        @DisplayName("KEYCODE_BUTTON_START is consumed")
        fun `button start`() {
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(KEYCODE_BUTTON_START)).isTrue()
        }

        @Test
        @DisplayName("KEYCODE_BUTTON_THUMBL is consumed")
        fun `button thumbl`() {
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(KEYCODE_BUTTON_THUMBL)).isTrue()
        }

        @Test
        @DisplayName("KEYCODE_BUTTON_THUMBR is consumed")
        fun `button thumbr`() {
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(KEYCODE_BUTTON_THUMBR)).isTrue()
        }
    }

    @Nested
    @DisplayName("shouldConsumeKeyEvent — D-pad keys")
    inner class DpadKeyTests {
        @Test
        @DisplayName("KEYCODE_DPAD_UP is consumed")
        fun `dpad up`() {
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(KEYCODE_DPAD_UP)).isTrue()
        }

        @Test
        @DisplayName("KEYCODE_DPAD_DOWN is consumed")
        fun `dpad down`() {
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(KEYCODE_DPAD_DOWN)).isTrue()
        }

        @Test
        @DisplayName("KEYCODE_DPAD_LEFT is consumed")
        fun `dpad left`() {
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(KEYCODE_DPAD_LEFT)).isTrue()
        }

        @Test
        @DisplayName("KEYCODE_DPAD_RIGHT is consumed")
        fun `dpad right`() {
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(KEYCODE_DPAD_RIGHT)).isTrue()
        }
    }

    @Nested
    @DisplayName("shouldConsumeKeyEvent — reserved and unmapped keys")
    inner class ReservedKeyTests {
        @Test
        @DisplayName("KEYCODE_BACK is NEVER consumed")
        fun `back never consumed`() {
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(KEYCODE_BACK)).isFalse()
        }

        @Test
        @DisplayName("KEYCODE_HOME is NOT consumed")
        fun `home not consumed`() {
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(KEYCODE_HOME)).isFalse()
        }

        @Test
        @DisplayName("KEYCODE_A (typewriter) is NOT consumed")
        fun `typewriter a not consumed`() {
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(KEYCODE_A)).isFalse()
        }

        @Test
        @DisplayName("KEYCODE_ENTER is NOT consumed")
        fun `enter not consumed`() {
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(KEYCODE_ENTER)).isFalse()
        }

        @Test
        @DisplayName("KEYCODE_DPAD_CENTER is NOT consumed")
        fun `dpad center not consumed`() {
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(KEYCODE_DPAD_CENTER)).isFalse()
        }

        @Test
        @DisplayName("KEYCODE_APP_SWITCH is NOT consumed")
        fun `app switch not consumed`() {
            assertThat(EventConsumptionPolicy.shouldConsumeKeyEvent(KEYCODE_APP_SWITCH)).isFalse()
        }
    }

    @Nested
    @DisplayName("isBackKey — explicit back detection")
    inner class BackKeyTests {
        @Test
        @DisplayName("KEYCODE_BACK is recognized")
        fun `back recognized`() {
            assertThat(EventConsumptionPolicy.isBackKey(KEYCODE_BACK)).isTrue()
        }

        @Test
        @DisplayName("other keys are not back")
        fun `other keys not back`() {
            assertThat(EventConsumptionPolicy.isBackKey(KEYCODE_BUTTON_A)).isFalse()
        }
    }
}

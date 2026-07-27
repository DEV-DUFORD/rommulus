package com.romm.androidtv.controller

import com.romm.androidtv.controller.policy.SourceFilterPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for SourceFilterPolicy.
 * Validates device classification logic without Android framework.
 */
@DisplayName("SourceFilterPolicy — device source classification")
class SourceFilterPolicyTest {

    private companion object {
        const val SOURCE_KEYBOARD = android.view.InputDevice.SOURCE_KEYBOARD
        const val SOURCE_DPAD = android.view.InputDevice.SOURCE_DPAD
        const val SOURCE_GAMEPAD = android.view.InputDevice.SOURCE_GAMEPAD
        const val SOURCE_JOYSTICK = android.view.InputDevice.SOURCE_JOYSTICK
        const val SOURCE_TOUCHSCREEN = android.view.InputDevice.SOURCE_TOUCHSCREEN
    }

    @Nested
    @DisplayName("isControllerSource — source mask filtering")
    inner class ControllerSourceTests {
        @Test
        @DisplayName("GAMEPAD is controller")
        fun `gamepad`() {
            assertThat(SourceFilterPolicy.isControllerSource(SOURCE_GAMEPAD)).isTrue()
        }

        @Test
        @DisplayName("JOYSTICK is controller")
        fun `joystick`() {
            assertThat(SOURCE_JOYSTICK).isEqualTo(0x01000010)
            assertThat(SourceFilterPolicy.isControllerSource(SOURCE_JOYSTICK)).isTrue()
        }

        @Test
        @DisplayName("obsolete class-bit placeholder is not a joystick source")
        fun `incorrect legacy joystick mask`() {
            assertThat(SourceFilterPolicy.isControllerSource(0x00000800)).isFalse()
        }

        @Test
        @DisplayName("DPAD is potential controller")
        fun `dpad`() {
            assertThat(SourceFilterPolicy.isControllerSource(SOURCE_DPAD)).isTrue()
        }

        @Test
        @DisplayName("GAMEPAD + JOYSTICK combined is controller")
        fun `gamepad joystick`() {
            assertThat(SourceFilterPolicy.isControllerSource(
                SOURCE_GAMEPAD or SOURCE_JOYSTICK
            )).isTrue()
        }

        @Test
        @DisplayName("GAMEPAD + DPAD combined is controller")
        fun `gamepad dpad`() {
            assertThat(SourceFilterPolicy.isControllerSource(
                SOURCE_GAMEPAD or SOURCE_DPAD
            )).isTrue()
        }

        @Test
        @DisplayName("KEYBOARD alone is NOT controller")
        fun `keyboard`() {
            assertThat(SourceFilterPolicy.isControllerSource(SOURCE_KEYBOARD)).isFalse()
        }

        @Test
        @DisplayName("TOUCHSCREEN alone is NOT controller")
        fun `touchscreen`() {
            assertThat(SourceFilterPolicy.isControllerSource(SOURCE_TOUCHSCREEN)).isFalse()
        }

        @Test
        @DisplayName("KEYBOARD + DPAD is a potential controller")
        fun `keyboard dpad`() {
            assertThat(SourceFilterPolicy.isControllerSource(
                SOURCE_KEYBOARD or SOURCE_DPAD
            )).isTrue() // DPAD is present, so it passes source filter
        }
    }

    @Nested
    @DisplayName("isTvRemote — remote vs controller disambiguation")
    inner class TvRemoteTests {
        @Test
        @DisplayName("GAMEPAD is never a remote")
        fun `gamepad not remote`() {
            assertThat(SourceFilterPolicy.isTvRemote(SOURCE_GAMEPAD, 0)).isFalse()
            assertThat(SourceFilterPolicy.isTvRemote(SOURCE_GAMEPAD, 4)).isFalse()
        }

        @Test
        @DisplayName("JOYSTICK is never a remote")
        fun `joystick not remote`() {
            assertThat(SourceFilterPolicy.isTvRemote(SOURCE_JOYSTICK, 0)).isFalse()
            assertThat(SourceFilterPolicy.isTvRemote(SOURCE_JOYSTICK, 2)).isFalse()
        }

        @Test
        @DisplayName("DPAD with joystick axes is NOT a remote")
        fun `dpad with axes not remote`() {
            // Retro gamepad: DPAD source + AXIS_X, AXIS_Y
            assertThat(SourceFilterPolicy.isTvRemote(SOURCE_DPAD, 2)).isFalse()
        }

        @Test
        @DisplayName("DPAD without joystick axes IS a remote")
        fun `dpad without axes is remote`() {
            // TV remote: DPAD source, no joystick axes
            assertThat(SourceFilterPolicy.isTvRemote(SOURCE_DPAD, 0)).isTrue()
        }

        @Test
        @DisplayName("GAMEPAD + DPAD is never a remote")
        fun `gamepad dpad combo`() {
            assertThat(SourceFilterPolicy.isTvRemote(
                SOURCE_GAMEPAD or SOURCE_DPAD, 0
            )).isFalse()
        }

        @Test
        @DisplayName("JOYSTICK + DPAD is never a remote")
        fun `joystick dpad combo`() {
            assertThat(SourceFilterPolicy.isTvRemote(
                SOURCE_JOYSTICK or SOURCE_DPAD, 0
            )).isFalse()
        }
    }
}

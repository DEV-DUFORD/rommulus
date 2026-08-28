package com.romm.androidtv.controller

import com.romm.androidtv.controller.policy.SourceFilterPolicy
import com.romm.androidtv.controller.policy.SourceMask
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for SourceFilterPolicy.
 * Uses neutral [SourceMask] bits (mapped from platform sources at the boundary).
 */
@DisplayName("SourceFilterPolicy — device source classification")
class SourceFilterPolicyTest {

    @Nested
    @DisplayName("isControllerSource — source mask filtering")
    inner class ControllerSourceTests {
        @Test
        @DisplayName("GAMEPAD is controller")
        fun `gamepad`() {
            assertThat(SourceFilterPolicy.isControllerSource(SourceMask.GAMEPAD)).isTrue()
        }

        @Test
        @DisplayName("JOYSTICK is controller")
        fun `joystick`() {
            assertThat(SourceFilterPolicy.isControllerSource(SourceMask.JOYSTICK)).isTrue()
        }

        @Test
        @DisplayName("an unrelated source bit is not a controller")
        fun `unrelated source bit`() {
            assertThat(SourceFilterPolicy.isControllerSource(0x00001000)).isFalse()
        }

        @Test
        @DisplayName("DPAD is potential controller")
        fun `dpad`() {
            assertThat(SourceFilterPolicy.isControllerSource(SourceMask.DPAD)).isTrue()
        }

        @Test
        @DisplayName("GAMEPAD + JOYSTICK combined is controller")
        fun `gamepad joystick`() {
            assertThat(SourceFilterPolicy.isControllerSource(
                SourceMask.GAMEPAD or SourceMask.JOYSTICK
            )).isTrue()
        }

        @Test
        @DisplayName("GAMEPAD + DPAD combined is controller")
        fun `gamepad dpad`() {
            assertThat(SourceFilterPolicy.isControllerSource(
                SourceMask.GAMEPAD or SourceMask.DPAD
            )).isTrue()
        }

        @Test
        @DisplayName("zero source is NOT controller")
        fun `zero source`() {
            assertThat(SourceFilterPolicy.isControllerSource(0)).isFalse()
        }

        @Test
        @DisplayName("DPAD plus an unrelated bit is a potential controller")
        fun `dpad plus unrelated`() {
            assertThat(SourceFilterPolicy.isControllerSource(
                SourceMask.DPAD or 0x00001000
            )).isTrue() // DPAD is present, so it passes source filter
        }
    }

    @Nested
    @DisplayName("isTvRemote — remote vs controller disambiguation")
    inner class TvRemoteTests {
        @Test
        @DisplayName("GAMEPAD is never a remote")
        fun `gamepad not remote`() {
            assertThat(SourceFilterPolicy.isTvRemote(SourceMask.GAMEPAD, 0)).isFalse()
            assertThat(SourceFilterPolicy.isTvRemote(SourceMask.GAMEPAD, 4)).isFalse()
        }

        @Test
        @DisplayName("JOYSTICK is never a remote")
        fun `joystick not remote`() {
            assertThat(SourceFilterPolicy.isTvRemote(SourceMask.JOYSTICK, 0)).isFalse()
            assertThat(SourceFilterPolicy.isTvRemote(SourceMask.JOYSTICK, 2)).isFalse()
        }

        @Test
        @DisplayName("DPAD with joystick axes is NOT a remote")
        fun `dpad with axes not remote`() {
            // Retro gamepad: DPAD source + joystick axes
            assertThat(SourceFilterPolicy.isTvRemote(SourceMask.DPAD, 2)).isFalse()
        }

        @Test
        @DisplayName("DPAD without joystick axes IS a remote")
        fun `dpad without axes is remote`() {
            // TV remote: DPAD source, no joystick axes
            assertThat(SourceFilterPolicy.isTvRemote(SourceMask.DPAD, 0)).isTrue()
        }

        @Test
        @DisplayName("GAMEPAD + DPAD is never a remote")
        fun `gamepad dpad combo`() {
            assertThat(SourceFilterPolicy.isTvRemote(
                SourceMask.GAMEPAD or SourceMask.DPAD, 0
            )).isFalse()
        }

        @Test
        @DisplayName("JOYSTICK + DPAD is never a remote")
        fun `joystick dpad combo`() {
            assertThat(SourceFilterPolicy.isTvRemote(
                SourceMask.JOYSTICK or SourceMask.DPAD, 0
            )).isFalse()
        }
    }
}

package com.romm.androidtv.controller.router

import android.view.InputDevice
import com.romm.androidtv.controller.policy.SourceMask
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AndroidSourceMaskTest {

    @Test
    fun `controller sources require the complete Android source constant`() {
        assertThat(sourceMaskFromAndroid(InputDevice.SOURCE_GAMEPAD)).isEqualTo(SourceMask.GAMEPAD)
        assertThat(sourceMaskFromAndroid(InputDevice.SOURCE_JOYSTICK)).isEqualTo(SourceMask.JOYSTICK)
        assertThat(sourceMaskFromAndroid(InputDevice.SOURCE_DPAD)).isEqualTo(SourceMask.DPAD)
    }

    @Test
    fun `shared class bits do not make Samsung input devices controllers`() {
        assertThat(sourceMaskFromAndroid(InputDevice.SOURCE_KEYBOARD)).isZero()
        assertThat(
            sourceMaskFromAndroid(InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_TOUCHSCREEN)
        ).isZero()
    }
}

package com.romm.androidtv.controller

import com.romm.androidtv.controller.model.NeutralKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("NeutralKey — platform mapping round-trip")
class NeutralKeyTest {

    @Test
    fun `round-trips Android key codes through fromPlatform`() {
        assertThat(NeutralKey.fromPlatform(NeutralKey.BUTTON_A.platformCode)).isEqualTo(NeutralKey.BUTTON_A)
        assertThat(NeutralKey.fromPlatform(NeutralKey.BUTTON_B.platformCode)).isEqualTo(NeutralKey.BUTTON_B)
        assertThat(NeutralKey.fromPlatform(NeutralKey.BUTTON_X.platformCode)).isEqualTo(NeutralKey.BUTTON_X)
        assertThat(NeutralKey.fromPlatform(NeutralKey.BUTTON_Y.platformCode)).isEqualTo(NeutralKey.BUTTON_Y)
        assertThat(NeutralKey.fromPlatform(NeutralKey.DPAD_UP.platformCode)).isEqualTo(NeutralKey.DPAD_UP)
        assertThat(NeutralKey.fromPlatform(NeutralKey.BACK.platformCode)).isEqualTo(NeutralKey.BACK)
    }

    @Test
    fun `known Android key codes resolve correctly`() {
        // KEYCODE_BUTTON_A = 96, KEYCODE_DPAD_UP = 19, KEYCODE_BACK = 4
        assertThat(NeutralKey.fromPlatform(96)).isEqualTo(NeutralKey.BUTTON_A)
        assertThat(NeutralKey.fromPlatform(19)).isEqualTo(NeutralKey.DPAD_UP)
        assertThat(NeutralKey.fromPlatform(4)).isEqualTo(NeutralKey.BACK)
    }

    @Test
    fun `unknown platform code returns null`() {
        assertThat(NeutralKey.fromPlatform(-1)).isNull()
        assertThat(NeutralKey.fromPlatform(0)).isNull()
        assertThat(NeutralKey.fromPlatform(Int.MAX_VALUE)).isNull()
    }

    @Test
    fun `platform codes are unique`() {
        val codes = NeutralKey.entries.map { it.platformCode }
        assertThat(codes).doesNotHaveDuplicates()
    }

    @Test
    fun `BACK is included for EventConsumptionPolicy`() {
        assertThat(NeutralKey.entries).contains(NeutralKey.BACK)
    }
}

package com.romm.androidtv.controller.adapters

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AndroidControllerInputProfilesTest {
    @Test
    fun `Hyperkin N64 stick drift is clamped by expanded deadzone`() {
        assertThat(
            AndroidControllerInputProfiles.applyDeadzone(
                vendorId = 0x2e24,
                productId = 0x0bff,
                axis = 0,
                normalizedValue = -0.20f,
            ),
        ).isZero()
        assertThat(
            AndroidControllerInputProfiles.applyDeadzone(
                vendorId = 0x2e24,
                productId = 0x0bff,
                axis = 1,
                normalizedValue = 0.29f,
            ),
        ).isZero()
    }

    @Test
    fun `Hyperkin deadzone preserves deliberate stick movement and non-stick axes`() {
        assertThat(
            AndroidControllerInputProfiles.applyDeadzone(0x2e24, 0x0bff, 0, 0.30f),
        ).isEqualTo(0.30f)
        assertThat(
            AndroidControllerInputProfiles.applyDeadzone(0x2e24, 0x0bff, 11, 0.20f),
        ).isEqualTo(0.20f)
    }

    @Test
    fun `other controllers retain their normalized values`() {
        assertThat(
            AndroidControllerInputProfiles.applyDeadzone(0x045e, 0x02ea, 0, 0.20f),
        ).isEqualTo(0.20f)
    }
}

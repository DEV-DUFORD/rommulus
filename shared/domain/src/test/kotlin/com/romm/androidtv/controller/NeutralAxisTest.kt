package com.romm.androidtv.controller

import com.romm.androidtv.controller.model.NeutralAxis
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("NeutralAxis — platform mapping round-trip")
class NeutralAxisTest {

    @Test
    fun `round-trips Android axis constants through fromPlatform`() {
        assertThat(NeutralAxis.fromPlatform(NeutralAxis.X.platformCode)).isEqualTo(NeutralAxis.X)
        assertThat(NeutralAxis.fromPlatform(NeutralAxis.Y.platformCode)).isEqualTo(NeutralAxis.Y)
        assertThat(NeutralAxis.fromPlatform(NeutralAxis.RX.platformCode)).isEqualTo(NeutralAxis.RX)
        assertThat(NeutralAxis.fromPlatform(NeutralAxis.RZ.platformCode)).isEqualTo(NeutralAxis.RZ)
        assertThat(NeutralAxis.fromPlatform(NeutralAxis.HAT_X.platformCode)).isEqualTo(NeutralAxis.HAT_X)
        assertThat(NeutralAxis.fromPlatform(NeutralAxis.HAT_Y.platformCode)).isEqualTo(NeutralAxis.HAT_Y)
        assertThat(NeutralAxis.fromPlatform(NeutralAxis.LTRIGGER.platformCode)).isEqualTo(NeutralAxis.LTRIGGER)
        assertThat(NeutralAxis.fromPlatform(NeutralAxis.GAS.platformCode)).isEqualTo(NeutralAxis.GAS)
    }

    @Test
    fun `known Android axis constants resolve correctly`() {
        // AXIS_X = 0, AXIS_Y = 1, AXIS_RX = 12, AXIS_GAS = 22
        assertThat(NeutralAxis.fromPlatform(0)).isEqualTo(NeutralAxis.X)
        assertThat(NeutralAxis.fromPlatform(1)).isEqualTo(NeutralAxis.Y)
        assertThat(NeutralAxis.fromPlatform(12)).isEqualTo(NeutralAxis.RX)
        assertThat(NeutralAxis.fromPlatform(15)).isEqualTo(NeutralAxis.HAT_X)
        assertThat(NeutralAxis.fromPlatform(16)).isEqualTo(NeutralAxis.HAT_Y)
        assertThat(NeutralAxis.fromPlatform(22)).isEqualTo(NeutralAxis.GAS)
    }

    @Test
    fun `unknown platform code returns null`() {
        assertThat(NeutralAxis.fromPlatform(-1)).isNull()
        assertThat(NeutralAxis.fromPlatform(Int.MAX_VALUE)).isNull()
    }

    @Test
    fun `platform codes are unique`() {
        val codes = NeutralAxis.entries.map { it.platformCode }
        assertThat(codes).doesNotHaveDuplicates()
    }
}

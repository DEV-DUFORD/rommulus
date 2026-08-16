package com.romm.androidtv.controller

import com.romm.androidtv.controller.model.NEUTRAL_AXIS_TO_CONTROL
import com.romm.androidtv.controller.model.NEUTRAL_KEY_TO_CONTROL
import com.romm.androidtv.controller.model.LogicalControl
import com.romm.androidtv.controller.model.NeutralAxis
import com.romm.androidtv.controller.model.NeutralKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Neutral mapping tables — coverage")
class NeutralMappingTest {

    @Test
    fun `NEUTRAL_KEY_TO_CONTROL covers all 14 standard gamepad keys`() {
        val keys = setOf(
            NeutralKey.BUTTON_A, NeutralKey.BUTTON_B, NeutralKey.BUTTON_X, NeutralKey.BUTTON_Y,
            NeutralKey.BUTTON_L1, NeutralKey.BUTTON_R1, NeutralKey.BUTTON_SELECT,
            NeutralKey.BUTTON_START, NeutralKey.BUTTON_THUMBL, NeutralKey.BUTTON_THUMBR,
            NeutralKey.DPAD_UP, NeutralKey.DPAD_DOWN, NeutralKey.DPAD_LEFT, NeutralKey.DPAD_RIGHT
        )
        assertThat(NEUTRAL_KEY_TO_CONTROL).containsOnlyKeys(keys)
        assertThat(NEUTRAL_KEY_TO_CONTROL).hasSize(14)
    }

    @Test
    fun `NEUTRAL_KEY_TO_CONTROL maps each key to its logical control`() {
        assertThat(NEUTRAL_KEY_TO_CONTROL[NeutralKey.BUTTON_A]).isEqualTo(LogicalControl.BUTTON_A)
        assertThat(NEUTRAL_KEY_TO_CONTROL[NeutralKey.BUTTON_B]).isEqualTo(LogicalControl.BUTTON_B)
        assertThat(NEUTRAL_KEY_TO_CONTROL[NeutralKey.BUTTON_X]).isEqualTo(LogicalControl.BUTTON_X)
        assertThat(NEUTRAL_KEY_TO_CONTROL[NeutralKey.BUTTON_Y]).isEqualTo(LogicalControl.BUTTON_Y)
        assertThat(NEUTRAL_KEY_TO_CONTROL[NeutralKey.BUTTON_L1]).isEqualTo(LogicalControl.BUTTON_LB)
        assertThat(NEUTRAL_KEY_TO_CONTROL[NeutralKey.BUTTON_R1]).isEqualTo(LogicalControl.BUTTON_RB)
        assertThat(NEUTRAL_KEY_TO_CONTROL[NeutralKey.BUTTON_SELECT]).isEqualTo(LogicalControl.BUTTON_SELECT)
        assertThat(NEUTRAL_KEY_TO_CONTROL[NeutralKey.BUTTON_START]).isEqualTo(LogicalControl.BUTTON_START)
        assertThat(NEUTRAL_KEY_TO_CONTROL[NeutralKey.BUTTON_THUMBL]).isEqualTo(LogicalControl.BUTTON_L3)
        assertThat(NEUTRAL_KEY_TO_CONTROL[NeutralKey.BUTTON_THUMBR]).isEqualTo(LogicalControl.BUTTON_R3)
        assertThat(NEUTRAL_KEY_TO_CONTROL[NeutralKey.DPAD_UP]).isEqualTo(LogicalControl.DPAD_UP)
        assertThat(NEUTRAL_KEY_TO_CONTROL[NeutralKey.DPAD_DOWN]).isEqualTo(LogicalControl.DPAD_DOWN)
        assertThat(NEUTRAL_KEY_TO_CONTROL[NeutralKey.DPAD_LEFT]).isEqualTo(LogicalControl.DPAD_LEFT)
        assertThat(NEUTRAL_KEY_TO_CONTROL[NeutralKey.DPAD_RIGHT]).isEqualTo(LogicalControl.DPAD_RIGHT)
    }

    @Test
    fun `NEUTRAL_AXIS_TO_CONTROL covers all 10 standard axes`() {
        val axes = setOf(
            NeutralAxis.X, NeutralAxis.Y, NeutralAxis.RX, NeutralAxis.RY,
            NeutralAxis.Z, NeutralAxis.RZ, NeutralAxis.LTRIGGER, NeutralAxis.RTRIGGER,
            NeutralAxis.BRAKE, NeutralAxis.GAS
        )
        assertThat(NEUTRAL_AXIS_TO_CONTROL).containsOnlyKeys(axes)
        assertThat(NEUTRAL_AXIS_TO_CONTROL).hasSize(10)
    }

    @Test
    fun `NEUTRAL_AXIS_TO_CONTROL maps each axis to its logical control`() {
        assertThat(NEUTRAL_AXIS_TO_CONTROL[NeutralAxis.X]).isEqualTo(LogicalControl.AXIS_LX)
        assertThat(NEUTRAL_AXIS_TO_CONTROL[NeutralAxis.Y]).isEqualTo(LogicalControl.AXIS_LY)
        assertThat(NEUTRAL_AXIS_TO_CONTROL[NeutralAxis.RX]).isEqualTo(LogicalControl.AXIS_RX)
        assertThat(NEUTRAL_AXIS_TO_CONTROL[NeutralAxis.RY]).isEqualTo(LogicalControl.AXIS_RY)
        assertThat(NEUTRAL_AXIS_TO_CONTROL[NeutralAxis.Z]).isEqualTo(LogicalControl.AXIS_RX) // alias
        assertThat(NEUTRAL_AXIS_TO_CONTROL[NeutralAxis.RZ]).isEqualTo(LogicalControl.AXIS_RY) // alias
        assertThat(NEUTRAL_AXIS_TO_CONTROL[NeutralAxis.LTRIGGER]).isEqualTo(LogicalControl.TRIGGER_LEFT)
        assertThat(NEUTRAL_AXIS_TO_CONTROL[NeutralAxis.RTRIGGER]).isEqualTo(LogicalControl.TRIGGER_RIGHT)
        assertThat(NEUTRAL_AXIS_TO_CONTROL[NeutralAxis.BRAKE]).isEqualTo(LogicalControl.TRIGGER_LEFT) // alias
        assertThat(NEUTRAL_AXIS_TO_CONTROL[NeutralAxis.GAS]).isEqualTo(LogicalControl.TRIGGER_RIGHT) // alias
    }
}

package com.romm.androidtv.emulation.touch

import com.romm.androidtv.controller.model.LogicalControl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TouchControllerStateTest {

    private val state = TouchControllerState()

    @Test
    fun `setButton pressed produces 1f at the logical index`() {
        state.setButton(LogicalControl.BUTTON_A, true)
        val snapshot = state.snapshot()
        assertThat(snapshot.buttons[LogicalControl.BUTTON_A.index]).isEqualTo(1f)
    }

    @Test
    fun `setButton released produces 0f at the logical index`() {
        state.setButton(LogicalControl.BUTTON_B, true)
        state.setButton(LogicalControl.BUTTON_B, false)
        val snapshot = state.snapshot()
        assertThat(snapshot.buttons[LogicalControl.BUTTON_B.index]).isEqualTo(0f)
    }

    @Test
    fun `setAxis stores value within range`(){
        state.setAxis(LogicalControl.AXIS_LX, 0.5f)
        val snapshot = state.snapshot()
        assertThat(snapshot.axes[LogicalControl.AXIS_LX.index]).isEqualTo(0.5f)
    }

    @Test
    fun `setAxis clamps value above 1 to 1`() {
        state.setAxis(LogicalControl.AXIS_LY, 2.0f)
        val snapshot = state.snapshot()
        assertThat(snapshot.axes[LogicalControl.AXIS_LY.index]).isEqualTo(1f)
    }

    @Test
    fun `setAxis clamps value below negative 1 to negative 1`() {
        state.setAxis(LogicalControl.AXIS_RX, -2.0f)
        val snapshot = state.snapshot()
        assertThat(snapshot.axes[LogicalControl.AXIS_RX.index]).isEqualTo(-1f)
    }

    @Test
    fun `snapshot returns a copy and mutating the returned snapshot does not affect state`() {
        state.setButton(LogicalControl.BUTTON_A, true)
        val snap = state.snapshot()
        snap.buttons[0] = 99f
        val snap2 = state.snapshot()
        assertThat(snap2.buttons[LogicalControl.BUTTON_A.index]).isEqualTo(1f)
    }

    @Test
    fun `reset zeroes all buttons and axes`() {
        state.setButton(LogicalControl.BUTTON_A, true)
        state.setButton(LogicalControl.BUTTON_X, true)
        state.setAxis(LogicalControl.AXIS_LX, 0.7f)
        state.setAxis(LogicalControl.AXIS_RY, -0.3f)
        state.reset()

        val snapshot = state.snapshot()
        assertThat(snapshot.buttons).containsOnly(0f)
        assertThat(snapshot.axes).containsOnly(0f)
    }
}

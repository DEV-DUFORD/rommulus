package com.romm.androidtv.controller

import android.view.MotionEvent
import com.romm.androidtv.controller.model.ControllerMapping
import com.romm.androidtv.controller.model.LogicalControl
import com.romm.androidtv.controller.policy.AxisMappingPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AxisMappingPolicyTest {
    @Test
    fun `prefers RX and RY when both right stick layouts are advertised`() {
        val resolved = AxisMappingPolicy.resolve(
            setOf(
                MotionEvent.AXIS_X,
                MotionEvent.AXIS_Y,
                MotionEvent.AXIS_RX,
                MotionEvent.AXIS_RY,
                MotionEvent.AXIS_Z,
                MotionEvent.AXIS_RZ
            ),
            ControllerMapping.DEFAULT_AXES
        )

        assertThat(resolved[MotionEvent.AXIS_RX]).isEqualTo(LogicalControl.AXIS_RX)
        assertThat(resolved[MotionEvent.AXIS_RY]).isEqualTo(LogicalControl.AXIS_RY)
        assertThat(resolved).doesNotContainKeys(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ)
    }

    @Test
    fun `uses Z and RZ for Xbox devices that do not advertise RX and RY`() {
        val resolved = AxisMappingPolicy.resolve(
            setOf(
                MotionEvent.AXIS_X,
                MotionEvent.AXIS_Y,
                MotionEvent.AXIS_Z,
                MotionEvent.AXIS_RZ,
                MotionEvent.AXIS_LTRIGGER,
                MotionEvent.AXIS_RTRIGGER
            ),
            ControllerMapping.DEFAULT_AXES
        )

        assertThat(resolved[MotionEvent.AXIS_Z]).isEqualTo(LogicalControl.AXIS_RX)
        assertThat(resolved[MotionEvent.AXIS_RZ]).isEqualTo(LogicalControl.AXIS_RY)
        assertThat(resolved[MotionEvent.AXIS_LTRIGGER]).isEqualTo(LogicalControl.TRIGGER_LEFT)
        assertThat(resolved[MotionEvent.AXIS_RTRIGGER]).isEqualTo(LogicalControl.TRIGGER_RIGHT)
    }

    @Test
    fun `falls back from trigger axes to brake and gas`() {
        val resolved = AxisMappingPolicy.resolve(
            setOf(MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS),
            ControllerMapping.DEFAULT_AXES
        )

        assertThat(resolved[MotionEvent.AXIS_BRAKE]).isEqualTo(LogicalControl.TRIGGER_LEFT)
        assertThat(resolved[MotionEvent.AXIS_GAS]).isEqualTo(LogicalControl.TRIGGER_RIGHT)
    }
}

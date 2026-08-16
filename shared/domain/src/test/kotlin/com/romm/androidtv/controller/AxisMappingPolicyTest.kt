package com.romm.androidtv.controller

import com.romm.androidtv.controller.model.ControllerMapping
import com.romm.androidtv.controller.model.LogicalControl
import com.romm.androidtv.controller.model.NeutralAxis
import com.romm.androidtv.controller.policy.AxisMappingPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AxisMappingPolicyTest {
    @Test
    fun `prefers RX and RY when both right stick layouts are advertised`() {
        val resolved = AxisMappingPolicy.resolve(
            setOf(
                NeutralAxis.X,
                NeutralAxis.Y,
                NeutralAxis.RX,
                NeutralAxis.RY,
                NeutralAxis.Z,
                NeutralAxis.RZ
            ),
            ControllerMapping.DEFAULT_AXES
        )

        assertThat(resolved[NeutralAxis.RX]).isEqualTo(LogicalControl.AXIS_RX)
        assertThat(resolved[NeutralAxis.RY]).isEqualTo(LogicalControl.AXIS_RY)
        assertThat(resolved).doesNotContainKeys(NeutralAxis.Z, NeutralAxis.RZ)
    }

    @Test
    fun `uses Z and RZ for devices that do not advertise RX and RY`() {
        val resolved = AxisMappingPolicy.resolve(
            setOf(
                NeutralAxis.X,
                NeutralAxis.Y,
                NeutralAxis.Z,
                NeutralAxis.RZ,
                NeutralAxis.LTRIGGER,
                NeutralAxis.RTRIGGER
            ),
            ControllerMapping.DEFAULT_AXES
        )

        assertThat(resolved[NeutralAxis.Z]).isEqualTo(LogicalControl.AXIS_RX)
        assertThat(resolved[NeutralAxis.RZ]).isEqualTo(LogicalControl.AXIS_RY)
        assertThat(resolved[NeutralAxis.LTRIGGER]).isEqualTo(LogicalControl.TRIGGER_LEFT)
        assertThat(resolved[NeutralAxis.RTRIGGER]).isEqualTo(LogicalControl.TRIGGER_RIGHT)
    }

    @Test
    fun `falls back from trigger axes to brake and gas`() {
        val resolved = AxisMappingPolicy.resolve(
            setOf(NeutralAxis.BRAKE, NeutralAxis.GAS),
            ControllerMapping.DEFAULT_AXES
        )

        assertThat(resolved[NeutralAxis.BRAKE]).isEqualTo(LogicalControl.TRIGGER_LEFT)
        assertThat(resolved[NeutralAxis.GAS]).isEqualTo(LogicalControl.TRIGGER_RIGHT)
    }
}

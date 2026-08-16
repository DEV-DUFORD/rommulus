package com.romm.androidtv.controller.policy

import com.romm.androidtv.controller.model.LogicalControl
import com.romm.androidtv.controller.model.NeutralAxis

/**
 * Selects one physical axis for each logical control from a device's advertised axes.
 */
object AxisMappingPolicy {
    private val priorities = linkedMapOf(
        LogicalControl.AXIS_LX to listOf(NeutralAxis.X),
        LogicalControl.AXIS_LY to listOf(NeutralAxis.Y),
        LogicalControl.AXIS_RX to listOf(NeutralAxis.RX, NeutralAxis.Z),
        LogicalControl.AXIS_RY to listOf(NeutralAxis.RY, NeutralAxis.RZ),
        LogicalControl.TRIGGER_LEFT to listOf(
            NeutralAxis.LTRIGGER,
            NeutralAxis.BRAKE
        ),
        LogicalControl.TRIGGER_RIGHT to listOf(
            NeutralAxis.RTRIGGER,
            NeutralAxis.GAS
        )
    )

    fun resolve(
        supportedAxes: Set<NeutralAxis>,
        configuredAxes: Map<NeutralAxis, LogicalControl>
    ): Map<NeutralAxis, LogicalControl> {
        val resolved = linkedMapOf<NeutralAxis, LogicalControl>()

        for ((logical, preferredAxes) in priorities) {
            val configuredCandidates = configuredAxes
                .filterValues { it == logical }
                .keys
                .filter { it in supportedAxes }

            val selected = preferredAxes.firstOrNull { it in configuredCandidates }
                ?: configuredCandidates.firstOrNull()
            if (selected != null) {
                resolved[selected] = logical
            }
        }

        return resolved
    }
}

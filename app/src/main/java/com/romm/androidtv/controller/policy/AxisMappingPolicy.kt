package com.romm.androidtv.controller.policy

import android.view.MotionEvent
import com.romm.androidtv.controller.model.LogicalControl

/**
 * Selects one physical axis for each logical control from a device's advertised axes.
 */
object AxisMappingPolicy {
    private val priorities = linkedMapOf(
        LogicalControl.AXIS_LX to listOf(MotionEvent.AXIS_X),
        LogicalControl.AXIS_LY to listOf(MotionEvent.AXIS_Y),
        LogicalControl.AXIS_RX to listOf(MotionEvent.AXIS_RX, MotionEvent.AXIS_Z),
        LogicalControl.AXIS_RY to listOf(MotionEvent.AXIS_RY, MotionEvent.AXIS_RZ),
        LogicalControl.TRIGGER_LEFT to listOf(
            MotionEvent.AXIS_LTRIGGER,
            MotionEvent.AXIS_BRAKE
        ),
        LogicalControl.TRIGGER_RIGHT to listOf(
            MotionEvent.AXIS_RTRIGGER,
            MotionEvent.AXIS_GAS
        )
    )

    fun resolve(
        supportedAxes: Set<Int>,
        configuredAxes: Map<Int, LogicalControl>
    ): Map<Int, LogicalControl> {
        val resolved = linkedMapOf<Int, LogicalControl>()

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

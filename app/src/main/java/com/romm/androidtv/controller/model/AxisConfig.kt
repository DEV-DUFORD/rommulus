package com.romm.androidtv.controller.model

/**
 * Per-axis normalization configuration: deadzone and inversion.
 *
 * Deadzone is expressed as a fraction of the normalized [-1, +1] range.
 * Values within ±deadzone are clamped to zero.
 *
 * Inversion flips the sign of the axis output.
 */
data class AxisConfig(
    val deadzone: Float = 0.15f,
    val inverted: Boolean = false
) {
    init {
        require(deadzone in 0f..1f) { "deadzone must be in [0, 1], was $deadzone" }
    }

    /** Apply deadzone and inversion to a normalized axis value in [-1, +1]. */
    fun apply(raw: Float): Float {
        val clamped = raw.coerceIn(-1f, 1f)
        val afterDeadzone = if (kotlin.math.abs(clamped) < deadzone) 0f else clamped
        return if (inverted) -afterDeadzone else afterDeadzone
    }

    companion object {
        val DEFAULT = AxisConfig(deadzone = 0.15f, inverted = false)
    }
}

/**
 * Mapping configuration for a single logical player slot.
 *
 * Maps physical Android inputs (keyCode or axis constant) to standard
 * [LogicalControl] outputs. The map is keyed by the physical input
 * and valued by the target logical control.
 */
data class ControllerMapping(
    /** Physical button keyCode -> LogicalControl mapping. */
    val buttons: Map<Int, LogicalControl> = DEFAULT_BUTTONS,

    /** Physical axis constant -> LogicalControl mapping. */
    val axes: Map<Int, LogicalControl> = DEFAULT_AXES,

    /** Per-logical-axis configuration (deadzone, inversion). */
    val axisConfigs: Map<LogicalControl, AxisConfig> = emptyMap()
) {
    /** Retrieve the deadzone/inversion config for a given logical axis. Defaults to standard. */
    fun getAxisConfig(logical: LogicalControl): AxisConfig =
        axisConfigs[logical] ?: AxisConfig.DEFAULT

    companion object {
        /**
         * Android-standard default button mapping.
         * Physical key codes map directly to their semantic logical control.
         */
        val DEFAULT_BUTTONS: Map<Int, LogicalControl> = KEYCODE_TO_CONTROL.toMap()

        /**
         * Android-standard default axis mapping.
         * Physical motion axes map directly to their semantic logical axis.
         */
        val DEFAULT_AXES: Map<Int, LogicalControl> = AXIS_TO_CONTROL.toMap()

        /** A/ B swap: exchanges BUTTON_A and BUTTON_B assignments. */
        fun swapAB(mapping: ControllerMapping): ControllerMapping {
            val newButtons = mapping.buttons.toMutableMap()
            val aKey = KEYCODE_TO_CONTROL.entries.find { it.value == LogicalControl.BUTTON_A }?.key
            val bKey = KEYCODE_TO_CONTROL.entries.find { it.value == LogicalControl.BUTTON_B }?.key

            if (aKey != null && bKey != null) {
                // Find any key mapped to A or B and swap
                val keysMappedToA = mapping.buttons.filterValues { it == LogicalControl.BUTTON_A }.keys
                val keysMappedToB = mapping.buttons.filterValues { it == LogicalControl.BUTTON_B }.keys

                // Swap: keys that mapped to A now map to B, and vice versa
                keysMappedToA.forEach { newButtons[it] = LogicalControl.BUTTON_B }
                keysMappedToB.forEach { newButtons[it] = LogicalControl.BUTTON_A }
            }

            return ControllerMapping(
                buttons = newButtons,
                axes = mapping.axes,
                axisConfigs = mapping.axisConfigs
            )
        }
    }
}

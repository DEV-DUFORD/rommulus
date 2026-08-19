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
 * A single half-axis direction of a physical motion axis.
 *
 * Mirrors [com.romm.androidtv.controller.config.PhysicalBinding.AxisDirection]
 * at the runtime layer: an [axis] constant (e.g. [android.view.MotionEvent.AXIS_X])
 * plus a [polarity] of -1 (negative half-axis) or +1 (positive half-axis).
 * When bound to a digital [LogicalControl], the axis crossing the active side
 * of this polarity acts like a digital button press.
 */
data class AxisDirection(
    val axis: Int,
    val polarity: Int
) {
    init {
        require(polarity == -1 || polarity == 1) { "polarity must be -1 or 1, was $polarity" }
    }
}

/** A physical digital input used by an app-level controller shortcut. */
sealed interface PhysicalControl {
    data class Key(val keyCode: Int) : PhysicalControl
    data class AxisDirection(val axis: Int, val polarity: Int) : PhysicalControl {
        init {
            require(polarity == -1 || polarity == 1) {
                "polarity must be -1 or 1, was $polarity"
            }
        }
    }
}

/**
 * Two physical inputs that must be held together to open the in-game pause
 * menu. This is deliberately separate from a core's RetroPad mappings.
 */
data class PauseMenuCombination(
    val first: PhysicalControl,
    val second: PhysicalControl,
) {
    init {
        require(first != second) { "pause menu combination requires two distinct inputs" }
    }
}

/**
 * Mapping configuration for a single logical player slot.
 *
 * Maps physical Android inputs (keyCode, axis constant, or axis+polarity) to
 * standard [LogicalControl] outputs. The map is keyed by the physical input
 * and valued by the target logical control.
 */
data class ControllerMapping(
    /** Physical button keyCode -> LogicalControl mapping. */
    val buttons: Map<Int, LogicalControl> = DEFAULT_BUTTONS,

    /** Physical axis constant -> LogicalControl mapping. */
    val axes: Map<Int, LogicalControl> = DEFAULT_AXES,

    /** Per-logical-axis configuration (deadzone, inversion). */
    val axisConfigs: Map<LogicalControl, AxisConfig> = emptyMap(),

    /** Physical axis+polarity -> digital LogicalControl mapping (half-axis to button). */
    val axisDirections: Map<AxisDirection, LogicalControl> = emptyMap(),

    /** Optional app-level two-button shortcut, excluded from RetroPad output. */
    val pauseMenuCombination: PauseMenuCombination? = null,
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
                axisConfigs = mapping.axisConfigs,
                axisDirections = mapping.axisDirections,
                pauseMenuCombination = mapping.pauseMenuCombination,
            )
        }
    }
}

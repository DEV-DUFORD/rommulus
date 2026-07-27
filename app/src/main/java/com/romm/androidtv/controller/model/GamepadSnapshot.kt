package com.romm.androidtv.controller.model

/**
 * Immutable snapshot of a virtual gamepad's current state.
 *
 * Mirrors the Gamepad API layout: 16 buttons (0-15) and 6 axes (0-5).
 * Button values are 0.0f (released) or 1.0f (pressed).
 * Axis values are in [-1.0f, +1.0f], already post-deadzone/inversion.
 *
 * This snapshot is the sole source of truth downstream: RomM and EmulatorJS
 * receive only translated snapshots, never raw Android events.
 */
data class GamepadSnapshot(
    val buttons: FloatArray,
    val axes: FloatArray
) {
    init {
        require(buttons.size == 16) { "GamepadSnapshot requires exactly 16 buttons" }
        require(axes.size == 6) { "GamepadSnapshot requires exactly 6 axes" }
    }

    /** Whether any button is currently pressed. */
    val isAnyButtonPressed: Boolean
        get() = buttons.any { it > 0f }

    /** Whether any axis is moving beyond rest. */
    val isAnyAxisActive: Boolean
        get() = axes.any { kotlin.math.abs(it) > 0.01f }

    companion object {
        /** Empty snapshot: all buttons released, all axes at zero. */
        val EMPTY = GamepadSnapshot(
            buttons = FloatArray(16),
            axes = FloatArray(6)
        )

        /**
         * Create a snapshot by applying a [ControllerMapping] to raw physical input.
         *
         * @param pressedKeys set of currently-pressed Android keyCodes
         * @param axisValues map of MotionEvent axis constant -> raw float value
         * @param mapping the active mapping for this slot
         */
        fun fromPhysicalInput(
            pressedKeys: Set<Int>,
            axisValues: Map<Int, Float>,
            mapping: ControllerMapping
        ): GamepadSnapshot {
            val buttons = FloatArray(16)
            val axes = FloatArray(6)

            // Apply button mappings
            for ((keyCode, logical) in mapping.buttons) {
                if (logical.type == LogicalControl.Type.BUTTON && keyCode in pressedKeys) {
                    buttons[logical.index] = 1.0f
                }
            }

            // Apply axis mappings with deadzone/inversion
            for ((axisConstant, logical) in mapping.axes) {
                val rawValue = axisValues[axisConstant] ?: continue
                if (logical.type == LogicalControl.Type.AXIS) {
                    val config = mapping.getAxisConfig(logical)
                    axes[logical.index] = config.apply(rawValue)
                }
            }

            return GamepadSnapshot(buttons = buttons, axes = axes)
        }

        /**
         * Produce a new snapshot by toggling a single button.
         * Used for incremental updates from KeyEvent up/down transitions.
         */
        fun withButton(snapshot: GamepadSnapshot, logical: LogicalControl, pressed: Boolean): GamepadSnapshot {
            if (logical.type != LogicalControl.Type.BUTTON) return snapshot
            val newButtons = snapshot.buttons.copyOf()
            newButtons[logical.index] = if (pressed) 1.0f else 0.0f
            return GamepadSnapshot(buttons = newButtons, axes = snapshot.axes.copyOf())
        }

        /**
         * Produce a new snapshot by setting a single axis value.
         */
        fun withAxis(snapshot: GamepadSnapshot, logical: LogicalControl, value: Float): GamepadSnapshot {
            if (logical.type != LogicalControl.Type.AXIS) return snapshot
            val newAxes = snapshot.axes.copyOf()
            newAxes[logical.index] = value.coerceIn(-1f, 1f)
            return GamepadSnapshot(buttons = snapshot.buttons.copyOf(), axes = newAxes)
        }
    }
}

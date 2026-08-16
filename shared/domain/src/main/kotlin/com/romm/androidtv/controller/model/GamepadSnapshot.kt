package com.romm.androidtv.controller.model

import kotlin.math.atan2
import kotlin.math.PI

/**
 * Immutable snapshot of a virtual gamepad's current state.
 *
 * Mirrors the Gamepad API layout: 16 buttons (0-15) and 6 axes (0-5).
 * Button values are 0.0f (released) or 1.0f (pressed).
 * Axis values are in [-1.0f, +1.0f], already post-deadzone/inversion.
 *
 * This snapshot is the sole source of truth downstream: RomM and EmulatorJS
 * receive only translated snapshots, never raw platform events.
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
         * @param pressedKeys set of currently-pressed neutral button keys
         * @param axisValues map of neutral axis -> raw float value
         * @param mapping the active mapping for this slot
         */
        fun fromPhysicalInput(
            pressedKeys: Set<NeutralKey>,
            axisValues: Map<NeutralAxis, Float>,
            mapping: ControllerMapping
        ): GamepadSnapshot {
            val buttons = FloatArray(16)
            val axes = FloatArray(6)

            // Apply button mappings
            for ((key, logical) in mapping.buttons) {
                if (logical.type == LogicalControl.Type.BUTTON && key in pressedKeys) {
                    buttons[logical.index] = 1.0f
                }
            }

            // Apply axis mappings with deadzone/inversion
            for ((axis, logical) in mapping.axes) {
                val rawValue = axisValues[axis] ?: continue
                if (logical.type == LogicalControl.Type.AXIS) {
                    val config = mapping.getAxisConfig(logical)
                    axes[logical.index] = config.apply(rawValue)
                }
            }

            // Apply axis-direction -> digital button mappings.
            // When all four d-pad directions are bound to the standard X/Y axes,
            // use true 8-zone angle-based mapping (see below). Otherwise fall back
            // to independent half-axis checks for every direction.
            val dpadBindings = mapDpadAxisDirections(mapping.axisDirections)
            if (dpadBindings != null) {
                applyAngleBasedDpad(buttons, axisValues, mapping, dpadBindings)
                // Process remaining non-d-pad axis directions with half-axis logic.
                for ((direction, logical) in mapping.axisDirections) {
                    if (logical.type != LogicalControl.Type.BUTTON) continue
                    if (isDpadDirection(direction)) continue
                    val rawValue = axisValues[direction.axis] ?: continue
                    val config = mapping.getAxisConfig(logical)
                    val value = config.apply(rawValue)
                    if ((direction.polarity > 0 && value > 0f) ||
                        (direction.polarity < 0 && value < 0f)) {
                        buttons[logical.index] = 1.0f
                    }
                }
            } else {
                // Fallback: independent half-axis for all directions including d-pad.
                for ((direction, logical) in mapping.axisDirections) {
                    if (logical.type != LogicalControl.Type.BUTTON) continue
                    val rawValue = axisValues[direction.axis] ?: continue
                    val config = mapping.getAxisConfig(logical)
                    val value = config.apply(rawValue)
                    if ((direction.polarity > 0 && value > 0f) ||
                        (direction.polarity < 0 && value < 0f)) {
                        buttons[logical.index] = 1.0f
                    }
                }
            }

            return GamepadSnapshot(buttons = buttons, axes = axes)
        }

        /**
         * Produce a new snapshot by toggling a single button.
         * Used for incremental updates from key up/down transitions.
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

        // ─── Angle-based d-pad mapping ────────────────────────────────────────
        //
        // The joystick stick is divided into 8 equal sectors of 45° each.
        // Zone boundaries lie at 22.5°, 67.5°, 112.5°, … (midpoints between cardinal
        // and diagonal directions). Within each sector exactly the buttons for that
        // direction are pressed — cardinal sectors fire one button, diagonal sectors
        // fire two adjacent buttons. This eliminates the "narrow band" problem of
        // independent half-axis checks where even tiny drift on the orthogonal axis
        // produces an unwanted diagonal press.

        private const val DIAGONAL_ZONE_HALF_WIDTH_DEGREES = 22.5f

        /** Standard d-pad AxisDirections (must match CoreControllerProfiles defaults). */
        private val DPAD_UP_DIR     = AxisDirection(NeutralAxis.Y, -1)
        private val DPAD_DOWN_DIR   = AxisDirection(NeutralAxis.Y,  1)
        private val DPAD_LEFT_DIR   = AxisDirection(NeutralAxis.X, -1)
        private val DPAD_RIGHT_DIR  = AxisDirection(NeutralAxis.X,  1)

        /** Returns true when [direction] is one of the four standard d-pad axis directions. */
        private fun isDpadDirection(direction: AxisDirection): Boolean =
            direction == DPAD_UP_DIR || direction == DPAD_DOWN_DIR ||
            direction == DPAD_LEFT_DIR || direction == DPAD_RIGHT_DIR

        /**
         * Extract d-pad bindings from [axisDirections]. Returns null when the four
         * standard d-pad directions are not all present (fallback to half-axis).
         */
        private fun mapDpadAxisDirections(
            axisDirections: Map<AxisDirection, LogicalControl>
        ): DpadBindings? {
            val up     = axisDirections[DPAD_UP_DIR]
            val down   = axisDirections[DPAD_DOWN_DIR]
            val left   = axisDirections[DPAD_LEFT_DIR]
            val right  = axisDirections[DPAD_RIGHT_DIR]
            if (up == null || down == null || left == null || right == null) return null
            if (up != LogicalControl.DPAD_UP     || down != LogicalControl.DPAD_DOWN ||
                left != LogicalControl.DPAD_LEFT || right != LogicalControl.DPAD_RIGHT) {
                return null // non-standard d-pad targets → fall back to half-axis
            }
            return DpadBindings(up, down, left, right)
        }

        private data class DpadBindings(
            val up: LogicalControl,
            val down: LogicalControl,
            val left: LogicalControl,
            val right: LogicalControl
        )

        /**
         * Apply 8-zone angle-based d-pad mapping. Deadzoned X/Y are derived from
         * the per-direction AxisConfig; atan2 determines the active zone.
         */
        private fun applyAngleBasedDpad(
            buttons: FloatArray,
            axisValues: Map<NeutralAxis, Float>,
            mapping: ControllerMapping,
            dpad: DpadBindings
        ) {
            // Obtain raw axis values (NeutralAxis.X and NeutralAxis.Y).
            val rawX = axisValues[NeutralAxis.X] ?: return
            val rawY = axisValues[NeutralAxis.Y] ?: return

            // Apply deadzone/inversion using the config of the RIGHT (+1 X) and UP (-1 Y)
            // bindings. If those specific configs are absent, fall back to the opposite
            // polarity's config, then to a default AxisConfig.
            val xConfig = mapping.axisConfigs[dpad.right]
                ?: mapping.axisConfigs[dpad.left]
                ?: AxisConfig.DEFAULT
            val yConfig = mapping.axisConfigs[dpad.up]
                ?: mapping.axisConfigs[dpad.down]
                ?: AxisConfig.DEFAULT

            val dx = xConfig.apply(rawX)
            val dy = yConfig.apply(rawY)

            // If both axes are within deadzone, no d-pad input.
            if (dx == 0f && dy == 0f) return

            // atan2(y, x): 0° = +X (right), increases CCW → 90°=+Y (up).
            // Y is screen-coordinates (positive = down), so we negate dy to convert
            // to standard math coordinates where positive Y = up.
            val rawAngleRad = atan2((-dy).toDouble(), dx.toDouble())
            val rawAngleDeg = rawAngleRad * (180.0 / PI)
            val angleDegF = rawAngleDeg.toFloat()
            val angleDeg = if (angleDegF < 0f) angleDegF + 360f else angleDegF

            val h = DIAGONAL_ZONE_HALF_WIDTH_DEGREES // 22.5°

            when {
                angleDeg >= 360f - h || angleDeg < h -> {
                    // RIGHT zone: [337.5, 360) ∪ [0, 22.5)
                    buttons[dpad.right.index] = 1.0f
                }
                angleDeg >= h && angleDeg < 90f - h -> {
                    // UP-RIGHT: [22.5, 67.5)
                    buttons[dpad.up.index] = 1.0f
                    buttons[dpad.right.index] = 1.0f
                }
                angleDeg >= 90f - h && angleDeg < 90f + h -> {
                    // UP: [67.5, 112.5)
                    buttons[dpad.up.index] = 1.0f
                }
                angleDeg >= 90f + h && angleDeg < 180f - h -> {
                    // UP-LEFT: [112.5, 157.5)
                    buttons[dpad.up.index] = 1.0f
                    buttons[dpad.left.index] = 1.0f
                }
                angleDeg >= 180f - h && angleDeg < 180f + h -> {
                    // LEFT: [157.5, 202.5)
                    buttons[dpad.left.index] = 1.0f
                }
                angleDeg >= 180f + h && angleDeg < 270f - h -> {
                    // DOWN-LEFT: [202.5, 247.5)
                    buttons[dpad.down.index] = 1.0f
                    buttons[dpad.left.index] = 1.0f
                }
                angleDeg >= 270f - h && angleDeg < 270f + h -> {
                    // DOWN: [247.5, 292.5)
                    buttons[dpad.down.index] = 1.0f
                }
                else -> {
                    // DOWN-RIGHT: [292.5, 337.5)
                    buttons[dpad.down.index] = 1.0f
                    buttons[dpad.right.index] = 1.0f
                }
            }
        }
    }
}

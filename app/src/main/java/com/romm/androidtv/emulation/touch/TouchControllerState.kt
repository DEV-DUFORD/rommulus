package com.romm.androidtv.emulation.touch

import com.romm.androidtv.controller.model.GamepadSnapshot
import com.romm.androidtv.controller.model.LogicalControl

/**
 * Holds player-one touch input state and produces immutable [GamepadSnapshot] values.
 *
 * Touch inputs are mapped onto the same [LogicalControl] index space as physical
 * gamepad controls, allowing [TouchInputMerger] to combine both sources into a
 * single snapshot.
 */
class TouchControllerState {

    private val buttons = FloatArray(16)
    private val axes = FloatArray(6)

    /**
     * Set a logical button to pressed or released.
     *
     * @param logical the logical control index (must be a BUTTON-type control)
     * @param pressed true to press, false to release
     */
    fun setButton(logical: LogicalControl, pressed: Boolean) {
        buttons[logical.index] = if (pressed) 1f else 0f
    }

    /**
     * Set a logical axis to a value in [-1f, 1f].
     *
     * @param logical the logical control index (must be an AXIS-type control)
     * @param value the axis value, clamped to [-1f, 1f]
     */
    fun setAxis(logical: LogicalControl, value: Float) {
        axes[logical.index] = value.coerceIn(-1f, 1f)
    }

    /**
     * Produce an immutable snapshot of the current touch state.
     */
    fun snapshot(): GamepadSnapshot = GamepadSnapshot(
        buttons = buttons.copyOf(),
        axes = axes.copyOf()
    )

    /** Reset all buttons and axes to their rest values. */
    fun reset() {
        buttons.fill(0f)
        axes.fill(0f)
    }
}

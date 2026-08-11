package com.romm.androidtv.emulation.touch

import com.romm.androidtv.controller.model.GamepadSnapshot

/**
 * Pure merger that combines a physical [GamepadSnapshot] (port 0) with a
 * touch [GamepadSnapshot] into a single unified snapshot.
 *
 * Merge rules:
 * - **Buttons**: A button is pressed if *either* source has it pressed (logical OR).
 * - **Axes**: If the touch axis exceeds the deadzone, the touch value wins.
 *   Otherwise the physical axis value is used (touch acts as an override, not a replacement).
 */
object TouchInputMerger {

    /** Touch axes below this magnitude are treated as zero and fall through to physical. */
    const val AXIS_DEADZONE = 0.2f

    /**
     * Merge a physical snapshot with a touch snapshot.
     *
     * @param physical the physical gamepad snapshot (port 0)
     * @param touch the touch controller snapshot
     * @return a new snapshot with merged button/axis values
     */
    fun merge(physical: GamepadSnapshot, touch: GamepadSnapshot): GamepadSnapshot {
        val buttons = FloatArray(16)
        val axes = FloatArray(6)

        // Buttons: logical OR — pressed if either source has it pressed
        for (i in 0 until 16) {
            buttons[i] = if (physical.buttons[i] == 1f || touch.buttons[i] == 1f) 1f else 0f
        }

        // Axes: touch overrides physical only when it exceeds the deadzone
        for (i in 0 until 6) {
            axes[i] = if (kotlin.math.abs(touch.axes[i]) > AXIS_DEADZONE) {
                touch.axes[i]
            } else {
                physical.axes[i]
            }
        }

        return GamepadSnapshot(buttons = buttons, axes = axes)
    }
}

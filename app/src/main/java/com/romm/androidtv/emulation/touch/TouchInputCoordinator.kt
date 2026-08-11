package com.romm.androidtv.emulation.touch

import com.romm.androidtv.controller.LibretroPadMapper
import com.romm.androidtv.controller.LibretroPadState
import com.romm.androidtv.controller.model.ControllerSlot
import com.romm.androidtv.controller.model.GamepadSnapshot
import com.romm.androidtv.controller.model.LogicalControl

/**
 * Coordinates the two producers of player-one input — the physical controller
 * path ([onPhysicalPorts], fed by [com.romm.androidtv.controller.LibretroInputAdapter])
 * and the on-screen touch controller ([onTouchButton]/[onTouchAxis]) — so neither
 * overwrites the other when both are active.
 *
 * Touch input is merged into port 0 only: ports 1-3 pass through unchanged. Each
 * physical port update and each touch update recomputes the final merged state and
 * pushes it to the native host via [nativeUpdate], which is expected to call
 * [com.romm.androidtv.emulation.nativehost.NativeLibretroHost.nativeUpdateInputState].
 *
 * The coordinator never pushes while [routingEnabled] is false. The caller gates
 * this from the gameplay-active policy (paused / configuration overlays / save-failure
 * UI all disable routing) and calls [resetTouch] when a blocking overlay opens, the
 * activity stops or loses focus, or touch controls are disabled, so a stuck touch
 * button can never leak into the core.
 */
class TouchInputCoordinator(
    private val nativeUpdate: (buttonMasks: IntArray, analogValues: IntArray) -> Unit,
) {
    private val touchState = TouchControllerState()
    private var physicalPorts: List<LibretroPadState> =
        List(ControllerSlot.SLOT_COUNT) { LibretroPadState.NEUTRAL }
    private var routingEnabled = false

    /**
     * Enables or disables native pushes. On the transition back to enabled, immediately
     * pushes the current merged state so a freshly-resumed session starts clean rather
     * than waiting for the next physical/touch event.
     */
    fun setRoutingEnabled(enabled: Boolean) {
        val wasEnabled = routingEnabled
        routingEnabled = enabled
        if (!wasEnabled && enabled) push()
    }

    /** Physical controller slots update (from [com.romm.androidtv.controller.LibretroInputAdapter]). */
    fun onPhysicalPorts(ports: List<LibretroPadState>) {
        physicalPorts = ports
        push()
    }

    /** A touch button was pressed (true) or released (false). */
    fun onTouchButton(logical: LogicalControl, pressed: Boolean) {
        touchState.setButton(logical, pressed)
        push()
    }

    /** A touch axis value changed (already normalized to [-1f, 1f]). */
    fun onTouchAxis(logical: LogicalControl, value: Float) {
        touchState.setAxis(logical, value)
        push()
    }

    /** Clears all touch state and pushes the resulting (physical-only) state. */
    fun resetTouch() {
        touchState.reset()
        push()
    }

    private fun push() {
        if (!routingEnabled) return
        val ports = physicalPorts

        // Merge touch into port 0 only: convert port 0's LibretroPadState back to a
        // GamepadSnapshot, merge with the touch snapshot, then convert forward again.
        val port0 = ports.getOrElse(0) { LibretroPadState.NEUTRAL }
        val mergedPort0 = LibretroPadMapper.map(
            TouchInputMerger.merge(port0.toGamepadSnapshot(), touchState.snapshot())
        )

        val buttonMasks = IntArray(ControllerSlot.SLOT_COUNT)
        val analogValues = IntArray(ControllerSlot.SLOT_COUNT * 4)
        for (port in 0 until ControllerSlot.SLOT_COUNT) {
            val state = if (port == 0) {
                mergedPort0
            } else {
                ports.getOrElse(port) { LibretroPadState.NEUTRAL }
            }
            buttonMasks[port] = state.buttonsMask
            analogValues[port * 4 + 0] = state.leftX
            analogValues[port * 4 + 1] = state.leftY
            analogValues[port * 4 + 2] = state.rightX
            analogValues[port * 4 + 3] = state.rightY
        }
        nativeUpdate(buttonMasks, analogValues)
    }
}

/** RetroPad bit position -> [LogicalControl], inverse of [LibretroPadMapper]'s press() calls. */
private val JOYPAD_BIT_TO_CONTROL: Map<Int, LogicalControl> = mapOf(
    0 to LogicalControl.BUTTON_B,
    1 to LogicalControl.BUTTON_Y,
    2 to LogicalControl.BUTTON_SELECT,
    3 to LogicalControl.BUTTON_START,
    4 to LogicalControl.DPAD_UP,
    5 to LogicalControl.DPAD_DOWN,
    6 to LogicalControl.DPAD_LEFT,
    7 to LogicalControl.DPAD_RIGHT,
    8 to LogicalControl.BUTTON_A,
    9 to LogicalControl.BUTTON_X,
    10 to LogicalControl.BUTTON_LB,
    11 to LogicalControl.BUTTON_RB,
    12 to LogicalControl.BUTTON_LT,
    13 to LogicalControl.BUTTON_RT,
    14 to LogicalControl.BUTTON_L3,
    15 to LogicalControl.BUTTON_R3,
)

/** Inverse of [LibretroPadMapper]: a [LibretroPadState] back into a [GamepadSnapshot]. */
private fun LibretroPadState.toGamepadSnapshot(): GamepadSnapshot {
    val buttons = FloatArray(16)
    var mask = buttonsMask
    var bit = 0
    while (mask != 0) {
        if (mask and 1 == 1) {
            JOYPAD_BIT_TO_CONTROL[bit]?.let { buttons[it.index] = 1f }
        }
        mask = mask ushr 1
        bit++
    }
    val axes = FloatArray(6)
    axes[LogicalControl.AXIS_LX.index] = analogToFloat(leftX)
    axes[LogicalControl.AXIS_LY.index] = analogToFloat(leftY)
    axes[LogicalControl.AXIS_RX.index] = analogToFloat(rightX)
    axes[LogicalControl.AXIS_RY.index] = analogToFloat(rightY)
    return GamepadSnapshot(buttons = buttons, axes = axes)
}

private fun analogToFloat(value: Int): Float =
    (value / 32767f).coerceIn(-1f, 1f)

package com.romm.androidtv.controller.model

/**
 * Standard logical gamepad controls, console-agnostic.
 * Mirrors the Gamepad API button/axis layout expected by RomM and EmulatorJS.
 */
enum class LogicalControl(
    val type: Type,
    val index: Int
) {
    // Buttons (W3C Gamepad API standard indices)
    BUTTON_A(Type.BUTTON, 0),
    BUTTON_B(Type.BUTTON, 1),
    BUTTON_X(Type.BUTTON, 2),
    BUTTON_Y(Type.BUTTON, 3),
    BUTTON_LB(Type.BUTTON, 4),
    BUTTON_RB(Type.BUTTON, 5),
    BUTTON_LT(Type.BUTTON, 6),
    BUTTON_RT(Type.BUTTON, 7),
    BUTTON_SELECT(Type.BUTTON, 8),
    BUTTON_START(Type.BUTTON, 9),
    BUTTON_L3(Type.BUTTON, 10),
    BUTTON_R3(Type.BUTTON, 11),

    // D-pad buttons
    DPAD_UP(Type.BUTTON, 12),
    DPAD_DOWN(Type.BUTTON, 13),
    DPAD_LEFT(Type.BUTTON, 14),
    DPAD_RIGHT(Type.BUTTON, 15),

    // Axes (GamepadAPI axis indices)
    AXIS_LX(Type.AXIS, 0),
    AXIS_LY(Type.AXIS, 1),
    AXIS_RX(Type.AXIS, 2),
    AXIS_RY(Type.AXIS, 3),

    // Triggers as axes (some controllers expose triggers as axes)
    TRIGGER_LEFT(Type.AXIS, 4),
    TRIGGER_RIGHT(Type.AXIS, 5);

    enum class Type { BUTTON, AXIS }
}

/**
 * Neutral [NeutralKey] mapped to a logical control.
 * Covers the standard set recognized for gamepads.
 */
val NEUTRAL_KEY_TO_CONTROL: Map<NeutralKey, LogicalControl> = mapOf(
    NeutralKey.BUTTON_A to LogicalControl.BUTTON_A,
    NeutralKey.BUTTON_B to LogicalControl.BUTTON_B,
    NeutralKey.BUTTON_X to LogicalControl.BUTTON_X,
    NeutralKey.BUTTON_Y to LogicalControl.BUTTON_Y,
    NeutralKey.BUTTON_L1 to LogicalControl.BUTTON_LB,
    NeutralKey.BUTTON_R1 to LogicalControl.BUTTON_RB,
    NeutralKey.BUTTON_SELECT to LogicalControl.BUTTON_SELECT,
    NeutralKey.BUTTON_START to LogicalControl.BUTTON_START,
    NeutralKey.BUTTON_THUMBL to LogicalControl.BUTTON_L3,
    NeutralKey.BUTTON_THUMBR to LogicalControl.BUTTON_R3,
    // D-pad keys may come from a remote or a controller; context determines handling.
    NeutralKey.DPAD_UP to LogicalControl.DPAD_UP,
    NeutralKey.DPAD_DOWN to LogicalControl.DPAD_DOWN,
    NeutralKey.DPAD_LEFT to LogicalControl.DPAD_LEFT,
    NeutralKey.DPAD_RIGHT to LogicalControl.DPAD_RIGHT
)

/**
 * Neutral [NeutralAxis] mapped to a logical control.
 *
 * Priority order for right stick:
 * 1. AXIS_RX / AXIS_RY (standard Xbox/PS layout)
 * 2. AXIS_Z / AXIS_RZ (fallback for older/alternate devices)
 *
 * This ensures device-capability-based mappings use actual InputDevice
 * MotionRanges correctly for both common and fallback axis layouts.
 */
val NEUTRAL_AXIS_TO_CONTROL: Map<NeutralAxis, LogicalControl> = mapOf(
    NeutralAxis.X to LogicalControl.AXIS_LX,
    NeutralAxis.Y to LogicalControl.AXIS_LY,
    NeutralAxis.RX to LogicalControl.AXIS_RX,
    NeutralAxis.RY to LogicalControl.AXIS_RY,
    NeutralAxis.Z to LogicalControl.AXIS_RX,
    NeutralAxis.RZ to LogicalControl.AXIS_RY,
    NeutralAxis.LTRIGGER to LogicalControl.TRIGGER_LEFT,
    NeutralAxis.RTRIGGER to LogicalControl.TRIGGER_RIGHT,
    NeutralAxis.BRAKE to LogicalControl.TRIGGER_LEFT,
    NeutralAxis.GAS to LogicalControl.TRIGGER_RIGHT
)

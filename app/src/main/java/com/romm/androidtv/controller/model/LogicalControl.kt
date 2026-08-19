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
 * Android KeyEvent keyCode mapped to a logical control.
 * Covers the standard set recognized by Android for gamepads.
 */
val KEYCODE_TO_CONTROL: Map<Int, LogicalControl> = mapOf(
    android.view.KeyEvent.KEYCODE_BUTTON_A to LogicalControl.BUTTON_A,
    android.view.KeyEvent.KEYCODE_BUTTON_B to LogicalControl.BUTTON_B,
    android.view.KeyEvent.KEYCODE_BUTTON_X to LogicalControl.BUTTON_X,
    android.view.KeyEvent.KEYCODE_BUTTON_Y to LogicalControl.BUTTON_Y,
    android.view.KeyEvent.KEYCODE_BUTTON_L1 to LogicalControl.BUTTON_LB,
    android.view.KeyEvent.KEYCODE_BUTTON_R1 to LogicalControl.BUTTON_RB,
    // Some controllers report their triggers as key events while others report
    // them as motion axes. Keep both paths routable so custom mappings (for
    // example, GBA A on L2) work regardless of the controller's report type.
    android.view.KeyEvent.KEYCODE_BUTTON_L2 to LogicalControl.BUTTON_LT,
    android.view.KeyEvent.KEYCODE_BUTTON_R2 to LogicalControl.BUTTON_RT,
    android.view.KeyEvent.KEYCODE_BUTTON_SELECT to LogicalControl.BUTTON_SELECT,
    android.view.KeyEvent.KEYCODE_BUTTON_START to LogicalControl.BUTTON_START,
    android.view.KeyEvent.KEYCODE_BUTTON_THUMBL to LogicalControl.BUTTON_L3,
    android.view.KeyEvent.KEYCODE_BUTTON_THUMBR to LogicalControl.BUTTON_R3,
    // D-pad keys may come from a remote or a controller; context determines handling.
    android.view.KeyEvent.KEYCODE_DPAD_UP to LogicalControl.DPAD_UP,
    android.view.KeyEvent.KEYCODE_DPAD_DOWN to LogicalControl.DPAD_DOWN,
    android.view.KeyEvent.KEYCODE_DPAD_LEFT to LogicalControl.DPAD_LEFT,
    android.view.KeyEvent.KEYCODE_DPAD_RIGHT to LogicalControl.DPAD_RIGHT
)

/**
 * Android MotionEvent axis constant mapped to a logical control.
 *
 * Priority order for right stick:
 * 1. AXIS_RX / AXIS_RY (standard Xbox/PS layout)
 * 2. AXIS_Z / AXIS_RZ (fallback for older/alternate devices)
 *
 * This ensures device-capability-based mappings use actual InputDevice
 * MotionRanges correctly for both common and fallback axis layouts.
 */
val AXIS_TO_CONTROL: Map<Int, LogicalControl> = mapOf(
    android.view.MotionEvent.AXIS_X to LogicalControl.AXIS_LX,
    android.view.MotionEvent.AXIS_Y to LogicalControl.AXIS_LY,
    android.view.MotionEvent.AXIS_RX to LogicalControl.AXIS_RX,
    android.view.MotionEvent.AXIS_RY to LogicalControl.AXIS_RY,
    android.view.MotionEvent.AXIS_Z to LogicalControl.AXIS_RX,
    android.view.MotionEvent.AXIS_RZ to LogicalControl.AXIS_RY,
    android.view.MotionEvent.AXIS_LTRIGGER to LogicalControl.TRIGGER_LEFT,
    android.view.MotionEvent.AXIS_RTRIGGER to LogicalControl.TRIGGER_RIGHT,
    android.view.MotionEvent.AXIS_BRAKE to LogicalControl.TRIGGER_LEFT,
    android.view.MotionEvent.AXIS_GAS to LogicalControl.TRIGGER_RIGHT
)

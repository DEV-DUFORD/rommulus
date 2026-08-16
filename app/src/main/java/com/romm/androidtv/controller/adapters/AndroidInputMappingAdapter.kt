package com.romm.androidtv.controller.adapters

import android.view.KeyEvent
import android.view.MotionEvent
import com.romm.androidtv.controller.model.LogicalControl

/**
 * Android-constant-keyed physical-to-logical maps.
 *
 * These map raw Android key codes / axis constants directly to [LogicalControl]s
 * and live in `:app` because they reference Android framework constants. The
 * shared model uses the platform-neutral [com.romm.androidtv.controller.model.NEUTRAL_KEY_TO_CONTROL] /
 * [com.romm.androidtv.controller.model.NEUTRAL_AXIS_TO_CONTROL] maps instead; this
 * adapter exists for call sites and tests that still need the Android-keyed form.
 */
val KEYCODE_TO_CONTROL: Map<Int, LogicalControl> = mapOf(
    KeyEvent.KEYCODE_BUTTON_A to LogicalControl.BUTTON_A,
    KeyEvent.KEYCODE_BUTTON_B to LogicalControl.BUTTON_B,
    KeyEvent.KEYCODE_BUTTON_X to LogicalControl.BUTTON_X,
    KeyEvent.KEYCODE_BUTTON_Y to LogicalControl.BUTTON_Y,
    KeyEvent.KEYCODE_BUTTON_L1 to LogicalControl.BUTTON_LB,
    KeyEvent.KEYCODE_BUTTON_R1 to LogicalControl.BUTTON_RB,
    KeyEvent.KEYCODE_BUTTON_SELECT to LogicalControl.BUTTON_SELECT,
    KeyEvent.KEYCODE_BUTTON_START to LogicalControl.BUTTON_START,
    KeyEvent.KEYCODE_BUTTON_THUMBL to LogicalControl.BUTTON_L3,
    KeyEvent.KEYCODE_BUTTON_THUMBR to LogicalControl.BUTTON_R3,
    // D-pad keys may come from a remote or a controller; context determines handling.
    KeyEvent.KEYCODE_DPAD_UP to LogicalControl.DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN to LogicalControl.DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT to LogicalControl.DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT to LogicalControl.DPAD_RIGHT
)

/**
 * Android MotionEvent axis constant -> logical control map.
 */
val AXIS_TO_CONTROL: Map<Int, LogicalControl> = mapOf(
    MotionEvent.AXIS_X to LogicalControl.AXIS_LX,
    MotionEvent.AXIS_Y to LogicalControl.AXIS_LY,
    MotionEvent.AXIS_RX to LogicalControl.AXIS_RX,
    MotionEvent.AXIS_RY to LogicalControl.AXIS_RY,
    MotionEvent.AXIS_Z to LogicalControl.AXIS_RX,
    MotionEvent.AXIS_RZ to LogicalControl.AXIS_RY,
    MotionEvent.AXIS_LTRIGGER to LogicalControl.TRIGGER_LEFT,
    MotionEvent.AXIS_RTRIGGER to LogicalControl.TRIGGER_RIGHT,
    MotionEvent.AXIS_BRAKE to LogicalControl.TRIGGER_LEFT,
    MotionEvent.AXIS_GAS to LogicalControl.TRIGGER_RIGHT
)

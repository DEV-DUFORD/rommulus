package com.romm.androidtv.controller.config

/**
 * Pure, framework-free formatter that produces human-readable labels for [PhysicalBinding] values.
 *
 * Labels are for display only and are never persisted. References to Android
 * [android.view.KeyEvent.KEYCODE_*] and [android.view.MotionEvent.AXIS_*] constants
 * are compile-time integer literals that get inlined, so this object is fully
 * unit-testable on the JVM without an Android runtime.
 */
object BindingLabelFormatter {

    @Suppress("MagicNumber")
    private val KEY_LABELS: Map<Int, String> = mapOf(
        android.view.KeyEvent.KEYCODE_BUTTON_A to "Button A",
        android.view.KeyEvent.KEYCODE_BUTTON_B to "Button B",
        android.view.KeyEvent.KEYCODE_BUTTON_X to "Button X",
        android.view.KeyEvent.KEYCODE_BUTTON_Y to "Button Y",
        android.view.KeyEvent.KEYCODE_BUTTON_L1 to "L1",
        android.view.KeyEvent.KEYCODE_BUTTON_R1 to "R1",
        android.view.KeyEvent.KEYCODE_BUTTON_L2 to "L2",
        android.view.KeyEvent.KEYCODE_BUTTON_R2 to "R2",
        android.view.KeyEvent.KEYCODE_BUTTON_SELECT to "Select",
        android.view.KeyEvent.KEYCODE_BUTTON_START to "Start",
        android.view.KeyEvent.KEYCODE_BUTTON_THUMBL to "L3",
        android.view.KeyEvent.KEYCODE_BUTTON_THUMBR to "R3",
        android.view.KeyEvent.KEYCODE_BUTTON_MODE to "Mode",
        android.view.KeyEvent.KEYCODE_DPAD_UP to "D-Pad Up",
        android.view.KeyEvent.KEYCODE_DPAD_DOWN to "D-Pad Down",
        android.view.KeyEvent.KEYCODE_DPAD_LEFT to "D-Pad Left",
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT to "D-Pad Right",
        android.view.KeyEvent.KEYCODE_ENTER to "Enter",
        android.view.KeyEvent.KEYCODE_DPAD_CENTER to "D-Pad Center",
    )

    @Suppress("MagicNumber")
    private val AXIS_LABELS: Map<Int, String> = mapOf(
        android.view.MotionEvent.AXIS_X to "Left Stick X",
        android.view.MotionEvent.AXIS_Y to "Left Stick Y",
        android.view.MotionEvent.AXIS_RX to "Right Stick X",
        android.view.MotionEvent.AXIS_RY to "Right Stick Y",
        android.view.MotionEvent.AXIS_Z to "Right Stick X",
        android.view.MotionEvent.AXIS_RZ to "Right Stick Y",
        android.view.MotionEvent.AXIS_LTRIGGER to "Left Trigger",
        android.view.MotionEvent.AXIS_RTRIGGER to "Right Trigger",
        android.view.MotionEvent.AXIS_BRAKE to "Left Trigger",
        android.view.MotionEvent.AXIS_GAS to "Right Trigger",
    )

    @Suppress("MagicNumber")
    private val AXIS_DIRECTIONS: Map<Int, Map<Int, String>> = mapOf(
        android.view.MotionEvent.AXIS_X to mapOf(
            1 to "Left Stick Right",
            -1 to "Left Stick Left",
        ),
        android.view.MotionEvent.AXIS_Y to mapOf(
            1 to "Left Stick Down",
            -1 to "Left Stick Up",
        ),
        android.view.MotionEvent.AXIS_RX to mapOf(
            1 to "Right Stick Right",
            -1 to "Right Stick Left",
        ),
        android.view.MotionEvent.AXIS_RY to mapOf(
            1 to "Right Stick Down",
            -1 to "Right Stick Up",
        ),
        android.view.MotionEvent.AXIS_Z to mapOf(
            1 to "Right Stick Right",
            -1 to "Right Stick Left",
        ),
        android.view.MotionEvent.AXIS_RZ to mapOf(
            1 to "Right Stick Down",
            -1 to "Right Stick Up",
        ),
    )

    fun label(binding: PhysicalBinding): String = when (binding) {
        is PhysicalBinding.Key -> KEY_LABELS[binding.keyCode] ?: "Key ${binding.keyCode}"
        is PhysicalBinding.Axis -> AXIS_LABELS[binding.axis] ?: "Axis ${binding.axis}"
        is PhysicalBinding.AxisDirection -> {
            val directionLabel = AXIS_DIRECTIONS[binding.axis]?.get(binding.polarity)
            if (directionLabel != null) {
                directionLabel
            } else {
                // Triggers keep base name for either polarity
                val baseLabel = AXIS_LABELS[binding.axis] ?: "Axis ${binding.axis}"
                baseLabel
            }
        }
    }
}

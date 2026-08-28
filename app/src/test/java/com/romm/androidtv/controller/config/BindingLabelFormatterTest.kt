package com.romm.androidtv.controller.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("BindingLabelFormatter — display labels")
class BindingLabelFormatterTest {

    @Nested
    @DisplayName("Key bindings produce named labels")
    inner class KeyLabels {
        @Test
        fun `BUTTON_A produces 'Button A'`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_BUTTON_A)))
                .isEqualTo("Button A")
        }

        @Test
        fun `BUTTON_B produces 'Button B'`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_BUTTON_B)))
                .isEqualTo("Button B")
        }

        @Test
        fun `BUTTON_Z produces a named label`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_BUTTON_Z)))
                .isEqualTo("Button Z")
        }

        @Test
        fun `DPAD_UP produces 'D-Pad Up'`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_DPAD_UP)))
                .isEqualTo("D-Pad Up")
        }

        @Test
        fun `BUTTON_START produces 'Start'`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_BUTTON_START)))
                .isEqualTo("Start")
        }

        @Test
        fun `BUTTON_SELECT produces 'Select'`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_BUTTON_SELECT)))
                .isEqualTo("Select")
        }

        @Test
        fun `THUMBL produces 'L3'`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_BUTTON_THUMBL)))
                .isEqualTo("L3")
        }

        @Test
        fun `THUMBR produces 'R3'`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_BUTTON_THUMBR)))
                .isEqualTo("R3")
        }

        @Test
        fun `BUTTON_MODE produces 'Mode'`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_BUTTON_MODE)))
                .isEqualTo("Mode")
        }

        @Test
        fun `DPAD_CENTER produces 'D-Pad Center'`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)))
                .isEqualTo("D-Pad Center")
        }

        @Test
        fun `ENTER produces 'Enter'`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_ENTER)))
                .isEqualTo("Enter")
        }
    }

    @Nested
    @DisplayName("Axis bindings produce named labels")
    inner class AxisLabels {
        @Test
        fun `AXIS_X produces 'Left Stick X'`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.Axis(android.view.MotionEvent.AXIS_X)))
                .isEqualTo("Left Stick X")
        }

        @Test
        fun `AXIS_Y produces 'Left Stick Y'`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.Axis(android.view.MotionEvent.AXIS_Y)))
                .isEqualTo("Left Stick Y")
        }

        @Test
        fun `AXIS_RX produces 'Right Stick X'`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.Axis(android.view.MotionEvent.AXIS_RX)))
                .isEqualTo("Right Stick X")
        }

        @Test
        fun `AXIS_RY produces 'Right Stick Y'`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.Axis(android.view.MotionEvent.AXIS_RY)))
                .isEqualTo("Right Stick Y")
        }

        @Test
        fun `AXIS_LTRIGGER produces 'Left Trigger'`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.Axis(android.view.MotionEvent.AXIS_LTRIGGER)))
                .isEqualTo("Left Trigger")
        }

        @Test
        fun `AXIS_RTRIGGER produces 'Right Trigger'`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.Axis(android.view.MotionEvent.AXIS_RTRIGGER)))
                .isEqualTo("Right Trigger")
        }

        @Test
        fun `hat axes produce d-pad labels`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.Axis(android.view.MotionEvent.AXIS_HAT_X)))
                .isEqualTo("D-Pad X")
            assertThat(
                BindingLabelFormatter.label(
                    PhysicalBinding.AxisDirection(android.view.MotionEvent.AXIS_HAT_Y, -1),
                ),
            ).isEqualTo("D-Pad Up")
        }
    }

    @Nested
    @DisplayName("AxisDirection bindings produce directional labels")
    inner class AxisDirectionLabels {
        @Test
        fun `AXIS_X +1 produces 'Left Stick Right'`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.AxisDirection(android.view.MotionEvent.AXIS_X, 1)))
                .isEqualTo("Left Stick Right")
        }

        @Test
        fun `AXIS_X -1 produces 'Left Stick Left'`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.AxisDirection(android.view.MotionEvent.AXIS_X, -1)))
                .isEqualTo("Left Stick Left")
        }

        @Test
        fun `AXIS_Y +1 produces 'Left Stick Down'`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.AxisDirection(android.view.MotionEvent.AXIS_Y, 1)))
                .isEqualTo("Left Stick Down")
        }

        @Test
        fun `AXIS_Y -1 produces 'Left Stick Up'`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.AxisDirection(android.view.MotionEvent.AXIS_Y, -1)))
                .isEqualTo("Left Stick Up")
        }

        @Test
        fun `AXIS_RX +1 produces 'Right Stick Right'`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.AxisDirection(android.view.MotionEvent.AXIS_RX, 1)))
                .isEqualTo("Right Stick Right")
        }

        @Test
        fun `AXIS_RY -1 produces 'Right Stick Up'`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.AxisDirection(android.view.MotionEvent.AXIS_RY, -1)))
                .isEqualTo("Right Stick Up")
        }

        @Test
        fun `trigger axis direction keeps base name regardless of polarity`() {
            val leftPos = BindingLabelFormatter.label(
                PhysicalBinding.AxisDirection(android.view.MotionEvent.AXIS_LTRIGGER, 1)
            )
            val leftNeg = BindingLabelFormatter.label(
                PhysicalBinding.AxisDirection(android.view.MotionEvent.AXIS_LTRIGGER, -1)
            )
            assertThat(leftPos).isEqualTo("Left Trigger")
            assertThat(leftNeg).isEqualTo("Left Trigger")
        }
    }

    @Nested
    @DisplayName("Unknown fallback labels")
    inner class FallbackLabels {
        @Test
        fun `unknown keyCode falls back to Key code`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.Key(99999)))
                .isEqualTo("Key 99999")
        }

        @Test
        fun `unknown axis falls back to Axis code`() {
            assertThat(BindingLabelFormatter.label(PhysicalBinding.Axis(-999)))
                .isEqualTo("Axis -999")
        }
    }
}

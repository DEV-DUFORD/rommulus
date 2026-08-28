package com.romm.androidtv.controller.capture

import android.view.InputDevice
import android.view.KeyEvent
import com.romm.androidtv.controller.capture.ControllerBindingCaptureCoordinator.Companion.ENTER_THRESHOLD
import com.romm.androidtv.controller.capture.ControllerBindingCaptureCoordinator.Companion.NEUTRAL_THRESHOLD
import com.romm.androidtv.controller.config.PhysicalBinding
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Pure state-machine tests for [ControllerBindingCaptureCoordinator].
 *
 * Uses the coordinator's sample seams ([ControllerBindingCaptureCoordinator.onKeySample]
 * / `onAxisSample`) because no Robolectric is used in this repo and
 * `MotionEvent` cannot be constructed on the JVM. `KeyEvent` is constructible,
 * so key-path tests go through the pure [ControllerBindingCaptureCoordinator.onKeySample]
 * seam (KeyEvent getters are not mocked on the JVM).
 * The timeout is exercised with `kotlinx-coroutines-test` virtual time.
 */
@DisplayName("ControllerBindingCaptureCoordinator — capture state machine")
class ControllerBindingCaptureCoordinatorTest {

    private val gamepadDevice = 11
    private val otherGamepadDevice = 22
    private val remoteDevice = 33

    private fun sourcesOf(vararg ids: Pair<Int, Int>): Map<Int, Int> = mapOf(*ids)

    private fun TestScope.coordinator(
        sources: Map<Int, Int> = sourcesOf(gamepadDevice to InputDevice.SOURCE_GAMEPAD),
        timeoutMillis: Long = ControllerBindingCaptureCoordinator.DEFAULT_TIMEOUT_MILLIS,
    ) = ControllerBindingCaptureCoordinator(
        scope = this,
        timeoutMillis = timeoutMillis,
        sourceProvider = { sources[it] ?: 0 }
    )

    /** [KeyEvent] methods are not mocked on the JVM, so tests drive [ControllerBindingCaptureCoordinator.onKeySample] directly. */
    private val ACTION_DOWN = KeyEvent.ACTION_DOWN
    private val ACTION_UP = KeyEvent.ACTION_UP

    @Nested
    @DisplayName("Neutral gating")
    inner class NeutralGating {

        @Test
        @DisplayName("a held press that opened the row is not captured until released")
        fun `rowOpeningPressNotCaptured`() = runTest {
            val c = coordinator()
            c.beginCapture(0, gamepadDevice, CaptureTarget.Digital)

            // Press still held from opening the row.
            assertThat(c.onKeySample(gamepadDevice, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
                .isEqualTo(true)
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.AwaitingNeutral)

            // Release -> neutral -> capturing.
            assertThat(c.onKeySample(gamepadDevice, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
                .isEqualTo(true)
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.Capturing)

            // Next fresh press is captured.
            assertThat(c.onKeySample(gamepadDevice, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B))
                .isEqualTo(true)
            assertThat(c.state.value)
                .isEqualTo(ControllerBindingCaptureState.Result(PhysicalBinding.Key(KeyEvent.KEYCODE_BUTTON_B)))
        }

        @Test
        @DisplayName("capture arms automatically when no assigned-device input is held")
        fun `idleDeviceArmsWithoutSacrificialPress`() = runTest {
            val c = coordinator()
            c.beginCapture(0, gamepadDevice, CaptureTarget.Digital)

            runCurrent()

            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.Capturing)
            c.onKeySample(gamepadDevice, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)
            assertThat(c.state.value)
                .isEqualTo(ControllerBindingCaptureState.Result(PhysicalBinding.Key(KeyEvent.KEYCODE_BUTTON_B)))
        }

        @Test
        @DisplayName("a non-neutral stick keeps the capture waiting")
        fun `stickOutOfCenterWaits`() = runTest {
            val c = coordinator()
            c.beginCapture(0, gamepadDevice, CaptureTarget.Analog)

            c.onAxisSample(gamepadDevice, android.view.MotionEvent.AXIS_X, 0.9f)
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.AwaitingNeutral)

            c.onAxisSample(gamepadDevice, android.view.MotionEvent.AXIS_X, 0.0f)
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.Capturing)
        }
    }

    @Nested
    @DisplayName("Key capture")
    inner class KeyCapture {

        @Test
        @DisplayName("captures the first new gamepad ACTION_DOWN as a Key")
        fun `capturesFirstDownAsKey`() = runTest {
            val c = coordinator()
            c.beginCapture(0, gamepadDevice, CaptureTarget.Digital)
            c.onKeySample(gamepadDevice, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)

            assertThat(c.onKeySample(gamepadDevice, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_Y))
                .isEqualTo(true)
            assertThat(c.state.value)
                .isEqualTo(ControllerBindingCaptureState.Result(PhysicalBinding.Key(KeyEvent.KEYCODE_BUTTON_Y)))
        }

        @Test
        @DisplayName("captures L2 and R2 key-event triggers for a button target")
        fun `capturesKeyEventTriggers`() = runTest {
            val c = coordinator()
            c.beginCapture(0, gamepadDevice, CaptureTarget.Digital)
            c.onKeySample(gamepadDevice, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)

            c.onKeySample(gamepadDevice, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L2)

            assertThat(c.state.value).isEqualTo(
                ControllerBindingCaptureState.Result(
                    PhysicalBinding.Key(KeyEvent.KEYCODE_BUTTON_L2),
                ),
            )
        }

        @Test
        @DisplayName("trigger targets accept a digital shoulder button")
        fun `triggerTargetCapturesDigitalButton`() = runTest {
            val c = coordinator()
            c.beginCapture(0, gamepadDevice, CaptureTarget.Trigger)
            runCurrent()

            c.onKeySample(gamepadDevice, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L1)

            assertThat(c.state.value).isEqualTo(
                ControllerBindingCaptureState.Result(
                    PhysicalBinding.Key(KeyEvent.KEYCODE_BUTTON_L1),
                ),
            )
        }

        @Test
        @DisplayName("repeats are suppressed and never captured")
        fun `repeatsSuppressed`() = runTest {
            val c = coordinator()
            c.beginCapture(0, gamepadDevice, CaptureTarget.Digital)
            c.onKeySample(gamepadDevice, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)

            assertThat(
                c.onKeySample(gamepadDevice, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_X, repeatCount = 5)
            ).isEqualTo(true)
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.Capturing)

            assertThat(
                c.onKeySample(gamepadDevice, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_X, repeatCount = 0)
            ).isEqualTo(true)
            assertThat(c.state.value)
                .isEqualTo(ControllerBindingCaptureState.Result(PhysicalBinding.Key(KeyEvent.KEYCODE_BUTTON_X)))
        }

        @Test
        @DisplayName("a key press is ignored for an analog target")
        fun `keysIgnoredForAnalogTarget`() = runTest {
            val c = coordinator()
            c.beginCapture(0, gamepadDevice, CaptureTarget.Analog)
            c.onKeySample(gamepadDevice, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)

            assertThat(c.onKeySample(gamepadDevice, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
                .isEqualTo(true)
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.Capturing)
        }
    }

    @Nested
    @DisplayName("Axis capture")
    inner class AxisCapture {

        @Test
        @DisplayName("analog target requires neutral then enter-threshold crossing, returns full Axis")
        fun `analogHysteresis`() = runTest {
            val c = coordinator()
            c.beginCapture(0, gamepadDevice, CaptureTarget.Analog)
            val axis = android.view.MotionEvent.AXIS_X

            c.onAxisSample(gamepadDevice, axis, 0.0f) // neutral -> capturing
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.Capturing)

            // Below enter threshold: not yet captured.
            assertThat(c.onAxisSample(gamepadDevice, axis, 0.3f)).isEqualTo(true)
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.Capturing)

            assertThat(c.onAxisSample(gamepadDevice, axis, 0.8f)).isEqualTo(true)
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.Result(PhysicalBinding.Axis(axis)))
        }

        @Test
        @DisplayName("analog target rejects a noisy axis that never returned to neutral")
        fun `noisyAxisRejected`() = runTest {
            val c = coordinator()
            c.beginCapture(0, gamepadDevice, CaptureTarget.Analog)
            val goodAxis = android.view.MotionEvent.AXIS_X
            val noisyAxis = android.view.MotionEvent.AXIS_Y

            c.onAxisSample(gamepadDevice, goodAxis, 0.0f) // neutral -> capturing
            // Noisy axis jumps to enter-level without ever being observed neutral.
            assertThat(c.onAxisSample(gamepadDevice, noisyAxis, 0.9f)).isEqualTo(true)
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.Capturing)

            assertThat(c.onAxisSample(gamepadDevice, goodAxis, 0.8f)).isEqualTo(true)
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.Result(PhysicalBinding.Axis(goodAxis)))
        }

        @Test
        @DisplayName("digital target captures axis+polarity for a stick direction")
        fun `digitalStickPolarity`() = runTest {
            val c = coordinator()
            c.beginCapture(0, gamepadDevice, CaptureTarget.Digital)
            val axis = android.view.MotionEvent.AXIS_X

            c.onAxisSample(gamepadDevice, axis, 0.0f)
            assertThat(c.onAxisSample(gamepadDevice, axis, -0.8f)).isEqualTo(true)
            assertThat(c.state.value)
                .isEqualTo(ControllerBindingCaptureState.Result(PhysicalBinding.AxisDirection(axis, -1)))

            val c2 = coordinator()
            c2.beginCapture(0, gamepadDevice, CaptureTarget.Digital)
            c2.onAxisSample(gamepadDevice, axis, 0.0f)
            assertThat(c2.onAxisSample(gamepadDevice, axis, 0.7f)).isEqualTo(true)
            assertThat(c2.state.value)
                .isEqualTo(ControllerBindingCaptureState.Result(PhysicalBinding.AxisDirection(axis, 1)))
        }

        @Test
        @DisplayName("digital capture ignores a stick deflection once a key press is armed")
        fun `stickIgnoredWhenKeyArmed`() = runTest {
            val c = coordinator()
            c.beginCapture(0, gamepadDevice, CaptureTarget.Digital)

            // Arm a key press (held during AwaitingNeutral) and observe the stick
            // at neutral so it would qualify for capture later.
            assertThat(c.onKeySample(gamepadDevice, ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1)).isEqualTo(true)
            assertThat(c.onAxisSample(gamepadDevice, android.view.MotionEvent.AXIS_RZ, 0f)).isEqualTo(true)
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.AwaitingNeutral)

            // Release the key -> capturing, with the key press now "armed".
            assertThat(c.onKeySample(gamepadDevice, ACTION_UP, KeyEvent.KEYCODE_BUTTON_R1)).isEqualTo(true)
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.Capturing)

            // A coincidental stick deflection must NOT capture the button row.
            assertThat(c.onAxisSample(gamepadDevice, android.view.MotionEvent.AXIS_RZ, 0.9f)).isEqualTo(true)
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.Capturing)

            // The bumper press captures the key.
            assertThat(c.onKeySample(gamepadDevice, ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1)).isEqualTo(true)
            assertThat(c.state.value)
                .isEqualTo(ControllerBindingCaptureState.Result(PhysicalBinding.Key(KeyEvent.KEYCODE_BUTTON_R1)))
        }

        @Test
        @DisplayName("digital multi-axis capture ignores a stick deflection once a key is armed")
        fun `multiAxisStickIgnoredWhenKeyArmed`() = runTest {
            val c = coordinator()
            c.beginCapture(0, gamepadDevice, CaptureTarget.Digital)

            // Arm a key press and observe both right-stick axes at neutral.
            c.onKeySample(gamepadDevice, ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1)
            c.onAxisSamples(
                gamepadDevice,
                listOf(
                    android.view.MotionEvent.AXIS_Z to 0f,
                    android.view.MotionEvent.AXIS_RZ to 0f,
                ),
            )
            c.onKeySample(gamepadDevice, ACTION_UP, KeyEvent.KEYCODE_BUTTON_R1)
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.Capturing)

            // A strong multi-axis stick deflection must not capture the row.
            c.onAxisSamples(
                gamepadDevice,
                listOf(
                    android.view.MotionEvent.AXIS_Z to 0.95f,
                    android.view.MotionEvent.AXIS_RZ to 0.9f,
                ),
            )
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.Capturing)

            // The bumper press captures the key.
            c.onKeySample(gamepadDevice, ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1)
            assertThat(c.state.value)
                .isEqualTo(ControllerBindingCaptureState.Result(PhysicalBinding.Key(KeyEvent.KEYCODE_BUTTON_R1)))
        }

        @Test
        @DisplayName("digital multi-axis capture picks the dominant (largest |value|) axis")
        fun `dominantAxisWinsOnMultiAxisCapture`() = runTest {
            val c = coordinator()
            c.beginCapture(0, gamepadDevice, CaptureTarget.Digital)
            // Return both axes to neutral -> capturing.
            c.onAxisSamples(
                gamepadDevice,
                listOf(
                    android.view.MotionEvent.AXIS_Z to 0f,
                    android.view.MotionEvent.AXIS_RZ to 0f,
                ),
            )
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.Capturing)

            // Both right-stick axes cross the enter threshold; AXIS_RZ has the
            // larger magnitude, so it must win even though AXIS_Z is listed first.
            c.onAxisSamples(
                gamepadDevice,
                listOf(
                    android.view.MotionEvent.AXIS_Z to 0.8f,
                    android.view.MotionEvent.AXIS_RZ to 0.95f,
                ),
            )
            assertThat(c.state.value)
                .isEqualTo(
                    ControllerBindingCaptureState.Result(
                        PhysicalBinding.AxisDirection(android.view.MotionEvent.AXIS_RZ, 1),
                    ),
                )
        }

        @Test
        @DisplayName("digital multi-axis capture picks the dominant axis regardless of order")
        fun `dominantAxisSelectedRegardlessOfOrder`() = runTest {
            val c = coordinator()
            c.beginCapture(0, gamepadDevice, CaptureTarget.Digital)
            // Return both axes to neutral -> capturing.
            c.onAxisSamples(
                gamepadDevice,
                listOf(
                    android.view.MotionEvent.AXIS_Z to 0f,
                    android.view.MotionEvent.AXIS_RZ to 0f,
                ),
            )
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.Capturing)

            // AXIS_Z crosses after AXIS_RZ but has the larger magnitude.
            c.onAxisSamples(
                gamepadDevice,
                listOf(
                    android.view.MotionEvent.AXIS_RZ to 0.7f,
                    android.view.MotionEvent.AXIS_Z to 0.9f,
                ),
            )
            assertThat(c.state.value)
                .isEqualTo(
                    ControllerBindingCaptureState.Result(
                        PhysicalBinding.AxisDirection(android.view.MotionEvent.AXIS_Z, 1),
                    ),
                )
        }

        @Test
        @DisplayName("digital single-axis capture still captures the crossing axis")
        fun `singleAxisDigitalCaptureUnchanged`() = runTest {
            val c = coordinator()
            c.beginCapture(0, gamepadDevice, CaptureTarget.Digital)
            c.onAxisSample(gamepadDevice, android.view.MotionEvent.AXIS_X, 0f)
            assertThat(c.onAxisSample(gamepadDevice, android.view.MotionEvent.AXIS_X, -0.8f)).isEqualTo(true)
            assertThat(c.state.value)
                .isEqualTo(
                    ControllerBindingCaptureState.Result(
                        PhysicalBinding.AxisDirection(android.view.MotionEvent.AXIS_X, -1),
                    ),
                )
        }

        @Test
        @DisplayName("trigger axis captures as a full unidirectional Axis")
        fun `triggerCapturesAsUnidirectionalAxis`() = runTest {
            val c = coordinator()
            c.beginCapture(0, gamepadDevice, CaptureTarget.Trigger)
            val trigger = android.view.MotionEvent.AXIS_LTRIGGER

            c.onAxisSample(gamepadDevice, trigger, 0.0f) // neutral rest
            assertThat(c.onAxisSample(gamepadDevice, trigger, 0.9f)).isEqualTo(true)
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.Result(PhysicalBinding.Axis(trigger)))
        }
    }

    @Nested
    @DisplayName("Device filtering")
    inner class DeviceFiltering {

        @Test
        @DisplayName("keyboard / TV-remote / DPAD-only events are ignored")
        fun `nonGamepadIgnored`() = runTest {
            val c = coordinator(
                sources = sourcesOf(
                    gamepadDevice to InputDevice.SOURCE_GAMEPAD,
                    remoteDevice to InputDevice.SOURCE_DPAD
                )
            )
            c.beginCapture(0, gamepadDevice, CaptureTarget.Digital)
            c.onKeySample(gamepadDevice, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)

            // A remote DPAD event must not be captured nor consumed.
            assertThat(c.onKeySample(remoteDevice, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
                .isNull()
            assertThat(c.onAxisSample(remoteDevice, android.view.MotionEvent.AXIS_X, 0.9f)).isNull()
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.Capturing)
        }

        @Test
        @DisplayName("events from a different gamepad device are left to normal routing")
        fun `otherDeviceLeftToRouting`() = runTest {
            val c = coordinator(
                sources = sourcesOf(
                    gamepadDevice to InputDevice.SOURCE_GAMEPAD,
                    otherGamepadDevice to InputDevice.SOURCE_GAMEPAD
                )
            )
            c.beginCapture(0, gamepadDevice, CaptureTarget.Digital)

            assertThat(c.onKeySample(otherGamepadDevice, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
                .isNull()
            assertThat(c.onAxisSample(otherGamepadDevice, android.view.MotionEvent.AXIS_X, 0.9f)).isNull()
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.AwaitingNeutral)
        }

        @Test
        @DisplayName("capture across physical gamepads accepts the first controller pressed")
        fun `any eligible gamepad can provide binding`() = runTest {
            val c = coordinator(
                sources = sourcesOf(
                    gamepadDevice to InputDevice.SOURCE_GAMEPAD,
                    otherGamepadDevice to InputDevice.SOURCE_GAMEPAD,
                    remoteDevice to InputDevice.SOURCE_DPAD,
                ),
            )
            c.beginCapture(
                slotIndex = 1,
                deviceIds = setOf(gamepadDevice, otherGamepadDevice, remoteDevice),
                target = CaptureTarget.Digital,
            )
            runCurrent()

            assertThat(c.onKeySample(remoteDevice, ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)).isNull()
            assertThat(c.onKeySample(otherGamepadDevice, ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
                .isEqualTo(true)
            assertThat(c.state.value)
                .isEqualTo(ControllerBindingCaptureState.Result(PhysicalBinding.Key(KeyEvent.KEYCODE_BUTTON_A)))
        }

        @Test
        @DisplayName("beginCapture with no controller assigned exposes NoDeviceAssigned")
        fun `noDeviceAssigned`() = runTest {
            val c = coordinator()
            c.beginCapture(0, null, CaptureTarget.Digital)
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.NoDeviceAssigned)
            // No capture session: events fall through.
            assertThat(c.onKeySample(gamepadDevice, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
                .isNull()
        }

        @Test
        @DisplayName("beginCapture with a non-gamepad assigned device exposes NoDeviceAssigned")
        fun `assignedNonGamepad`() = runTest {
            val c = coordinator(
                sources = sourcesOf(remoteDevice to InputDevice.SOURCE_DPAD)
            )
            c.beginCapture(0, remoteDevice, CaptureTarget.Digital)
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.NoDeviceAssigned)
        }
    }

    @Nested
    @DisplayName("Cancellation and timeout")
    inner class CancellationAndTimeout {

        @Test
        @DisplayName("explicit cancel emits Cancelled and stops the session")
        fun `explicitCancel`() = runTest {
            val c = coordinator()
            c.beginCapture(0, gamepadDevice, CaptureTarget.Digital)
            c.cancel()
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.Cancelled)
            assertThat(c.onKeySample(gamepadDevice, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
                .isNull()
        }

        @Test
        @DisplayName("disconnect during capture cancels the session")
        fun `disconnectCancels`() = runTest {
            val c = coordinator()
            c.beginCapture(0, gamepadDevice, CaptureTarget.Digital)
            c.onDeviceRemoved(gamepadDevice)
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.Cancelled)

            // The InputDeviceListener override routes the same path.
            val c2 = coordinator()
            c2.beginCapture(0, gamepadDevice, CaptureTarget.Digital)
            c2.onInputDeviceRemoved(gamepadDevice)
            assertThat(c2.state.value).isEqualTo(ControllerBindingCaptureState.Cancelled)
        }

        @Test
        @DisplayName("disconnect of a different device does not cancel")
        fun `disconnectOtherDeviceIgnored`() = runTest {
            val c = coordinator(
                sources = sourcesOf(
                    gamepadDevice to InputDevice.SOURCE_GAMEPAD,
                    otherGamepadDevice to InputDevice.SOURCE_GAMEPAD
                )
            )
            c.beginCapture(0, gamepadDevice, CaptureTarget.Digital)
            c.onDeviceRemoved(otherGamepadDevice)
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.AwaitingNeutral)
        }

        @Test
        @DisplayName("no qualifying input within 15s times out with no result")
        fun `timeoutAfter15s`() = runTest {
            val c = coordinator(timeoutMillis = 15_000L)
            c.beginCapture(0, gamepadDevice, CaptureTarget.Digital)

            advanceTimeBy(15_000L)
            runCurrent()
            assertThat(c.state.value).isEqualTo(ControllerBindingCaptureState.TimedOut)

            // Nothing saved; subsequent input falls through.
            assertThat(c.onKeySample(gamepadDevice, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
                .isNull()
        }

        @Test
        @DisplayName("a captured result cancels the pending timeout")
        fun `resultStopsTimeout`() = runTest {
            val c = coordinator()
            c.beginCapture(0, gamepadDevice, CaptureTarget.Digital)
            c.onKeySample(gamepadDevice, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)
            assertThat(c.onKeySample(gamepadDevice, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
                .isEqualTo(true)
            assertThat(c.state.value)
                .isEqualTo(ControllerBindingCaptureState.Result(PhysicalBinding.Key(KeyEvent.KEYCODE_BUTTON_A)))

            advanceTimeBy(15_000L)
            runCurrent()
            assertThat(c.state.value)
                .isEqualTo(ControllerBindingCaptureState.Result(PhysicalBinding.Key(KeyEvent.KEYCODE_BUTTON_A)))
        }
    }

    @Nested
    @DisplayName("Threshold constants")
    inner class ThresholdConstants {
        @Test
        @DisplayName("neutral and enter thresholds match the spec")
        fun `thresholdsMatchSpec`() {
            assertThat(NEUTRAL_THRESHOLD).isEqualTo(0.25f)
            assertThat(ENTER_THRESHOLD).isEqualTo(0.65f)
            assertThat(ControllerBindingCaptureCoordinator.DEFAULT_TIMEOUT_MILLIS).isEqualTo(15_000L)
        }
    }
}

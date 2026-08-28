package com.romm.desktop.controller

import com.romm.androidtv.controller.capture.CaptureTarget
import com.romm.androidtv.controller.config.PhysicalBinding
import com.romm.androidtv.controller.model.NeutralAxis
import com.romm.androidtv.controller.model.NeutralKey
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Pure state-machine tests for [DesktopCaptureCoordinator], driven by fake poll snapshots
 * (no JInput native required) with virtual-time clocks for the 15 s timeout — mirroring the
 * Android `ControllerBindingCaptureCoordinatorTest` coverage.
 */
@DisplayName("DesktopCaptureCoordinator — polling capture state machine")
class DesktopCaptureCoordinatorTest {

    private val padA = "pad-a"
    private val padB = "pad-b"

    private fun TestScope.coordinator(
        timeoutMillis: Long = DesktopCaptureCoordinator.DEFAULT_TIMEOUT_MILLIS,
    ) = DesktopCaptureCoordinator(scope = this, timeoutMillis = timeoutMillis)

    private fun state(
        buttons: Set<NeutralKey> = emptySet(),
        axes: Map<NeutralAxis, Float> = emptyMap(),
    ) = JInputControllerState(buttons = buttons, axes = axes)

    @Nested
    @DisplayName("Neutral gating")
    inner class NeutralGating {

        @Test
        fun `a held press that opened the row is not captured until released`() = runTest {
            val c = coordinator()
            c.beginCapture(0, padA, CaptureTarget.Digital)

            // First poll is the baseline: the still-held row-opening press is recorded but
            // never diffed, so it cannot be captured.
            assertThat(c.onPoll(padA, state(buttons = setOf(NeutralKey.BUTTON_A)))).isEqualTo(true)
            assertThat(c.state.value).isEqualTo(DesktopCaptureState.AwaitingNeutral)

            // Still held on the next poll — no rising edge, still non-neutral.
            c.onPoll(padA, state(buttons = setOf(NeutralKey.BUTTON_A)))
            assertThat(c.state.value).isEqualTo(DesktopCaptureState.AwaitingNeutral)

            // Release -> neutral -> capturing.
            c.onPoll(padA, state())
            assertThat(c.state.value).isEqualTo(DesktopCaptureState.Capturing)

            // Next fresh press is captured.
            c.onPoll(padA, state(buttons = setOf(NeutralKey.BUTTON_B)))
            assertThat(c.state.value)
                .isEqualTo(DesktopCaptureState.Result(PhysicalBinding.Key(NeutralKey.BUTTON_B.platformCode)))
        }

        @Test
        fun `idle device arms on the first poll without a sacrificial press`() = runTest {
            val c = coordinator()
            c.beginCapture(0, padA, CaptureTarget.Digital)

            c.onPoll(padA, state())
            assertThat(c.state.value).isEqualTo(DesktopCaptureState.Capturing)

            c.onPoll(padA, state(buttons = setOf(NeutralKey.BUTTON_B)))
            assertThat(c.state.value)
                .isEqualTo(DesktopCaptureState.Result(PhysicalBinding.Key(NeutralKey.BUTTON_B.platformCode)))
        }

        @Test
        fun `a non-neutral stick keeps the capture waiting`() = runTest {
            val c = coordinator()
            c.beginCapture(0, padA, CaptureTarget.Analog)

            c.onPoll(padA, state(axes = mapOf(NeutralAxis.X to 0.9f)))
            assertThat(c.state.value).isEqualTo(DesktopCaptureState.AwaitingNeutral)

            c.onPoll(padA, state(axes = mapOf(NeutralAxis.X to 0.0f)))
            assertThat(c.state.value).isEqualTo(DesktopCaptureState.Capturing)
        }

        @Test
        fun `held keys clear on release and the next press is captured`() = runTest {
            val c = coordinator()
            c.beginCapture(0, padA, CaptureTarget.Digital)

            // Two buttons held at start.
            c.onPoll(padA, state(buttons = setOf(NeutralKey.BUTTON_A, NeutralKey.BUTTON_B)))
            assertThat(c.state.value).isEqualTo(DesktopCaptureState.AwaitingNeutral)

            // Release one — still non-neutral.
            c.onPoll(padA, state(buttons = setOf(NeutralKey.BUTTON_B)))
            assertThat(c.state.value).isEqualTo(DesktopCaptureState.AwaitingNeutral)

            // Release the rest -> capturing; a held key is never itself captured.
            c.onPoll(padA, state())
            assertThat(c.state.value).isEqualTo(DesktopCaptureState.Capturing)

            c.onPoll(padA, state(buttons = setOf(NeutralKey.BUTTON_X)))
            assertThat(c.state.value)
                .isEqualTo(DesktopCaptureState.Result(PhysicalBinding.Key(NeutralKey.BUTTON_X.platformCode)))
        }
    }

    @Nested
    @DisplayName("Enter threshold")
    inner class EnterThreshold {

        @Test
        fun `axis below the enter threshold is not captured`() = runTest {
            val c = coordinator()
            c.beginCapture(0, padA, CaptureTarget.Digital)
            c.onPoll(padA, state(axes = mapOf(NeutralAxis.X to 0.0f))) // arm; X seen neutral
            assertThat(c.state.value).isEqualTo(DesktopCaptureState.Capturing)

            c.onPoll(padA, state(axes = mapOf(NeutralAxis.X to 0.5f)))
            assertThat(c.state.value).isEqualTo(DesktopCaptureState.Capturing)
        }

        @Test
        fun `axis crossing the enter threshold after neutral captures with polarity`() = runTest {
            val c = coordinator()
            c.beginCapture(0, padA, CaptureTarget.Digital)
            c.onPoll(padA, state(axes = mapOf(NeutralAxis.X to 0.0f))) // arm (X seen neutral)

            c.onPoll(padA, state(axes = mapOf(NeutralAxis.X to 0.7f)))
            assertThat(c.state.value).isEqualTo(
                DesktopCaptureState.Result(PhysicalBinding.AxisDirection(NeutralAxis.X.platformCode, 1)),
            )

            val negative = coordinator()
            negative.beginCapture(0, padA, CaptureTarget.Digital)
            negative.onPoll(padA, state(axes = mapOf(NeutralAxis.Y to 0.0f)))
            negative.onPoll(padA, state(axes = mapOf(NeutralAxis.Y to -0.8f)))
            assertThat(negative.state.value).isEqualTo(
                DesktopCaptureState.Result(PhysicalBinding.AxisDirection(NeutralAxis.Y.platformCode, -1)),
            )
        }

        @Test
        fun `analog target captures a full axis binding`() = runTest {
            val c = coordinator()
            c.beginCapture(0, padA, CaptureTarget.Analog)
            c.onPoll(padA, state(axes = mapOf(NeutralAxis.X to 0.0f))) // arm; X seen neutral

            c.onPoll(padA, state(axes = mapOf(NeutralAxis.X to 0.9f)))
            assertThat(c.state.value)
                .isEqualTo(DesktopCaptureState.Result(PhysicalBinding.Axis(NeutralAxis.X.platformCode)))
        }

        @Test
        fun `trigger target captures either a key or full axis binding`() = runTest {
            val keyCapture = coordinator()
            keyCapture.beginCapture(0, padA, CaptureTarget.Trigger)
            keyCapture.onPoll(padA, state())
            keyCapture.onPoll(padA, state(buttons = setOf(NeutralKey.BUTTON_L1)))
            assertThat(keyCapture.state.value).isEqualTo(
                DesktopCaptureState.Result(PhysicalBinding.Key(NeutralKey.BUTTON_L1.platformCode)),
            )

            val axisCapture = coordinator()
            axisCapture.beginCapture(0, padA, CaptureTarget.Trigger)
            axisCapture.onPoll(padA, state(axes = mapOf(NeutralAxis.LTRIGGER to 0.0f)))
            axisCapture.onPoll(padA, state(axes = mapOf(NeutralAxis.LTRIGGER to 0.9f)))
            assertThat(axisCapture.state.value).isEqualTo(
                DesktopCaptureState.Result(PhysicalBinding.Axis(NeutralAxis.LTRIGGER.platformCode)),
            )
        }

        @Test
        fun `digital multi-axis poll captures only the dominant deflection`() = runTest {
            val c = coordinator()
            c.beginCapture(0, padA, CaptureTarget.Digital)
            c.onPoll(padA, state(axes = mapOf(NeutralAxis.X to 0.0f, NeutralAxis.Y to 0.0f))) // arm

            c.onPoll(padA, state(axes = mapOf(NeutralAxis.X to 0.7f, NeutralAxis.Y to 0.85f)))
            assertThat(c.state.value).isEqualTo(
                DesktopCaptureState.Result(PhysicalBinding.AxisDirection(NeutralAxis.Y.platformCode, 1)),
            )
        }
    }

    @Nested
    @DisplayName("First press wins")
    inner class FirstPressWins {

        @Test
        fun `the first controller to produce a rising edge wins`() = runTest {
            val c = coordinator()
            c.beginCapture(0, setOf(padA, padB), CaptureTarget.Digital)
            c.onPoll(padA, state()) // baseline A
            c.onPoll(padB, state()) // baseline B

            c.onPoll(padA, state(buttons = setOf(NeutralKey.BUTTON_A)))
            assertThat(c.state.value)
                .isEqualTo(DesktopCaptureState.Result(PhysicalBinding.Key(NeutralKey.BUTTON_A.platformCode)))

            // A late press on the other controller is ignored once capture is terminal.
            assertThat(c.onPoll(padB, state(buttons = setOf(NeutralKey.BUTTON_B)))).isNull()
            assertThat(c.state.value)
                .isEqualTo(DesktopCaptureState.Result(PhysicalBinding.Key(NeutralKey.BUTTON_A.platformCode)))
        }

        @Test
        fun `a key press in the same poll preempts a stick deflection`() = runTest {
            val c = coordinator()
            c.beginCapture(0, padA, CaptureTarget.Digital)
            c.onPoll(padA, state(axes = mapOf(NeutralAxis.X to 0.0f))) // arm; X seen neutral

            c.onPoll(padA, state(buttons = setOf(NeutralKey.BUTTON_A), axes = mapOf(NeutralAxis.X to 0.9f)))
            assertThat(c.state.value)
                .isEqualTo(DesktopCaptureState.Result(PhysicalBinding.Key(NeutralKey.BUTTON_A.platformCode)))
        }

        @Test
        fun `an armed button press blocks stick capture for the rest of the session`() = runTest {
            val c = coordinator()
            c.beginCapture(0, padA, CaptureTarget.Digital)

            // Stick held off-center at start keeps us awaiting neutral...
            c.onPoll(padA, state(axes = mapOf(NeutralAxis.X to 0.9f)))
            assertThat(c.state.value).isEqualTo(DesktopCaptureState.AwaitingNeutral)

            // ...and a fresh button press while non-neutral arms the button intent.
            c.onPoll(padA, state(buttons = setOf(NeutralKey.BUTTON_B), axes = mapOf(NeutralAxis.X to 0.9f)))
            assertThat(c.state.value).isEqualTo(DesktopCaptureState.AwaitingNeutral)

            // Everything returns to neutral -> capturing.
            c.onPoll(padA, state(axes = mapOf(NeutralAxis.X to 0.0f)))
            assertThat(c.state.value).isEqualTo(DesktopCaptureState.Capturing)

            // A stick deflection must NOT capture the button row now.
            c.onPoll(padA, state(axes = mapOf(NeutralAxis.X to 0.9f)))
            assertThat(c.state.value).isEqualTo(DesktopCaptureState.Capturing)
        }
    }

    @Nested
    @DisplayName("Timeout / cancel / no device")
    inner class TerminalStates {

        @Test
        fun `capture times out after the default 15 seconds with no qualifying input`() = runTest {
            val c = coordinator()
            c.beginCapture(0, padA, CaptureTarget.Digital)
            runCurrent() // start the timeout job at virtual t=0
            c.onPoll(padA, state()) // arm
            assertThat(c.state.value).isEqualTo(DesktopCaptureState.Capturing)

            advanceTimeBy(DesktopCaptureCoordinator.DEFAULT_TIMEOUT_MILLIS - 1)
            runCurrent()
            assertThat(c.state.value).isEqualTo(DesktopCaptureState.Capturing)

            advanceTimeBy(1)
            runCurrent()
            assertThat(c.state.value).isEqualTo(DesktopCaptureState.TimedOut)
        }

        @Test
        fun `a successful capture is not replaced by a later timeout`() = runTest {
            val c = coordinator(timeoutMillis = 1_000L)
            c.beginCapture(0, padA, CaptureTarget.Digital)
            runCurrent() // start the timeout job at virtual t=0
            c.onPoll(padA, state())
            c.onPoll(padA, state(buttons = setOf(NeutralKey.BUTTON_A)))

            advanceTimeBy(5_000L)
            runCurrent()
            assertThat(c.state.value)
                .isEqualTo(DesktopCaptureState.Result(PhysicalBinding.Key(NeutralKey.BUTTON_A.platformCode)))
        }

        @Test
        fun `explicit cancel emits Cancelled`() = runTest {
            val c = coordinator()
            c.beginCapture(0, padA, CaptureTarget.Digital)
            c.onPoll(padA, state())

            c.cancel()
            assertThat(c.state.value).isEqualTo(DesktopCaptureState.Cancelled)
            // Samples after a terminal state are ignored.
            assertThat(c.onPoll(padA, state(buttons = setOf(NeutralKey.BUTTON_A)))).isNull()
        }

        @Test
        fun `removing the last eligible controller cancels the capture`() = runTest {
            val c = coordinator()
            c.beginCapture(0, setOf(padA, padB), CaptureTarget.Digital)
            c.onPoll(padA, state())
            c.onPoll(padB, state())

            c.onDeviceRemoved(padA)
            assertThat(c.state.value).isEqualTo(DesktopCaptureState.Capturing)

            c.onDeviceRemoved(padB)
            assertThat(c.state.value).isEqualTo(DesktopCaptureState.Cancelled)
        }

        @Test
        fun `no eligible device emits NoDeviceAssigned`() = runTest {
            val c = coordinator()
            c.beginCapture(0, emptySet<String>(), CaptureTarget.Digital)
            assertThat(c.state.value).isEqualTo(DesktopCaptureState.NoDeviceAssigned)

            val single = coordinator()
            single.beginCapture(0, null as String?, CaptureTarget.Digital)
            assertThat(single.state.value).isEqualTo(DesktopCaptureState.NoDeviceAssigned)
        }

        @Test
        fun `polls for non-eligible controllers are ignored`() = runTest {
            val c = coordinator()
            c.beginCapture(0, padA, CaptureTarget.Digital)
            c.onPoll(padA, state()) // arm

            assertThat(c.onPoll(padB, state(buttons = setOf(NeutralKey.BUTTON_A)))).isNull()
            assertThat(c.state.value).isEqualTo(DesktopCaptureState.Capturing)
        }
    }
}

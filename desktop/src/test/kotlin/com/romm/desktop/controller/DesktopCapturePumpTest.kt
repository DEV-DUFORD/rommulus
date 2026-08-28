package com.romm.desktop.controller

import com.romm.androidtv.controller.capture.CaptureTarget
import com.romm.androidtv.controller.config.PhysicalBinding
import com.romm.androidtv.controller.model.DeviceSignature
import com.romm.androidtv.controller.model.NeutralKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests the JInput poll-source → [DesktopCaptureCoordinator] wiring (E2 capture overlay):
 * the pump feeds every enumerated controller's poll into the coordinator while a session is
 * active and stays idle otherwise. Driven with virtual time — no JInput native required.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("DesktopCapturePump — JInput poll source wiring")
class DesktopCapturePumpTest {

    private class FakeController(
        var state: JInputControllerState = JInputControllerState(buttons = emptySet(), axes = emptyMap()),
    ) : JInputController {
        override val id: String = "fake-pad"
        override val signature: DeviceSignature = DeviceSignature(
            descriptor = "jinput:fake-pad",
            vendorId = 0,
            productId = 0,
            name = "fake-pad",
        )
        override fun poll(): JInputControllerState = state
    }

    private class FakeSource(private val controllers: List<JInputController>) : JInputSource {
        var enumerations = 0
        override fun enumerate(): List<JInputController> {
            enumerations++
            return controllers
        }
    }

    @Test
    fun `pump feeds polls into the capture coordinator until a result is captured`() = runTest {
        val controller = FakeController()
        val source = FakeSource(listOf(controller))
        val capture = DesktopCaptureCoordinator(scope = this)
        val pump = DesktopCapturePump(source, capture, this, pollIntervalMillis = 10L)

        capture.beginCapture(0, setOf("fake-pad"), CaptureTarget.Digital)
        assertThat(capture.state.value).isEqualTo(DesktopCaptureState.AwaitingNeutral)

        pump.start()
        runCurrent() // tick 1: neutral baseline -> Capturing.
        assertThat(capture.state.value).isEqualTo(DesktopCaptureState.Capturing)

        controller.state = JInputControllerState(buttons = setOf(NeutralKey.BUTTON_B), axes = emptyMap())
        advanceTimeBy(20L)
        runCurrent()

        assertThat(capture.state.value)
            .isEqualTo(DesktopCaptureState.Result(PhysicalBinding.Key(NeutralKey.BUTTON_B.platformCode)))
        pump.stop()
    }

    @Test
    fun `pump does not enumerate controllers while no capture is active`() = runTest {
        val source = FakeSource(emptyList())
        val capture = DesktopCaptureCoordinator(scope = this)
        val pump = DesktopCapturePump(source, capture, this, pollIntervalMillis = 10L)

        pump.start()
        advanceTimeBy(100L)
        runCurrent()

        assertThat(source.enumerations).isZero()
        assertThat(capture.state.value).isEqualTo(DesktopCaptureState.Idle)
        pump.stop()
    }

    @Test
    fun `captureActive is true only while a session accepts input`() {
        assertThat(captureActive(DesktopCaptureState.AwaitingNeutral)).isTrue()
        assertThat(captureActive(DesktopCaptureState.Capturing)).isTrue()
        assertThat(captureActive(DesktopCaptureState.Idle)).isFalse()
        assertThat(captureActive(DesktopCaptureState.Cancelled)).isFalse()
        assertThat(captureActive(DesktopCaptureState.TimedOut)).isFalse()
        assertThat(captureActive(DesktopCaptureState.NoDeviceAssigned)).isFalse()
        assertThat(captureActive(DesktopCaptureState.Result(PhysicalBinding.Key(NeutralKey.BUTTON_A.platformCode)))).isFalse()
    }
}

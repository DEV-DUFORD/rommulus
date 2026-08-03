package com.romm.androidtv.controller.capture

import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.romm.androidtv.controller.config.PhysicalBinding
import com.romm.androidtv.controller.policy.SourceFilterPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Immutable capture lifecycle exposed to the controller-settings UI.
 *
 * The coordinator is a one-shot state machine: `beginCapture` starts an
 * "awaiting neutral" phase, then moves to `Capturing` once every observed
 * button and axis is neutral, then emits exactly one terminal state
 * (`Result`, `Cancelled`, `TimedOut` or `NoDeviceAssigned`).
 *
 * @see ControllerBindingCaptureCoordinator
 */
sealed interface ControllerBindingCaptureState {
    /** No capture in progress. */
    data object Idle : ControllerBindingCaptureState

    /** Waiting for all gamepad buttons/axes to return to neutral. */
    data object AwaitingNeutral : ControllerBindingCaptureState

    /** Neutral, waiting for the first qualifying input. */
    data object Capturing : ControllerBindingCaptureState

    /** A binding was captured. Terminal. */
    data class Result(val binding: PhysicalBinding) : ControllerBindingCaptureState

    /** Capture cancelled (Back, disconnect, stop, tab change). Terminal. */
    data object Cancelled : ControllerBindingCaptureState

    /** No qualifying input within the timeout. Terminal; nothing saved. */
    data object TimedOut : ControllerBindingCaptureState

    /** No real controller is connected. Terminal. */
    data object NoDeviceAssigned : ControllerBindingCaptureState
}

/**
 * What kind of physical input the caller wants to capture.
 *
 * - [Digital]: a face-button press yields a [PhysicalBinding.Key]; a stick
 *   deflection yields a [PhysicalBinding.AxisDirection] (axis + polarity).
 * - [Analog]: a stick or trigger deflection yields a full
 *   [PhysicalBinding.Axis]. Trigger axes are inherently unidirectional and
 *   are captured as a full `Axis` regardless.
 */
sealed interface CaptureTarget {
    data object Digital : CaptureTarget
    data object Analog : CaptureTarget
}

/**
 * Captures a raw controller input for a single player port, following the
 * CONTROLLER_SETTINGS.md "Capture dialog" rules:
 *
 * 1. Capture begins only after all buttons/axes are neutral (so the press that
 *    opened the row does not become the binding).
 * 2. The first new gamepad `ACTION_DOWN` from any eligible physical device is accepted
 *    as a [PhysicalBinding.Key] for digital targets.
 * 3. Axis capture requires the axis to first be observed below
 *    [NEUTRAL_THRESHOLD], then cross [ENTER_THRESHOLD] in either direction.
 * 4. Repeats, TV-remote/keyboard/mouse devices, and noisy axes that never
 *    return to neutral are ignored.
 * 5/6. Triggers capture as unidirectional [PhysicalBinding.Axis]; sticks
 *    capture as [PhysicalBinding.Axis] (analog) or [PhysicalBinding.AxisDirection]
 *    (digital).
 * 8. Capture cancels on explicit cancel, device disconnect, or activity stop.
 * 9. Capturing times out after [DEFAULT_TIMEOUT_MILLIS] with no qualifying input.
 * 10. Only `SOURCE_GAMEPAD`/`SOURCE_JOYSTICK` devices are accepted.
 *
 * State is exposed as a [StateFlow] mirroring
 * `ControllerEventRouter.slotsFlow`. The Activity wires the not-yet-wired
 * `onKeyEvent`/`onMotionEvent` before its own dispatch path, and wires
 * disconnect handling to the router's `InputDeviceListener` via
 * [onInputDeviceRemoved]/[onDeviceRemoved].
 */
class ControllerBindingCaptureCoordinator(
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val sourceProvider: (Int) -> Int = { InputDevice.getDevice(it)?.sources ?: 0 },
) : InputManager.InputDeviceListener {

    private val _state = MutableStateFlow<ControllerBindingCaptureState>(
        ControllerBindingCaptureState.Idle
    )
    val state: StateFlow<ControllerBindingCaptureState> = _state.asStateFlow()

    private val eligibleDeviceIds = mutableSetOf<Int>()
    private var target: CaptureTarget = CaptureTarget.Digital

    /** Device/key pairs currently held down across eligible devices (for neutral gating). */
    private val pressedKeys = mutableSetOf<Pair<Int, Int>>()

    /** Last observed normalized value per device/axis pair. */
    private val axisValues = mutableMapOf<Pair<Int, Int>, Float>()

    /** Axes that have been observed below [NEUTRAL_THRESHOLD] at least once. */
    private val axisSeenNeutral = mutableSetOf<Pair<Int, Int>>()

    private var timeoutJob: Job? = null

    /** Whether a capture session is currently active (pre- or post-neutral). */
    private val isActive: Boolean
        get() = _state.value is ControllerBindingCaptureState.AwaitingNeutral ||
            _state.value == ControllerBindingCaptureState.Capturing

    /**
     * Begin capturing a binding for [slotIndex] (0..3).
     *
     * @param deviceId the InputDevice id assigned to the selected effective
     *   player port, or null if no controller is connected there.
     * @param target digital or analog capture mode.
     */
    @Suppress("UNUSED_PARAMETER") // slotIndex reserved for caller-facing diagnostics
    fun beginCapture(slotIndex: Int, deviceId: Int?, target: CaptureTarget) {
        beginCapture(
            slotIndex = slotIndex,
            deviceIds = setOfNotNull(deviceId),
            target = target,
        )
    }

    /**
     * Begin capture across every connected physical gamepad. The first qualifying
     * input wins; TV remotes and Android virtual gamepads must be excluded by the caller.
     */
    @Suppress("UNUSED_PARAMETER")
    fun beginCapture(slotIndex: Int, deviceIds: Set<Int>, target: CaptureTarget) {
        cancelInternal()
        eligibleDeviceIds += deviceIds.filter { isGamepadSource(sourceProvider(it)) }
        if (eligibleDeviceIds.isEmpty()) {
            _state.value = ControllerBindingCaptureState.NoDeviceAssigned
            return
        }
        this.target = target
        pressedKeys.clear()
        axisValues.clear()
        axisSeenNeutral.clear()
        _state.value = ControllerBindingCaptureState.AwaitingNeutral
        // The row-opening ACTION_DOWN has already passed through Activity dispatch.
        // Arm on the next coroutine turn when no eligible-device input was observed;
        // this avoids requiring an unrelated controller to be pressed twice.
        scope.launch { checkNeutralAndAdvance() }
        startTimeout()
    }

    /**
     * Cancel capture explicitly (remote Back, tab change, activity stop).
     * Emits [ControllerBindingCaptureState.Cancelled].
     */
    fun cancel() {
        cancelInternal()
        _state.value = ControllerBindingCaptureState.Cancelled
    }

    /**
     * Notify the coordinator that a device disconnected. The caller wires this
     * to the router's `InputDeviceListener` removal path (see
     * `ControllerEventRouter.onInputDeviceRemoved`). Cancels an in-progress
     * capture for that device.
     */
    fun onDeviceRemoved(deviceId: Int) {
        if (!eligibleDeviceIds.remove(deviceId)) return
        pressedKeys.removeAll { (heldDeviceId, _) -> heldDeviceId == deviceId }
        axisValues.keys.removeAll { (axisDeviceId, _) -> axisDeviceId == deviceId }
        axisSeenNeutral.removeAll { (axisDeviceId, _) -> axisDeviceId == deviceId }
        if (eligibleDeviceIds.isEmpty() && isActive) {
            cancel()
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) = Unit
    override fun onInputDeviceChanged(deviceId: Int) = Unit
    override fun onInputDeviceRemoved(deviceId: Int) = onDeviceRemoved(deviceId)

    /**
     * Entry point called by the Activity's `dispatchKeyEvent` BEFORE its normal
     * routing. Returns `true` to consume the event, or `null` to let the
     * existing routing continue.
     */
    fun onKeyEvent(event: KeyEvent): Boolean? {
        return onKeySample(event.deviceId, event.action, event.keyCode, event.repeatCount)
    }

    /**
     * Entry point called by the Activity's `dispatchGenericMotionEvent` BEFORE
     * its normal routing. Returns `true` to consume, `null` to continue.
     */
    fun onMotionEvent(event: MotionEvent): Boolean? {
        val deviceId = event.deviceId
        if (deviceId !in eligibleDeviceIds) return null
        if (!isGamepadSource(sourceProvider(deviceId))) return null

        val axisConstants = InputDevice.getDevice(deviceId)?.motionRanges?.map { it.axis }.orEmpty()
        for (h in 0 until event.historySize) {
            for (axis in axisConstants) {
                onAxisSample(deviceId, axis, event.getHistoricalAxisValue(axis, h))
            }
        }
        for (axis in axisConstants) {
            onAxisSample(deviceId, axis, event.getAxisValue(axis))
        }
        return true
    }

    /**
     * Pure key-sample seam (extracted so the state machine is unit-testable
     * without a real [KeyEvent]). [onKeyEvent] delegates here after parsing.
     */
    internal fun onKeySample(deviceId: Int, action: Int, keyCode: Int, repeatCount: Int = 0): Boolean? {
        if (!isActive) return null
        if (deviceId !in eligibleDeviceIds) return null
        if (!isGamepadSource(sourceProvider(deviceId))) return null
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return null

        if (_state.value == ControllerBindingCaptureState.AwaitingNeutral) {
            when (action) {
                KeyEvent.ACTION_DOWN -> {
                    if (repeatCount == 0) pressedKeys.add(deviceId to keyCode)
                    // A held button keeps us non-neutral; never capture it here.
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    pressedKeys.remove(deviceId to keyCode)
                    checkNeutralAndAdvance()
                    return true
                }
            }
        }

        // Capturing phase.
        if (action == KeyEvent.ACTION_DOWN && repeatCount == 0 && target == CaptureTarget.Digital) {
            finishWith(PhysicalBinding.Key(keyCode))
        }
        return true
    }

    /**
     * Pure axis-sample seam (extracted so the state machine is unit-testable
     * without a real [MotionEvent]). [onMotionEvent] delegates here for each
     * axis sample (current and historical).
     */
    internal fun onAxisSample(deviceId: Int, axis: Int, value: Float): Boolean? {
        if (!isActive) return null
        if (deviceId !in eligibleDeviceIds) return null
        if (!isGamepadSource(sourceProvider(deviceId))) return null

        val deviceAxis = deviceId to axis
        axisValues[deviceAxis] = value
        if (abs(value) < NEUTRAL_THRESHOLD) axisSeenNeutral.add(deviceAxis)

        if (_state.value == ControllerBindingCaptureState.AwaitingNeutral) {
            checkNeutralAndAdvance()
            return true
        }

        // Capturing phase.
        if (deviceAxis !in axisSeenNeutral) return true // noisy axis that never returned to neutral
        if (abs(value) < ENTER_THRESHOLD) return true // not yet crossing the enter threshold

        val binding = when (target) {
            CaptureTarget.Analog -> PhysicalBinding.Axis(axis)
            CaptureTarget.Digital -> PhysicalBinding.AxisDirection(
                axis = axis,
                polarity = if (value > 0f) 1 else -1
            )
        }
        finishWith(binding)
        return true
    }

    private fun finishWith(binding: PhysicalBinding) {
        _state.value = ControllerBindingCaptureState.Result(binding)
        cancelInternal()
    }

    /** Transition [AwaitingNeutral] -> [Capturing] once every button/axis is neutral. */
    private fun checkNeutralAndAdvance() {
        if (_state.value != ControllerBindingCaptureState.AwaitingNeutral) return
        if (pressedKeys.isNotEmpty()) return
        if (axisValues.values.any { abs(it) >= NEUTRAL_THRESHOLD }) return
        _state.value = ControllerBindingCaptureState.Capturing
    }

    private fun startTimeout() {
        timeoutJob = scope.launch {
            delay(timeoutMillis)
            if (isActive) {
                cancelInternal()
                _state.value = ControllerBindingCaptureState.TimedOut
            }
        }
    }

    private fun cancelInternal() {
        timeoutJob?.cancel()
        timeoutJob = null
        eligibleDeviceIds.clear()
        pressedKeys.clear()
        axisValues.clear()
        axisSeenNeutral.clear()
    }

    /**
     * True only for real gamepad/joystick devices (rule 10). Delegates the
     * controller classification to the codebase-wide [SourceFilterPolicy]
     * (consistent with `ControllerEventRouter`) and additionally requires the
     * strict GAMEPAD/JOYSTICK bit, because `SourceFilterPolicy.isControllerSource`
     * also accepts DPAD-only devices (TV remotes), which capture must exclude
     * per rule 10 — the unit tests assert that DPAD-only remotes are rejected.
     * `sourceProvider` stays injectable so tests can drive source masks directly.
     */
    private fun isGamepadSource(sources: Int): Boolean =
        SourceFilterPolicy.isControllerSource(sources) &&
            ((sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)

    companion object {
        /** Axis magnitude at or below which the axis counts as "neutral". */
        const val NEUTRAL_THRESHOLD = 0.25f

        /** Axis magnitude that must be crossed (after neutral) to accept it. */
        const val ENTER_THRESHOLD = 0.65f

        /** Spec-mandated 15 s timeout for a capture session. */
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L

        const val SOURCE_GAMEPAD = InputDevice.SOURCE_GAMEPAD
        const val SOURCE_JOYSTICK = InputDevice.SOURCE_JOYSTICK
    }
}

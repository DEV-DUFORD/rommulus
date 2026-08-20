package com.romm.desktop.controller

import com.romm.androidtv.controller.capture.CaptureTarget
import com.romm.androidtv.controller.config.PhysicalBinding
import com.romm.androidtv.controller.model.NeutralAxis
import com.romm.androidtv.controller.model.NeutralKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Immutable capture lifecycle exposed to the desktop controller-settings UI.
 *
 * The coordinator is a one-shot state machine mirroring the Android
 * `ControllerBindingCaptureCoordinator`: `beginCapture` starts an "awaiting neutral" phase,
 * then moves to [Capturing] once every observed button and axis is neutral, then emits
 * exactly one terminal state ([Result], [Cancelled], [TimedOut] or [NoDeviceAssigned]).
 */
sealed interface DesktopCaptureState {
    /** No capture in progress. */
    data object Idle : DesktopCaptureState

    /** Waiting for all gamepad buttons/axes to return to neutral. */
    data object AwaitingNeutral : DesktopCaptureState

    /** Neutral, waiting for the first qualifying input. */
    data object Capturing : DesktopCaptureState

    /** A binding was captured. Terminal. */
    data class Result(val binding: PhysicalBinding) : DesktopCaptureState

    /** Capture cancelled (explicit cancel, disconnect, or stop). Terminal. */
    data object Cancelled : DesktopCaptureState

    /** No qualifying input within the timeout. Terminal; nothing saved. */
    data object TimedOut : DesktopCaptureState

    /** No controller is connected. Terminal. */
    data object NoDeviceAssigned : DesktopCaptureState
}

/**
 * Captures a raw controller input for one player port, following the same rules as the
 * Android `ControllerBindingCaptureCoordinator` but fed by JInput POLLING instead of
 * key/motion events:
 *
 * 1. Capture begins only after all buttons/axes are neutral (so the press that opened the
 *    row does not become the binding). The first poll after [beginCapture] establishes the
 *    baseline and is never diffed, so a still-held button cannot be captured.
 * 2. While capture is active, consecutive polls per eligible controller are diffed: the
 *    rising edge of a [NeutralKey] yields a [PhysicalBinding.Key] (its platform code) for
 *    digital targets — first press wins across all eligible controllers.
 * 3. Axis capture requires the axis to first be observed below [NEUTRAL_THRESHOLD], then
 *    cross [ENTER_THRESHOLD] in either direction: digital targets yield a
 *    [PhysicalBinding.AxisDirection] (axis platform code + polarity), analog targets a full
 *    [PhysicalBinding.Axis].
 * 4. Noisy axes that never return to neutral are ignored; once a button press has been seen
 *    during a digital capture, stick deflections cannot capture the row instead.
 * 5. Capture cancels on explicit cancel or when every eligible controller disconnects, and
 *    times out after [DEFAULT_TIMEOUT_MILLIS] with no qualifying input.
 *
 * Eligibility: the caller passes JInput controller ids (from [JInputSource.enumerate]);
 * JInput only ever enumerates game controllers, so — unlike Android — there is no
 * source-mask filter to apply.
 *
 * Thread model: [onPoll] must be called from a single thread (the desktop poll loop), the
 * same assumption as the Android coordinator's event seams.
 */
class DesktopCaptureCoordinator(
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {

    private val _state = MutableStateFlow<DesktopCaptureState>(DesktopCaptureState.Idle)
    val state: StateFlow<DesktopCaptureState> = _state.asStateFlow()

    private val eligibleDeviceIds = mutableSetOf<String>()
    private var target: CaptureTarget = CaptureTarget.Digital

    /**
     * True once a button press has been observed during a Digital (button-row) capture.
     * When set, stick deflections are ignored so a button row can never be silently
     * captured by a stick; the intended key press takes priority.
     */
    private var keyPressedDuringCapture = false

    /** Last polled button set per eligible controller (baseline for rising-edge diffs). */
    private val lastButtons = mutableMapOf<String, Set<NeutralKey>>()

    /** Last observed normalized value per controller/axis pair. */
    private val axisValues = mutableMapOf<Pair<String, NeutralAxis>, Float>()

    /** Axes that have been observed below [NEUTRAL_THRESHOLD] at least once. */
    private val axisSeenNeutral = mutableSetOf<Pair<String, NeutralAxis>>()

    private var timeoutJob: Job? = null

    /** Whether a capture session is currently active (pre- or post-neutral). */
    private val isActive: Boolean
        get() = _state.value == DesktopCaptureState.AwaitingNeutral ||
            _state.value == DesktopCaptureState.Capturing

    /**
     * Begin capturing a binding for [slotIndex] (0..3).
     *
     * @param deviceId the JInput controller id assigned to the selected effective player
     *   port, or null if no controller is connected there.
     * @param target digital or analog capture mode.
     */
    @Suppress("UNUSED_PARAMETER") // slotIndex reserved for caller-facing diagnostics
    fun beginCapture(slotIndex: Int, deviceId: String?, target: CaptureTarget) {
        beginCapture(
            slotIndex = slotIndex,
            deviceIds = setOfNotNull(deviceId),
            target = target,
        )
    }

    /**
     * Begin capture across the given JInput controllers. The first qualifying input wins.
     */
    @Suppress("UNUSED_PARAMETER")
    fun beginCapture(slotIndex: Int, deviceIds: Set<String>, target: CaptureTarget) {
        cancelInternal()
        eligibleDeviceIds += deviceIds
        if (eligibleDeviceIds.isEmpty()) {
            _state.value = DesktopCaptureState.NoDeviceAssigned
            return
        }
        this.target = target
        lastButtons.clear()
        axisValues.clear()
        axisSeenNeutral.clear()
        keyPressedDuringCapture = false
        _state.value = DesktopCaptureState.AwaitingNeutral
        startTimeout()
    }

    /**
     * Cancel capture explicitly (Back, tab change, activity stop). Emits
     * [DesktopCaptureState.Cancelled].
     */
    fun cancel() {
        cancelInternal()
        _state.value = DesktopCaptureState.Cancelled
    }

    /**
     * Notify the coordinator that a controller disconnected. Cancels an in-progress capture
     * once no eligible controllers remain.
     */
    fun onDeviceRemoved(controllerId: String) {
        if (!eligibleDeviceIds.remove(controllerId)) return
        lastButtons.remove(controllerId)
        axisValues.keys.removeAll { (axisControllerId, _) -> axisControllerId == controllerId }
        axisSeenNeutral.removeAll { (axisControllerId, _) -> axisControllerId == controllerId }
        if (eligibleDeviceIds.isEmpty() && isActive) {
            cancel()
        }
    }

    /**
     * Feed one poll of [controllerId] into the capture state machine. Called by the desktop
     * poll loop on every tick while capture is active. Returns `true` when the sample was
     * consumed (capture active and the controller eligible), or `null` to ignore it.
     */
    fun onPoll(controllerId: String, snapshot: JInputControllerState): Boolean? {
        if (!isActive) return null
        if (controllerId !in eligibleDeviceIds) return null

        val previous = lastButtons[controllerId]
        // The first poll after beginCapture is the baseline — it is recorded but never
        // diffed, so a button still held from opening the row cannot be captured.
        val rising: Set<NeutralKey> = if (previous == null) emptySet() else snapshot.buttons - previous
        lastButtons[controllerId] = snapshot.buttons

        // Record every sample so neutral gating sees current state.
        for ((axis, value) in snapshot.axes) {
            val controllerAxis = controllerId to axis
            axisValues[controllerAxis] = value
            if (abs(value) < NEUTRAL_THRESHOLD) axisSeenNeutral.add(controllerAxis)
        }

        if (_state.value == DesktopCaptureState.AwaitingNeutral) {
            // A fresh button press indicates the user intends a button; stick deflections
            // must not capture this button row instead.
            if (target == CaptureTarget.Digital && rising.isNotEmpty()) keyPressedDuringCapture = true
            checkNeutralAndAdvance()
            return true
        }

        // Capturing phase: buttons first — a rising edge wins over any stick deflection in
        // the same poll, and the first press across controllers wins overall.
        if (target == CaptureTarget.Digital) {
            for (key in rising.sortedBy { it.ordinal }) {
                finishWith(PhysicalBinding.Key(key.platformCode))
                return true
            }
        }

        val ordered = snapshot.axes.entries.sortedBy { it.key.ordinal }
        if (target == CaptureTarget.Analog || ordered.size <= 1) {
            for ((axis, value) in ordered) {
                onAxisSample(controllerId, axis, value)
            }
            return true
        }

        // Digital multi-axis: capture only the dominant qualifying deflection so a stick
        // deflection is attributed to its dominant axis and two directions can never share
        // a captured (axis, polarity) key.
        if (keyPressedDuringCapture) return true
        val dominant = ordered
            .filter { (axis, value) ->
                (controllerId to axis) in axisSeenNeutral && abs(value) >= ENTER_THRESHOLD
            }
            .maxByOrNull { abs(it.value) }
        if (dominant != null) {
            finishWith(
                PhysicalBinding.AxisDirection(
                    axis = dominant.key.platformCode,
                    polarity = if (dominant.value > 0f) 1 else -1,
                ),
            )
        }
        return true
    }

    /** Pure per-axis capture check (the single-axis / analog path). */
    private fun onAxisSample(controllerId: String, axis: NeutralAxis, value: Float) {
        val controllerAxis = controllerId to axis
        if (controllerAxis !in axisSeenNeutral) return // noisy axis that never returned to neutral
        if (abs(value) < ENTER_THRESHOLD) return // not yet crossing the enter threshold

        if (target == CaptureTarget.Digital && keyPressedDuringCapture) return

        val binding = when (target) {
            CaptureTarget.Analog -> PhysicalBinding.Axis(axis.platformCode)
            CaptureTarget.Digital -> PhysicalBinding.AxisDirection(
                axis = axis.platformCode,
                polarity = if (value > 0f) 1 else -1,
            )
        }
        finishWith(binding)
    }

    private fun finishWith(binding: PhysicalBinding) {
        _state.value = DesktopCaptureState.Result(binding)
        cancelInternal()
    }

    /** Transition [AwaitingNeutral] -> [Capturing] once every button/axis is neutral. */
    private fun checkNeutralAndAdvance() {
        if (_state.value != DesktopCaptureState.AwaitingNeutral) return
        if (lastButtons.values.any { it.isNotEmpty() }) return
        if (axisValues.values.any { abs(it) >= NEUTRAL_THRESHOLD }) return
        _state.value = DesktopCaptureState.Capturing
    }

    private fun startTimeout() {
        timeoutJob = scope.launch {
            delay(timeoutMillis)
            if (isActive) {
                cancelInternal()
                _state.value = DesktopCaptureState.TimedOut
            }
        }
    }

    private fun cancelInternal() {
        timeoutJob?.cancel()
        timeoutJob = null
        eligibleDeviceIds.clear()
        lastButtons.clear()
        axisValues.clear()
        axisSeenNeutral.clear()
    }

    companion object {
        /** Axis magnitude at or below which the axis counts as "neutral". */
        const val NEUTRAL_THRESHOLD = 0.25f

        /** Axis magnitude that must be crossed (after neutral) to accept it. */
        const val ENTER_THRESHOLD = 0.65f

        /** Spec-mandated 15 s timeout for a capture session. */
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L
    }
}

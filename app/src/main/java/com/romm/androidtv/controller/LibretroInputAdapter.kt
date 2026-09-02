package com.romm.androidtv.controller

import com.romm.androidtv.controller.model.ControllerSlot
import com.romm.androidtv.controller.model.GamepadSnapshot
import com.romm.androidtv.controller.model.LogicalControl
import com.romm.androidtv.controller.model.SlotConnectionState
import com.romm.androidtv.controller.router.ControllerEventRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * One port's worth of Libretro RetroPad input, ready to hand to the native
 * host (LIBRETRO_REFACTOR.md section 9).
 *
 * [buttonsMask] packs the sixteen `RETRO_DEVICE_ID_JOYPAD_*` digital button
 * states as bit flags (bit N set = that button constant's value N is
 * pressed) — this is exactly the format `RETRO_DEVICE_ID_JOYPAD_MASK`
 * queries expect, and individual button queries are just a single-bit test
 * against it.
 *
 * Analog stick values are already clamped to Libretro's signed 16-bit
 * range (roughly -32767..32767, symmetric to avoid an off-by-one at the
 * negative extreme).
 */
data class LibretroPadState(
    val buttonsMask: Int,
    val leftX: Int,
    val leftY: Int,
    val rightX: Int,
    val rightY: Int
) {
    companion object {
        val NEUTRAL = LibretroPadState(buttonsMask = 0, leftX = 0, leftY = 0, rightX = 0, rightY = 0)
    }
}

/**
 * Translates the existing browser-style [GamepadSnapshot] into Libretro's
 * RetroPad button IDs and analog axes (LIBRETRO_REFACTOR.md section 9).
 *
 * This is a pure, unit-tested mapping with no Android/JNI dependency —
 * see `LibretroPadMapperTest`. It does not modify [GamepadSnapshot],
 * [ControllerSlot], or any other part of the existing controller pipeline;
 * it only reads their already-normalized output.
 */
object LibretroPadMapper {
    // libretro.h RETRO_DEVICE_ID_JOYPAD_* values double as bit positions in
    // the RETRO_DEVICE_ID_JOYPAD_MASK bitmask.
    private const val JOYPAD_B = 0
    private const val JOYPAD_Y = 1
    private const val JOYPAD_SELECT = 2
    private const val JOYPAD_START = 3
    private const val JOYPAD_UP = 4
    private const val JOYPAD_DOWN = 5
    private const val JOYPAD_LEFT = 6
    private const val JOYPAD_RIGHT = 7
    private const val JOYPAD_A = 8
    private const val JOYPAD_X = 9
    private const val JOYPAD_L = 10
    private const val JOYPAD_R = 11
    private const val JOYPAD_L2 = 12
    private const val JOYPAD_R2 = 13
    private const val JOYPAD_L3 = 14
    private const val JOYPAD_R3 = 15

    /** Digital press threshold for GamepadSnapshot's 0f/1f (or analog-as-button) button values. */
    private const val PRESS_THRESHOLD = 0.5f

    /**
     * Libretro analog values are signed 16-bit. Using a symmetric range
     * (-32767..32767) rather than the full asymmetric int16 range
     * (-32768..32767) avoids treating the single extra negative value as a
     * special case anywhere downstream.
     */
    private const val ANALOG_MAX = 32767

    fun map(snapshot: GamepadSnapshot): LibretroPadState {
        val buttons = snapshot.buttons
        val axes = snapshot.axes
        var mask = 0

        fun press(bit: Int, pressed: Boolean) {
            if (pressed) mask = mask or (1 shl bit)
        }

        press(JOYPAD_A, buttons[LogicalControl.BUTTON_A.index] > PRESS_THRESHOLD)
        press(JOYPAD_B, buttons[LogicalControl.BUTTON_B.index] > PRESS_THRESHOLD)
        press(JOYPAD_X, buttons[LogicalControl.BUTTON_X.index] > PRESS_THRESHOLD)
        press(JOYPAD_Y, buttons[LogicalControl.BUTTON_Y.index] > PRESS_THRESHOLD)
        press(JOYPAD_L, buttons[LogicalControl.BUTTON_LB.index] > PRESS_THRESHOLD)
        press(JOYPAD_R, buttons[LogicalControl.BUTTON_RB.index] > PRESS_THRESHOLD)
        press(JOYPAD_SELECT, buttons[LogicalControl.BUTTON_SELECT.index] > PRESS_THRESHOLD)
        press(JOYPAD_START, buttons[LogicalControl.BUTTON_START.index] > PRESS_THRESHOLD)
        press(JOYPAD_L3, buttons[LogicalControl.BUTTON_L3.index] > PRESS_THRESHOLD)
        press(JOYPAD_R3, buttons[LogicalControl.BUTTON_R3.index] > PRESS_THRESHOLD)
        press(JOYPAD_UP, buttons[LogicalControl.DPAD_UP.index] > PRESS_THRESHOLD)
        press(JOYPAD_DOWN, buttons[LogicalControl.DPAD_DOWN.index] > PRESS_THRESHOLD)
        press(JOYPAD_LEFT, buttons[LogicalControl.DPAD_LEFT.index] > PRESS_THRESHOLD)
        press(JOYPAD_RIGHT, buttons[LogicalControl.DPAD_RIGHT.index] > PRESS_THRESHOLD)

        // L2/R2 can arrive as a digital button (BUTTON_LT/BUTTON_RT) or as a
        // trigger axis (TRIGGER_LEFT/TRIGGER_RIGHT) depending on the
        // physical controller — section 9 requires handling both.
        press(JOYPAD_L2, buttons[LogicalControl.BUTTON_LT.index] > PRESS_THRESHOLD ||
            axes[LogicalControl.TRIGGER_LEFT.index] > PRESS_THRESHOLD)
        press(JOYPAD_R2, buttons[LogicalControl.BUTTON_RT.index] > PRESS_THRESHOLD ||
            axes[LogicalControl.TRIGGER_RIGHT.index] > PRESS_THRESHOLD)

        // No Y-axis inversion here: GamepadSnapshot's axes are already in
        // the W3C/Android convention (negative = up), which is the same
        // convention Libretro's RETRO_DEVICE_ID_ANALOG_Y expects. Inverting
        // here would flip it a second time (deadzone/inversion is already
        // applied once, upstream, by AxisConfig) — "invert Y only once"
        // (LIBRETRO_REFACTOR.md section 9) means: not here.
        return LibretroPadState(
            buttonsMask = mask,
            leftX = toLibretroAnalog(axes[LogicalControl.AXIS_LX.index]),
            leftY = toLibretroAnalog(axes[LogicalControl.AXIS_LY.index]),
            rightX = toLibretroAnalog(axes[LogicalControl.AXIS_RX.index]),
            rightY = toLibretroAnalog(axes[LogicalControl.AXIS_RY.index])
        )
    }

    private fun toLibretroAnalog(normalized: Float): Int =
        (normalized.coerceIn(-1f, 1f) * ANALOG_MAX).toInt()
}

/**
 * Maps the four-slot [ControllerSlot] list to four Libretro ports (0-based),
 * applying [LibretroPadMapper] to each slot's current snapshot. Connected
 * player slots map directly to Libretro ports. Android TV system devices such
 * as `virtual-remote` and `virtual-search` are filtered by the router before
 * physical assignment, so no port compaction is needed here.
 *
 * Pure and Android-independent — see `LibretroPadMapperTest`.
 */
fun mapControllerSlotsToLibretroPorts(slots: List<ControllerSlot>): List<LibretroPadState> {
    val orderedSlots = effectiveLibretroPortOrder(slots)

    return List(ControllerSlot.SLOT_COUNT) { port ->
        orderedSlots.getOrNull(port)
            ?.takeIf { it.connectionState == SlotConnectionState.CONNECTED }
            ?.let { LibretroPadMapper.map(it.currentSnapshot) }
            ?: LibretroPadState.NEUTRAL
    }
}

/**
 * Feeds the existing four-slot [ControllerEventRouter] output to the native
 * Libretro host's `RETRO_DEVICE_JOYPAD`/`RETRO_DEVICE_ANALOG` input
 * callbacks (LIBRETRO_REFACTOR.md section 9).
 *
 * This class only reads [ControllerEventRouter.slotsFlow] (via
 * [kotlinx.coroutines.flow.StateFlow], already the router's public,
 * unmodified surface) and forwards translated state to [onPortUpdated] —
 * it does not touch any of the router's internal button/axis/slot logic.
 *
 * [onPortUpdated] is expected to hand the four ports' state to a
 * thread-safe native setter (see `NativeLibretroHost.nativeUpdateInputState`)
 * that the emulation thread's `input_state` callback reads from — see that
 * JNI method's doc comment for the cross-thread handoff design.
 */
class LibretroInputAdapter(
    private val router: ControllerEventRouter,
    private val onPortUpdated: (ports: List<LibretroPadState>) -> Unit
) {
    private var job: Job? = null
    private var lastPorts: List<LibretroPadState>? = null

    /** Starts observing [ControllerEventRouter.slotsFlow] in [scope]. Safe to call once. */
    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = router.slotsFlow
            .onEach(::pushSlots)
            .launchIn(scope)
    }

    /**
     * Pushes the router's current state immediately on the calling thread.
     *
     * Gameplay dispatch calls this directly after a consumed Android input
     * event so the native atomics are updated before dispatch returns, rather
     * than depending on the StateFlow collector to run before the core's next
     * input poll.
     */
    fun pushCurrentState() {
        pushSlots(router.slotsFlow.value)
    }

    /** Stops observing. Safe to call even if [start] was never called. */
    fun stop() {
        job?.cancel()
        job = null
        lastPorts = null
    }

    private fun pushSlots(slots: List<ControllerSlot>) {
        val ports = mapControllerSlotsToLibretroPorts(slots)
        if (ports == lastPorts) return
        lastPorts = ports
        onPortUpdated(ports)
    }
}

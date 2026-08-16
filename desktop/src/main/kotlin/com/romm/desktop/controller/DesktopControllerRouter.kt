package com.romm.desktop.controller

import com.romm.androidtv.controller.model.ControllerSlot
import com.romm.androidtv.controller.model.DeviceSignature
import com.romm.androidtv.controller.model.GamepadSnapshot
import com.romm.androidtv.controller.model.LogicalControl
import com.romm.androidtv.controller.model.NeutralAxis
import com.romm.androidtv.controller.model.NeutralKey
import com.romm.androidtv.controller.policy.SlotAssignmentPolicy
import com.romm.desktop.log.DesktopLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Platform-neutral focus action emitted by [DesktopControllerRouter] when the primary
 * (first active) controller produces a focus-relevant button transition.
 *
 * The desktop shell maps these onto the Compose focus system (e.g. `Move(UP)` becomes a
 * spatial `FocusManager.moveFocus(FocusDirection.Up)` call, `Activate` triggers the
 * focused item, `Back` pops the navigation stack). The router itself has zero Compose
 * dependencies so it stays unit-testable on a bare JVM.
 */
sealed interface FocusAction {
    /** D-pad / left-stick directional navigation. */
    data class Move(val direction: Direction) : FocusAction

    /** A/South button — confirm / enter. */
    data object Activate : FocusAction

    /** B/North button — back / escape. */
    data object Back : FocusAction

    enum class Direction { UP, DOWN, LEFT, RIGHT }
}

/**
 * Immutable result of one poll of a physical controller, already translated into the
 * shared neutral model at the ingestion boundary (mirrors the Android
 * `ControllerEventRouter` + adapters split, where the `KeyEvent`/`MotionEvent` ->
 * neutral translation lives in `:app` and the router only sees neutral types).
 *
 * @property buttons neutral keys currently pressed.
 * @property axes neutral axis -> normalized value. Stick axes are in [-1, +1];
 *   trigger axes are in [0, +1] with rest = 0, matching the Android trigger convention.
 */
data class JInputControllerState(
    val buttons: Set<NeutralKey>,
    val axes: Map<NeutralAxis, Float>,
)

/**
 * One physical controller behind the [JInputSource] seam.
 */
interface JInputController {
    /** Stable within a session; used for hot-plug tracking. */
    val id: String

    /** Platform-neutral fingerprint consumed by [SlotAssignmentPolicy]. */
    val signature: DeviceSignature

    /** Read the controller's current state (JInput is poll-based). */
    fun poll(): JInputControllerState
}

/**
 * Testable seam over JInput.
 *
 * JInput's `ControllerEnvironment` is a hard singleton that cannot be injected or
 * faked, so the router depends on this interface instead of JInput directly. The
 * production implementation ([JInputControllerSource]) performs the JInput -> neutral
 * translation (button identifiers -> [NeutralKey], axis identifiers -> [NeutralAxis],
 * min/max/dead-zone normalization) exactly where the Android app performs the
 * `KeyEvent`/`MotionEvent` -> neutral translation.
 */
interface JInputSource {
    /** Enumerate the controllers currently connected. Safe to call on every poll tick. */
    fun enumerate(): List<JInputController>
}

/**
 * Poll-based desktop controller router — the JVM mirror of the Android
 * `ControllerEventRouter`.
 *
 * Responsibilities:
 * 1. Polls [JInputSource] every [pollIntervalMillis] while started.
 * 2. Builds [GamepadSnapshot]s per slot via the shared
 *   [GamepadSnapshot.fromPhysicalInput] (deadzone/inversion applied by the slot's
 *   `ControllerMapping`).
 * 3. Assigns / disconnects / reconnects the four [ControllerSlot]s via the shared
 *   [SlotAssignmentPolicy].
 * 4. Emits platform-neutral [FocusAction]s on rising edges of the focus-relevant
 *   buttons (D-pad, A, B) of the first active slot.
 *
 * Hot-plug: controllers are re-enumerated on every tick; a controller that disappears
 * immediately produces a neutral (EMPTY) snapshot on its slot, so no button can stick.
 *
 * This class imports neither Compose nor JInput: the shell maps [FocusAction] onto
 * the focus navigator, and [JInputControllerSource] owns the JInput boundary.
 *
 * Thread model: [tick] must be invoked from a single thread (the poll loop while
 * started, or the test thread when driven manually) — same assumption as the
 * Android router.
 */
class DesktopControllerRouter(
    private val source: JInputSource,
    private val scope: CoroutineScope,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
) {
    /** The four browser-facing slots (W3C contract), exposed as a [StateFlow]. */
    private val _slots = MutableStateFlow(ControllerSlot.createAllSlots())
    val slots: StateFlow<List<ControllerSlot>> = _slots.asStateFlow()

    /** Focus actions produced by the primary controller (see [FocusAction]). */
    private val _focusActions = MutableSharedFlow<FocusAction>(extraBufferCapacity = 8)
    val focusActions: SharedFlow<FocusAction> = _focusActions.asSharedFlow()

    /** Session-level controller id -> tracked state. */
    private val tracked = LinkedHashMap<String, TrackedController>()

    private val logger: Logger = DesktopLogger.get()

    private var pollJob: Job? = null

    /**
     * Start the poll loop. Idempotent.
     */
    fun start() {
        if (pollJob != null) return
        var lastFailureLogMillis = 0L
        pollJob = scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (t: Throwable) {
                    // A transient source failure must not kill the poll loop. Catch
                    // Throwable (not just Exception): an Error escaping the source — e.g. a
                    // late UnsatisfiedLinkError from the JInput native — would otherwise
                    // terminate the loop silently. Throttled so a persistent failure logs
                    // at most once per [FAILURE_LOG_INTERVAL_MILLIS].
                    val now = System.currentTimeMillis()
                    if (now - lastFailureLogMillis >= FAILURE_LOG_INTERVAL_MILLIS) {
                        lastFailureLogMillis = now
                        logger.log(Level.WARNING, "Controller poll tick failed (loop continues): $t", t)
                    }
                }
                delay(pollIntervalMillis)
            }
        }
    }

    /**
     * Stop the poll loop and clear all state: every assigned slot emits a neutral
     * snapshot and no controller remains tracked (mirrors the Android
     * `deactivate()`).
     */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
        tracked.clear()
        _slots.value = SlotAssignmentPolicy.clearAllSlots(_slots.value)
    }

    /**
     * One poll iteration. Runs on the poll loop while started; also callable
     * directly for deterministic tests.
     */
    internal fun tick() {
        val present = source.enumerate()
        val presentIds = present.mapTo(HashSet()) { it.id }

        // 1. Hot-plug: controllers that disappeared go neutral immediately.
        for (id in tracked.keys.toList()) {
            if (id !in presentIds) {
                val t = tracked.remove(id) ?: continue
                emitIfChanged(SlotAssignmentPolicy.applyDisconnect(_slots.value, t.slotIndex))
            }
        }

        // 2. Connect new controllers and refresh the state of all present ones.
        for (controller in present) {
            var t = tracked[controller.id]
            if (t == null) {
                val slotIndex = SlotAssignmentPolicy.findSlotForDevice(_slots.value, controller.signature)
                if (slotIndex < 0) continue // all slots occupied — device rejected
                emitIfChanged(
                    SlotAssignmentPolicy.applyAssignment(_slots.value, slotIndex, controller.signature)
                )
                t = TrackedController(controller, slotIndex, GamepadSnapshot.EMPTY)
                tracked[controller.id] = t
            }

            val state = controller.poll()
            val slot = _slots.value[t.slotIndex]
            // Slots are created with the default ControllerMapping, whose axisDirections
            // is empty — without the standard d-pad bindings, left-stick X/Y never derives
            // DPAD_* buttons and FocusAction.Move is never emitted. Merge the defaults in
            // for snapshot computation only: the slot's stored mapping is left untouched,
            // so an explicit user remap always wins (withDefaultAxisDirections never
            // clobbers existing entries and returns `this` when nothing is missing).
            val mapping = slot.mapping.withDefaultAxisDirections()
            val snapshot = GamepadSnapshot.fromPhysicalInput(state.buttons, state.axes, mapping)

            val updated = _slots.value.toMutableList()
            updated[t.slotIndex] = slot.updateSnapshot(snapshot)
            emitIfChanged(updated)

            // Only the first active (lowest-index) slot drives focus.
            val isPrimary = t.slotIndex == _slots.value.indexOfFirst { it.isActive }
            if (isPrimary) emitFocusActions(t.previousSnapshot, snapshot)
            t.previousSnapshot = snapshot
        }
    }

    // ---- Focus action emission ----

    /**
     * Emit a [FocusAction] for every focus-relevant button that rose between
     * [previous] and [current] (rising-edge detection suppresses auto-repeat).
     */
    private fun emitFocusActions(previous: GamepadSnapshot, current: GamepadSnapshot) {
        for (i in current.buttons.indices) {
            if (current.buttons[i] <= 0f || previous.buttons[i] > 0f) continue
            val action = when (i) {
                LogicalControl.DPAD_UP.index -> FocusAction.Move(FocusAction.Direction.UP)
                LogicalControl.DPAD_DOWN.index -> FocusAction.Move(FocusAction.Direction.DOWN)
                LogicalControl.DPAD_LEFT.index -> FocusAction.Move(FocusAction.Direction.LEFT)
                LogicalControl.DPAD_RIGHT.index -> FocusAction.Move(FocusAction.Direction.RIGHT)
                LogicalControl.BUTTON_A.index -> FocusAction.Activate
                LogicalControl.BUTTON_B.index -> FocusAction.Back
                else -> continue
            }
            _focusActions.tryEmit(action)
        }
    }

    // ---- Emission suppression (mirrors the Android router) ----

    /**
     * Emit a new slot list only if it differs from the current value.
     * Uses structural equality (content comparison) rather than identity to
     * suppress redundant StateFlow emissions — without this, every fresh list
     * (and fresh FloatArray) would trigger an emission even when nothing changed.
     */
    private fun emitIfChanged(newSlots: List<ControllerSlot>) {
        val current = _slots.value
        if (current.size != newSlots.size) {
            _slots.value = newSlots
            return
        }
        for (i in current.indices) {
            if (!slotContentEquals(current[i], newSlots[i])) {
                _slots.value = newSlots
                return
            }
        }
        // Content is identical — suppress emission.
    }

    /**
     * Compare two slots by content, including FloatArray elements.
     * Data class equals() uses reference equality for arrays, so the
     * button/axis arrays are compared element-by-element.
     */
    private fun slotContentEquals(a: ControllerSlot, b: ControllerSlot): Boolean {
        return a.playerNumber == b.playerNumber &&
            a.preferredSignature == b.preferredSignature &&
            a.connectionState == b.connectionState &&
            a.mapping == b.mapping &&
            a.currentSnapshot.buttons contentEquals b.currentSnapshot.buttons &&
            a.currentSnapshot.axes contentEquals b.currentSnapshot.axes
    }

    private class TrackedController(
        val controller: JInputController,
        val slotIndex: Int,
        var previousSnapshot: GamepadSnapshot,
    )

    companion object {
        /** ~60 Hz poll rate. */
        const val DEFAULT_POLL_INTERVAL_MILLIS = 16L

        /** Minimum interval between poll-failure log lines (throttles a persistent failure). */
        private const val FAILURE_LOG_INTERVAL_MILLIS = 5_000L
    }
}

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

data class ConnectedController(
    val id: String,
    val name: String,
    val slotIndex: Int?,
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
 *   buttons (D-pad, A, B) of every connected controller.
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
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    /** The four browser-facing slots (W3C contract), exposed as a [StateFlow]. */
    private val _slots = MutableStateFlow(ControllerSlot.createAllSlots())
    val slots: StateFlow<List<ControllerSlot>> = _slots.asStateFlow()

    /** Focus actions produced by every connected controller (see [FocusAction]). */
    private val _focusActions = MutableSharedFlow<FocusAction>(extraBufferCapacity = 8)
    val focusActions: SharedFlow<FocusAction> = _focusActions.asSharedFlow()

    private val _connectedControllers = MutableStateFlow<List<ConnectedController>>(emptyList())
    val connectedControllers: StateFlow<List<ConnectedController>> =
        _connectedControllers.asStateFlow()

    /** Session-level controller id -> tracked state. */
    private val tracked = LinkedHashMap<String, TrackedController>()
    private val focusInputStates = LinkedHashMap<String, FocusInputState>()
    private val presentControllers = LinkedHashMap<String, JInputController>()
    private val excludedFromAutomaticAssignment = HashSet<String>()
    private val manuallyEmptySlots = HashSet<Int>()

    private val logger: Logger = DesktopLogger.get()

    private var pollJob: Job? = null
    @Volatile
    private var focusActionsEnabled = true

    /**
     * Enables or suppresses shell navigation actions without stopping controller polling.
     * Capture dialogs use this to own A/B and directional input exclusively.
     */
    fun setFocusActionsEnabled(enabled: Boolean) {
        focusActionsEnabled = enabled
    }

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
    @Synchronized
    fun stop() {
        pollJob?.cancel()
        pollJob = null
        tracked.clear()
        focusInputStates.clear()
        presentControllers.clear()
        excludedFromAutomaticAssignment.clear()
        manuallyEmptySlots.clear()
        _connectedControllers.value = emptyList()
        _slots.value = SlotAssignmentPolicy.clearAllSlots(_slots.value)
    }

    /**
     * Assigns a connected controller to a player slot, or leaves the slot empty when
     * [controllerId] is null. A controller moved from another slot is removed from its
     * old slot, and a controller displaced from [slotIndex] remains available in the
     * picker without being immediately auto-assigned again.
     */
    @Synchronized
    fun assignController(slotIndex: Int, controllerId: String?): Boolean {
        if (slotIndex !in _slots.value.indices) return false
        val selected = controllerId?.let(presentControllers::get)
        if (controllerId != null && selected == null) return false

        val displaced = tracked.values.firstOrNull { it.slotIndex == slotIndex }
        if (displaced != null && displaced.controller.id != controllerId) {
            tracked.remove(displaced.controller.id)
            excludedFromAutomaticAssignment += displaced.controller.id
        }

        val updated = _slots.value.toMutableList()
        if (controllerId == null) {
            updated[slotIndex] = emptySlot(updated[slotIndex])
            manuallyEmptySlots += slotIndex
            emitIfChanged(updated)
            updateConnectedControllers()
            return true
        }

        val existing = tracked[controllerId]
        if (existing != null && existing.slotIndex != slotIndex) {
            updated[existing.slotIndex] = emptySlot(updated[existing.slotIndex])
            manuallyEmptySlots += existing.slotIndex
        }
        val assignment = existing ?: TrackedController(selected!!, slotIndex)
        assignment.slotIndex = slotIndex
        tracked[controllerId] = assignment
        excludedFromAutomaticAssignment -= controllerId
        manuallyEmptySlots -= slotIndex
        updated[slotIndex] = updated[slotIndex].copy(
            preferredSignature = selected!!.signature,
            connectionState = com.romm.androidtv.controller.model.SlotConnectionState.CONNECTED,
            currentSnapshot = GamepadSnapshot.EMPTY,
        )
        emitIfChanged(updated)
        updateConnectedControllers()
        return true
    }

    /** Current controller names in player-slot order; null entries are intentionally empty. */
    @Synchronized
    fun assignedControllerNames(): List<String?> =
        _slots.value.mapIndexed { index, slot ->
            tracked.values.firstOrNull { it.slotIndex == index }
                ?.controller?.signature?.name
                ?.takeIf { slot.isActive }
        }

    /**
     * One poll iteration. Runs on the poll loop while started; also callable
     * directly for deterministic tests.
     */
    @Synchronized
    internal fun tick() {
        val present = source.enumerate()
        val presentIds = present.mapTo(HashSet()) { it.id }
        val focusActionsThisTick = LinkedHashSet<FocusAction>()
        presentControllers.clear()
        present.forEach { presentControllers[it.id] = it }
        excludedFromAutomaticAssignment.retainAll(presentIds)
        focusInputStates.keys.retainAll(presentIds)

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
                if (controller.id !in excludedFromAutomaticAssignment) {
                    val slotIndex = findAutomaticSlot(controller.signature)
                    if (slotIndex >= 0) {
                        emitIfChanged(
                            SlotAssignmentPolicy.applyAssignment(
                                _slots.value,
                                slotIndex,
                                controller.signature,
                            ),
                        )
                        t = TrackedController(controller, slotIndex)
                        tracked[controller.id] = t
                    }
                }
            } else if (t.controller !== controller) {
                // A JInput rescan recreates every native controller wrapper, including
                // devices that never disconnected. Keep the existing player slot but
                // immediately adopt the live wrapper and reset edge/repeat state.
                t = TrackedController(controller, t.slotIndex)
                tracked[controller.id] = t
            }

            val state = controller.poll()
            val slot = t?.let { _slots.value[it.slotIndex] }
            // Slots are created with the default ControllerMapping, whose axisDirections
            // is empty — without the standard d-pad bindings, left-stick X/Y never derives
            // DPAD_* buttons and FocusAction.Move is never emitted. Merge the defaults in
            // for snapshot computation only: the slot's stored mapping is left untouched,
            // so an explicit user remap always wins (withDefaultAxisDirections never
            // clobbers existing entries and returns `this` when nothing is missing).
            val mapping = (slot?.mapping ?: com.romm.androidtv.controller.model.ControllerMapping())
                .withDefaultAxisDirections()
            val snapshot = GamepadSnapshot.fromPhysicalInput(state.buttons, state.axes, mapping)

            if (t != null && slot != null) {
                val updated = _slots.value.toMutableList()
                updated[t.slotIndex] = slot.updateSnapshot(snapshot)
                emitIfChanged(updated)
            }

            val focusState = focusInputStates[controller.id]
                ?.takeIf { it.controller === controller }
                ?: FocusInputState(controller).also { focusInputStates[controller.id] = it }
            if (focusActionsEnabled) {
                collectFocusActions(focusState, snapshot, focusActionsThisTick)
            }
            focusState.previousSnapshot = snapshot
        }
        focusActionsThisTick.forEach(_focusActions::tryEmit)
        updateConnectedControllers()
    }

    private fun findAutomaticSlot(signature: DeviceSignature): Int {
        val reconnect = _slots.value.indexOfFirst { slot ->
            slot.playerNumber - 1 !in manuallyEmptySlots &&
                slot.connectionState ==
                    com.romm.androidtv.controller.model.SlotConnectionState.DISCONNECTED &&
                slot.preferredSignature?.matchesReconnect(signature) == true
        }
        if (reconnect >= 0) return reconnect
        return _slots.value.indexOfFirst { slot ->
            slot.playerNumber - 1 !in manuallyEmptySlots &&
                slot.connectionState ==
                    com.romm.androidtv.controller.model.SlotConnectionState.UNASSIGNED
        }
    }

    private fun emptySlot(slot: ControllerSlot): ControllerSlot =
        ControllerSlot(playerNumber = slot.playerNumber, mapping = slot.mapping)

    private fun updateConnectedControllers() {
        _connectedControllers.value = presentControllers.values.map { controller ->
            ConnectedController(
                id = controller.id,
                name = controllerDisplayName(controller.signature.name)
                    .ifBlank { "Game controller" },
                slotIndex = tracked[controller.id]?.slotIndex,
            )
        }
    }

    // ---- Focus action emission ----

    /**
     * Emit a [FocusAction] for every focus-relevant button that rose between
     * [previous] and [current] (rising-edge detection suppresses auto-repeat).
     */
    private fun collectFocusActions(
        focusState: FocusInputState,
        current: GamepadSnapshot,
        actions: MutableSet<FocusAction>,
    ) {
        val now = clockMillis()
        for ((control, direction) in FOCUS_DIRECTIONS) {
            val pressed = current.buttons[control.index] > 0f
            val wasPressed = focusState.previousSnapshot.buttons[control.index] > 0f
            when {
                !pressed -> focusState.nextDirectionRepeatAt.remove(direction)
                !wasPressed -> {
                    actions += FocusAction.Move(direction)
                    focusState.nextDirectionRepeatAt[direction] =
                        now + DIRECTION_REPEAT_DELAY_MILLIS
                }
                now >= (focusState.nextDirectionRepeatAt[direction] ?: Long.MAX_VALUE) -> {
                    actions += FocusAction.Move(direction)
                    focusState.nextDirectionRepeatAt[direction] =
                        now + DIRECTION_REPEAT_INTERVAL_MILLIS
                }
            }
        }

        for ((control, action) in EDGE_FOCUS_ACTIONS) {
            if (current.buttons[control.index] > 0f &&
                focusState.previousSnapshot.buttons[control.index] <= 0f
            ) {
                actions += action
            }
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
        var slotIndex: Int,
    )

    private class FocusInputState(
        val controller: JInputController,
        var previousSnapshot: GamepadSnapshot = GamepadSnapshot.EMPTY,
    ) {
        val nextDirectionRepeatAt = mutableMapOf<FocusAction.Direction, Long>()
    }

    companion object {
        /** ~60 Hz poll rate. */
        const val DEFAULT_POLL_INTERVAL_MILLIS = 16L

        const val DIRECTION_REPEAT_DELAY_MILLIS = 350L
        const val DIRECTION_REPEAT_INTERVAL_MILLIS = 90L

        /** Minimum interval between poll-failure log lines (throttles a persistent failure). */
        private const val FAILURE_LOG_INTERVAL_MILLIS = 5_000L

        private val FOCUS_DIRECTIONS = listOf(
            LogicalControl.DPAD_UP to FocusAction.Direction.UP,
            LogicalControl.DPAD_DOWN to FocusAction.Direction.DOWN,
            LogicalControl.DPAD_LEFT to FocusAction.Direction.LEFT,
            LogicalControl.DPAD_RIGHT to FocusAction.Direction.RIGHT,
        )

        private val EDGE_FOCUS_ACTIONS = listOf(
            LogicalControl.BUTTON_A to FocusAction.Activate,
            LogicalControl.BUTTON_B to FocusAction.Back,
        )
    }
}

package com.romm.androidtv.controller.router

import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.romm.androidtv.controller.model.*
import com.romm.androidtv.controller.policy.AxisMappingPolicy
import com.romm.androidtv.controller.policy.EventConsumptionPolicy
import com.romm.androidtv.controller.policy.RemoteSlotPolicy
import com.romm.androidtv.controller.policy.SlotAssignmentPolicy
import com.romm.androidtv.controller.policy.SourceFilterPolicy
import com.romm.androidtv.controller.util.AxisNormalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lifecycle-aware controller event router.
 *
 * Responsibilities:
 * 1. Observes [android.hardware.input.InputManager.InputDeviceListener] for connect/disconnect.
 * 2. Filters SOURCE_GAMEPAD, SOURCE_JOYSTICK, and SOURCE_DPAD (controller-originated).
 * 3. Captures KeyEvent button transitions and MotionEvent axes/hat switches.
 * 4. Normalizes MotionRange values, applies deadzones/inversion/trigger normalization.
 * 5. Maintains exactly four browser-facing [ControllerSlot]s.
 * 6. Produces a [StateFlow] of slot states for Compose UI consumption.
 * 7. Enumerates already-connected controllers on startup and lifecycle resume.
 *
 * Does NOT persist beyond current process (Phase 0 scope).
 */
class ControllerEventRouter : android.hardware.input.InputManager.InputDeviceListener {
    /** Current four-slot browser contract, exposed as StateFlow. */
    private val _slotsFlow = MutableStateFlow(ControllerSlot.createAllSlots())
    val slotsFlow: StateFlow<List<ControllerSlot>> = _slotsFlow.asStateFlow()

    /** Whether the router is actively capturing events. */
    private var isActive = false

    /** Session-level device ID -> slot index assignment map. */
    private val deviceIdToSlotIndex = mutableMapOf<Int, Int>()

    /** Currently pressed keyCodes per device ID (for incremental snapshot building). */
    private val pressedKeysPerDevice = mutableMapOf<Int, MutableSet<Int>>()

    /** Hat-derived D-pad keyCodes per device ID, tracked separately from key events. */
    private val hatDpadKeysPerDevice = mutableMapOf<Int, MutableSet<Int>>()

    /** Current axis values per device ID. */
    private val axisValuesPerDevice = mutableMapOf<Int, MutableMap<Int, Float>>()

    /** Capability-resolved physical-to-logical axes per device ID. */
    private val resolvedAxesPerDevice = mutableMapOf<Int, Map<Int, LogicalControl>>()

    /** Device signatures observed this session. */
    private val deviceIdToSignature = mutableMapOf<Int, DeviceSignature>()

    private val virtualRemoteDeviceId = Int.MIN_VALUE

    /**
     * Enumerate already-connected input devices and assign controllers.
     * Must be called after registerInputDeviceListener() to catch devices
     * that were connected before the listener was registered, and on lifecycle
     * resume (onStart) when deactivate() cleared device mappings.
     *
     * @param inputManager unused (retained for API compatibility; uses static InputDevice.getDeviceIds())
     */
    @Suppress("UNUSED_PARAMETER")
    fun enumerateExistingDevices(inputManager: InputManager) {
        // InputManager.inputDevices is not a public API; use InputDevice.getDeviceIds() instead.
        val deviceIds = android.view.InputDevice.getDeviceIds()
        for (deviceId in deviceIds) {
            // Skip if we're already tracking this device (prevents double-assignment)
            if (deviceIdToSignature.containsKey(deviceId)) continue
            onInputDeviceAdded(deviceId)
        }
    }

    // ---- Lifecycle management ----

    /**
     * Register with a lifecycle owner. Router activates on STARTED, deactivates on STOPPED.
     * On deactivation, all assigned slots emit neutral (EMPTY) snapshots to prevent stuck inputs.
     * On STARTED, already-connected controllers are re-enumerated and reassigned.
     */
    fun attachLifecycle(owner: LifecycleOwner) {
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                isActive = true
            }

            override fun onStop(owner: LifecycleOwner) {
                deactivate()
            }
        })
    }

    /**
     * Register with a raw lifecycle object (for testing).
     */
    fun setActive(active: Boolean) {
        if (active) {
            isActive = true
        } else {
            deactivate()
        }
    }

    /**
     * Deactivate the router: clear per-device state, emit neutral snapshots
     * for all assigned slots, and remove device-to-slot mappings.
     */
    private fun deactivate() {
        isActive = false

        // Emit neutral snapshots for all currently-connected slots
        val currentSlots = _slotsFlow.value
        val clearedSlots = RemoteSlotPolicy.removeRemoteReservation(
            SlotAssignmentPolicy.clearAllSlots(currentSlots)
        )
        _slotsFlow.value = clearedSlots

        // Clear per-device state
        pressedKeysPerDevice.clear()
        hatDpadKeysPerDevice.clear()
        axisValuesPerDevice.clear()
        resolvedAxesPerDevice.clear()
        deviceIdToSlotIndex.clear()
        deviceIdToSignature.clear()
    }

    // ---- InputDeviceListener callbacks ----

    override fun onInputDeviceAdded(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId) ?: return

        val sources = device.sources
        if (!SourceFilterPolicy.isControllerSource(sources)) return
        if (isTvRemoteByAxes(device, sources)) return

        val signature = DeviceSignature.from(device)
        deviceIdToSignature[deviceId] = signature
        pressedKeysPerDevice[deviceId] = mutableSetOf()
        hatDpadKeysPerDevice[deviceId] = mutableSetOf()
        axisValuesPerDevice[deviceId] = mutableMapOf()

        // Assign to slot using deterministic policy
        assignDeviceToSlot(deviceId, signature)
        val slotIndex = deviceIdToSlotIndex[deviceId]
        val mapping = slotIndex?.let { _slotsFlow.value[it].mapping } ?: ControllerMapping()
        resolvedAxesPerDevice[deviceId] = resolveAxes(device, mapping)
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId) ?: return
        if (!SourceFilterPolicy.isControllerSource(device.sources)) return
        deviceIdToSignature[deviceId] = DeviceSignature.from(device)
        val slotIndex = deviceIdToSlotIndex[deviceId]
        val mapping = slotIndex?.let { _slotsFlow.value[it].mapping } ?: ControllerMapping()
        resolvedAxesPerDevice[deviceId] = resolveAxes(device, mapping)
        axisValuesPerDevice[deviceId]?.clear()
        if (slotIndex != null) rebuildSnapshotForDevice(deviceId)
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        // Only process devices we were tracking
        if (deviceIdToSignature.remove(deviceId) == null) return
        pressedKeysPerDevice.remove(deviceId)
        hatDpadKeysPerDevice.remove(deviceId)
        axisValuesPerDevice.remove(deviceId)
        resolvedAxesPerDevice.remove(deviceId)

        val slotIdx = deviceIdToSlotIndex.remove(deviceId)
        if (slotIdx != null) {
            val currentSlots = _slotsFlow.value
            val updatedSlots = SlotAssignmentPolicy.applyDisconnect(currentSlots, slotIdx)
            emitIfChanged(updatedSlots)
        }
    }

    /**
     * Called by the Activity's dispatch layer to route a KeyEvent.
     * Returns true if this event was consumed by a controller slot.
     *
     * KEY_DOWN events with repeatCount > 0 are suppressed: they carry no new
     * state information (the key is already pressed) and would otherwise cause
     * spurious StateFlow emissions and bridge pushes.
     *
     * TV remote events are routed into the first available empty/disconnected
     * slot (0-3) to avoid creating a fifth browser slot.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isActive) return false
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false

        val deviceId = event.deviceId
        val device = InputDevice.getDevice(deviceId) ?: return false

        val sources = device.sources
        if (!SourceFilterPolicy.isControllerSource(sources)) return false

        val keyCode = event.keyCode

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) {
            return isTvRemoteByAxes(device, sources) || EventConsumptionPolicy.shouldConsumeKeyEvent(keyCode)
        }

        if (isTvRemoteByAxes(device, sources)) {
            return routeTvRemoteKey(event.action, keyCode, event.repeatCount)
        }

        if (!EventConsumptionPolicy.shouldConsumeKeyEvent(keyCode)) return false

        pressedKeysPerDevice.getOrPut(deviceId) { mutableSetOf() }
        val isDown = event.action == KeyEvent.ACTION_DOWN

        if (isDown) {
            pressedKeysPerDevice[deviceId]!!.add(keyCode)
        } else {
            pressedKeysPerDevice[deviceId]!!.remove(keyCode)
        }

        rebuildSnapshotForDevice(deviceId)
        return true
    }

    /**
     * Handle TV remote key event by routing into an available physical slot.
     * Returns true if the event was consumed.
     */
    fun routeTvRemoteKey(action: Int, keyCode: Int, repeatCount: Int = 0): Boolean {
        if (!isActive) return false
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false

        val logicalKeyCode = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> KeyEvent.KEYCODE_DPAD_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> KeyEvent.KEYCODE_BUTTON_A
            else -> return false
        }

        if (action == KeyEvent.ACTION_DOWN && repeatCount > 0) return true

        val tvDeviceId = virtualRemoteDeviceId
        pressedKeysPerDevice.getOrPut(tvDeviceId) { mutableSetOf() }
        hatDpadKeysPerDevice.getOrPut(tvDeviceId) { mutableSetOf() }

        if (deviceIdToSlotIndex[tvDeviceId] == null) {
            val availableSlotIdx = findAvailableSlot()
            if (availableSlotIdx < 0) return false
            deviceIdToSlotIndex[tvDeviceId] = availableSlotIdx
            val currentSlots = _slotsFlow.value
            val updatedSlots = SlotAssignmentPolicy.applyAssignment(
                currentSlots, availableSlotIdx, DeviceSignature.VIRTUAL_REMOTE
            )
            emitIfChanged(updatedSlots)
        }

        val keys = pressedKeysPerDevice[tvDeviceId]!!
        if (action == KeyEvent.ACTION_DOWN) {
            keys.add(logicalKeyCode)
        } else {
            keys.remove(logicalKeyCode)
        }

        rebuildSnapshotForDevice(tvDeviceId)
        return true
    }

    /**
     * Find the first available (empty/disconnected) slot index.
     * Returns -1 if all slots are occupied.
     */
    private fun findAvailableSlot(): Int {
        return _slotsFlow.value.indexOfFirst {
            it.connectionState == SlotConnectionState.UNASSIGNED
        }
    }

    /**
     * Called by the Activity's dispatch layer to route a MotionEvent.
     * Returns true if this event was consumed by a controller slot.
     *
     * Axes not present in this event are reset to zero (neutral). MotionEvents
     * only carry data for axes that actually changed; stale values from prior
     * events must be cleared so return-to-center is correctly reflected.
     *
     * TV remote motion events are not processed (TV remotes don't have motion axes).
     */
    fun onMotionEvent(event: MotionEvent): Boolean {
        if (!isActive) return false
        val deviceId = event.deviceId
        val device = InputDevice.getDevice(deviceId) ?: return false

        val sources = event.source
        if (!SourceFilterPolicy.isControllerSource(sources)) return false
        // Skip TV remote motion events (they don't have motion axes)
        if (isTvRemoteByAxes(device, sources)) return false

        axisValuesPerDevice.getOrPut(deviceId) { mutableMapOf() }
        val axes = axisValuesPerDevice[deviceId]!!

        val slotIndex = deviceIdToSlotIndex[deviceId] ?: return false
        val mapping = _slotsFlow.value[slotIndex].mapping
        val resolvedAxes = resolvedAxesPerDevice[deviceId]
            ?: resolveAxes(device, mapping).also { resolvedAxesPerDevice[deviceId] = it }

        for (axisConstant in resolvedAxes.keys) {
            axes[axisConstant] = 0f
        }

        // Process history items first (coalesce intermediate states)
        val historySize = event.historySize
        for (h in 0 until historySize) {
            processMotionAxes(device, event, axes, resolvedAxes, h)
        }

        // Process current event
        processMotionAxes(device, event, axes, resolvedAxes, -1)

        // Handle hat switch -> D-pad button mapping.
        // Hat-derived D-pad state is tracked SEPARATELY from key-derived D-pad state
        // to prevent neutral hat events from erasing held key D-pad state.
        val hatX = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y)
        updateHatButtons(deviceId, hatX, hatY)

        rebuildSnapshotForDevice(deviceId)
        return true
    }

    /**
     * Process axis values from a MotionEvent at the given history index.
     * Uses -1 for the current event.
     *
     * All axes are always stored (including zeros). The caller resets axes
     * to zero before calling this; we overwrite with actual values from the event.
     */
    private fun processMotionAxes(
        device: InputDevice,
        event: MotionEvent,
        axes: MutableMap<Int, Float>,
        resolvedAxes: Map<Int, LogicalControl>,
        historyIndex: Int
    ) {
        for ((axisConstant, logicalControl) in resolvedAxes) {
            val rawValue = if (historyIndex >= 0) {
                event.getHistoricalAxisValue(axisConstant, historyIndex)
            } else {
                event.getAxisValue(axisConstant)
            }

            val normalized = normalizeAxis(device, axisConstant, logicalControl, rawValue)
            axes[axisConstant] = normalized
        }
    }

    // ---- Slot assignment ----

    /**
     * Assign a device to a slot. Delegates to [SlotAssignmentPolicy].
     */
    private fun assignDeviceToSlot(deviceId: Int, signature: DeviceSignature) {
        var currentSlots = _slotsFlow.value
        var slotIndex = SlotAssignmentPolicy.findSlotForDevice(currentSlots, signature)
        val remoteIndex = deviceIdToSlotIndex[virtualRemoteDeviceId]

        if (
            remoteIndex != null &&
            (
                slotIndex < 0 ||
                    (
                        currentSlots[slotIndex].connectionState == SlotConnectionState.UNASSIGNED &&
                            slotIndex > remoteIndex
                    )
                )
        ) {
            val result = RemoteSlotPolicy.makeRoomForPhysicalController(currentSlots)
            currentSlots = result.slots
            if (result.remoteSlotIndex == null) {
                deviceIdToSlotIndex.remove(virtualRemoteDeviceId)
                pressedKeysPerDevice.remove(virtualRemoteDeviceId)
                hatDpadKeysPerDevice.remove(virtualRemoteDeviceId)
            } else {
                deviceIdToSlotIndex[virtualRemoteDeviceId] = result.remoteSlotIndex
            }
            slotIndex = SlotAssignmentPolicy.findSlotForDevice(currentSlots, signature)
        }

        if (slotIndex < 0) return // No available slot

        deviceIdToSlotIndex[deviceId] = slotIndex
        val updatedSlots = SlotAssignmentPolicy.applyAssignment(
            currentSlots, slotIndex, signature
        )
        emitIfChanged(updatedSlots)
    }

    /**
     * Update a single slot by index. Thread-safe via main-thread assumption.
     */
    private fun updateSlot(index: Int, transform: (ControllerSlot) -> ControllerSlot) {
        val slots = _slotsFlow.value.toMutableList()
        slots[index] = transform(slots[index])
        emitIfChanged(slots)
    }

    /**
     * Emit a new slot list only if it differs from the current value.
     * Uses structural equality (content comparison) rather than identity
     * to suppress redundant StateFlow emissions. Without this, every
     * toMutableList() call produces a new list instance that triggers
     * an emission even when no actual state changed.
     */
    private fun emitIfChanged(newSlots: List<ControllerSlot>) {
        val current = _slotsFlow.value
        if (current.size != newSlots.size) {
            _slotsFlow.value = newSlots
            return
        }
        for (i in current.indices) {
            if (!slotContentEquals(current[i], newSlots[i])) {
                _slotsFlow.value = newSlots
                return
            }
        }
        // Content is identical — suppress emission
    }

    /**
     * Compare two slots by content, including FloatArray elements.
     * Data class equals() uses reference equality for arrays, so we
     * must compare button/axis arrays element-by-element.
     */
    private fun slotContentEquals(a: ControllerSlot, b: ControllerSlot): Boolean {
        return a.playerNumber == b.playerNumber &&
            a.preferredSignature == b.preferredSignature &&
            a.connectionState == b.connectionState &&
            a.mapping == b.mapping &&
            a.currentSnapshot.buttons contentEquals b.currentSnapshot.buttons &&
            a.currentSnapshot.axes contentEquals b.currentSnapshot.axes
    }

    // ---- Snapshot building ----

    private fun rebuildSnapshotForDevice(deviceId: Int) {
        val slotIdx = deviceIdToSlotIndex[deviceId] ?: return
        val slot = _slotsFlow.value[slotIdx]
        val mapping = if (deviceId == virtualRemoteDeviceId) {
            slot.mapping
        } else {
            slot.mapping.copy(axes = resolvedAxesPerDevice[deviceId] ?: emptyMap())
        }

        val pressedKeys = pressedKeysPerDevice[deviceId] ?: emptySet()
        val hatDpadKeys = hatDpadKeysPerDevice[deviceId] ?: emptySet()
        // Merge hat-derived and key-derived D-pad state
        val mergedKeys = pressedKeys + hatDpadKeys
        val axisValues = axisValuesPerDevice[deviceId] ?: emptyMap()

        val snapshot = GamepadSnapshot.fromPhysicalInput(mergedKeys, axisValues, mapping)
        updateSlot(slotIdx) { it.updateSnapshot(snapshot) }
    }

    /**
     * Translate hat switch values to D-pad button presses.
     * Hat values are -1.0, 0.0, or +1.0 for left/neutral/right or up/neutral/down.
     *
     * CRITICAL: Hat-derived D-pad state is tracked SEPARATELY from key-derived
     * D-pad state. This prevents neutral hat events from erasing held key D-pad
     * state. The two sources are merged when building the snapshot.
     */
    private fun updateHatButtons(deviceId: Int, hatX: Float, hatY: Float) {
        hatDpadKeysPerDevice.getOrPut(deviceId) { mutableSetOf() }
        val hatKeys = hatDpadKeysPerDevice[deviceId]!!

        // Clear existing hat-derived D-pad keys
        hatKeys.clear()

        // Apply hat-derived D-pad state
        if (hatY < -0.5f) hatKeys.add(android.view.KeyEvent.KEYCODE_DPAD_UP)
        if (hatY > 0.5f) hatKeys.add(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        if (hatX < -0.5f) hatKeys.add(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        if (hatX > 0.5f) hatKeys.add(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
    }

    // ---- Axis normalization ----

    /**
     * Normalize a raw axis value using the device's MotionRange.
     * Delegates to [AxisNormalizer] for correct flat-region handling.
     */
    private fun normalizeAxis(
        device: InputDevice,
        axisConstant: Int,
        logicalControl: LogicalControl,
        rawValue: Float
    ): Float {
        val ranges = device.motionRanges
        val range = ranges?.find { it.axis == axisConstant }
        if (range == null) return AxisNormalizer.normalizeFallback(rawValue)

        return if (
            logicalControl == LogicalControl.TRIGGER_LEFT ||
            logicalControl == LogicalControl.TRIGGER_RIGHT
        ) {
            AxisNormalizer.normalizeTrigger(rawValue, range.min, range.max, range.flat)
        } else {
            AxisNormalizer.normalize(rawValue, range.min, range.max, range.flat)
        }
    }

    private fun resolveAxes(
        device: InputDevice,
        mapping: ControllerMapping
    ): Map<Int, LogicalControl> {
        val supportedAxes = device.motionRanges.orEmpty().mapTo(mutableSetOf()) { it.axis }
        return AxisMappingPolicy.resolve(supportedAxes, mapping.axes)
    }

    /**
     * Check whether a device is likely a TV remote based on its motion ranges.
     * A device with DPAD source but no joystick axes is a remote.
     */
    private fun isTvRemoteByAxes(device: InputDevice, sources: Int): Boolean {
        val ranges = device.motionRanges ?: return SourceFilterPolicy.isTvRemote(sources, 0)

        val joystickAxisCount = ranges.count { r ->
            r.axis == android.view.MotionEvent.AXIS_X ||
            r.axis == android.view.MotionEvent.AXIS_Y ||
            r.axis == android.view.MotionEvent.AXIS_RX ||
            r.axis == android.view.MotionEvent.AXIS_RY ||
            r.axis == android.view.MotionEvent.AXIS_Z ||
            r.axis == android.view.MotionEvent.AXIS_RZ
        }

        return SourceFilterPolicy.isTvRemote(sources, joystickAxisCount)
    }

    /**
     * Swap A/B buttons for a given slot. Used by the diagnostics screen.
     */
    fun swapAB(slotIndex: Int) {
        if (slotIndex !in 0..3) return
        replaceMapping(slotIndex) { ControllerMapping.swapAB(it) }
    }

    /**
     * Reset a slot's mapping to defaults.
     */
    fun resetMapping(slotIndex: Int) {
        if (slotIndex !in 0..3) return
        replaceMapping(slotIndex) { ControllerMapping() }
    }

    private fun replaceMapping(
        slotIndex: Int,
        transform: (ControllerMapping) -> ControllerMapping
    ) {
        val slot = _slotsFlow.value[slotIndex]
        val newMapping = transform(slot.mapping)
        val deviceId = deviceIdToSlotIndex.entries
            .firstOrNull { it.value == slotIndex }
            ?.key

        if (deviceId != null && deviceId != virtualRemoteDeviceId) {
            val device = InputDevice.getDevice(deviceId)
            if (device != null) {
                resolvedAxesPerDevice[deviceId] = resolveAxes(device, newMapping)
            }
        }

        val pressedKeys = deviceId?.let { pressedKeysPerDevice[it] }.orEmpty()
        val hatKeys = deviceId?.let { hatDpadKeysPerDevice[it] }.orEmpty()
        val axisValues = deviceId?.let { axisValuesPerDevice[it] }.orEmpty()
        val effectiveMapping = if (deviceId == null || deviceId == virtualRemoteDeviceId) {
            newMapping
        } else {
            newMapping.copy(axes = resolvedAxesPerDevice[deviceId] ?: emptyMap())
        }
        val snapshot = GamepadSnapshot.fromPhysicalInput(
            pressedKeys + hatKeys,
            axisValues,
            effectiveMapping
        )
        updateSlot(slotIndex) { it.remap(newMapping).updateSnapshot(snapshot) }
    }

}

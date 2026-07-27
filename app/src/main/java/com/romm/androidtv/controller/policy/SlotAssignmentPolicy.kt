package com.romm.androidtv.controller.policy

import com.romm.androidtv.controller.model.ControllerSlot
import com.romm.androidtv.controller.model.DeviceSignature
import com.romm.androidtv.controller.model.SlotConnectionState

/**
 * Pure, framework-free slot assignment policy.
 *
 * Decides which slot index a newly-connected device should occupy.
 * The router delegates to this policy; the policy itself has no Android
 * framework dependencies and can be unit-tested on the JVM.
 *
 * Algorithm:
 * 1. If a DISCONNECTED slot exists whose signature matches the device's
 *    reconnect key, reassign there (preserves mapping across hot-plug).
 * 2. Otherwise, occupy the first UNASSIGNED slot in order.
 * 3. If all slots are CONNECTED or DISCONNECTED with different signatures,
 *    return -1 (device rejected — no available slot).
 */
object SlotAssignmentPolicy {

    /**
     * @return the slot index to assign, or -1 if no slot is available.
     */
    fun findSlotForDevice(
        slots: List<ControllerSlot>,
        signature: DeviceSignature
    ): Int {
        // Step 1: reconnect to existing disconnected slot with matching identity
        val reconnectIdx = slots.indexOfFirst { slot ->
            slot.connectionState == SlotConnectionState.DISCONNECTED &&
                slot.preferredSignature != null &&
                slot.preferredSignature.matchesReconnect(signature)
        }
        if (reconnectIdx >= 0) return reconnectIdx

        // Step 2: first unassigned slot
        val unassignedIdx = slots.indexOfFirst {
            it.connectionState == SlotConnectionState.UNASSIGNED
        }
        if (unassignedIdx >= 0) return unassignedIdx

        // Step 3: no slot available
        return -1
    }

    /**
     * Apply the assignment decision to a list of slots.
     * Returns a new immutable list with the updated slot.
     */
    fun applyAssignment(
        slots: List<ControllerSlot>,
        slotIndex: Int,
        signature: DeviceSignature
    ): List<ControllerSlot> {
        if (slotIndex < 0 || slotIndex >= slots.size) return slots

        val list = slots.toMutableList()
        val existing = list[slotIndex]

        if (existing.connectionState == SlotConnectionState.DISCONNECTED) {
            // Reconnect: preserve existing signature/mapping, restore CONNECTED
            list[slotIndex] = existing.reconnect()
        } else if (existing.connectionState == SlotConnectionState.UNASSIGNED) {
            // New assignment
            list[slotIndex] = existing.assign(signature)
        }
        // If slot is already CONNECTED, do nothing

        return list
    }

    /**
     * Mark the slot for a device as disconnected. Emits neutral snapshot.
     */
    fun applyDisconnect(
        slots: List<ControllerSlot>,
        slotIndex: Int
    ): List<ControllerSlot> {
        if (slotIndex < 0 || slotIndex >= slots.size) return slots
        val list = slots.toMutableList()
        list[slotIndex] = list[slotIndex].disconnect()
        return list
    }

    /**
     * Clear all assigned slots to neutral (UNASSIGNED or DISCONNECTED with empty snapshot).
     * Used on router stop / focus loss.
     */
    fun clearAllSlots(slots: List<ControllerSlot>): List<ControllerSlot> {
        return slots.map { slot ->
            when (slot.connectionState) {
                SlotConnectionState.CONNECTED -> slot.disconnect()
                SlotConnectionState.DISCONNECTED -> slot
                SlotConnectionState.UNASSIGNED -> slot
            }
        }
    }
}

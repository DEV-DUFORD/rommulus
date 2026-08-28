package com.romm.androidtv.controller.policy

import com.romm.androidtv.controller.model.ControllerSlot
import com.romm.androidtv.controller.model.DeviceSignature
import com.romm.androidtv.controller.model.SlotConnectionState

object RemoteSlotPolicy {
    data class Result(
        val slots: List<ControllerSlot>,
        val remoteSlotIndex: Int?
    )

    /**
     * Move the virtual remote to the highest unassigned slot, or remove it when
     * all other slots are reserved for physical controllers.
     */
    fun makeRoomForPhysicalController(slots: List<ControllerSlot>): Result {
        val remoteIndex = slots.indexOfFirst {
            it.connectionState == SlotConnectionState.CONNECTED &&
                it.preferredSignature == DeviceSignature.VIRTUAL_REMOTE
        }
        if (remoteIndex < 0) return Result(slots, null)

        val targetIndex = slots.indices
            .filter { it != remoteIndex }
            .lastOrNull { slots[it].connectionState == SlotConnectionState.UNASSIGNED }

        val updated = slots.toMutableList()
        val remoteSlot = updated[remoteIndex]
        updated[remoteIndex] = ControllerSlot(playerNumber = remoteIndex + 1)

        if (targetIndex != null) {
            updated[targetIndex] = remoteSlot.copy(playerNumber = targetIndex + 1)
        }

        return Result(updated, targetIndex)
    }

    fun removeRemoteReservation(slots: List<ControllerSlot>): List<ControllerSlot> =
        slots.mapIndexed { index, slot ->
            if (slot.preferredSignature == DeviceSignature.VIRTUAL_REMOTE) {
                ControllerSlot(playerNumber = index + 1)
            } else {
                slot
            }
        }
}

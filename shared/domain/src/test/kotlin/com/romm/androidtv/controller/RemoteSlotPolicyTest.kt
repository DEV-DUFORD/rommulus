package com.romm.androidtv.controller

import com.romm.androidtv.controller.model.ControllerSlot
import com.romm.androidtv.controller.model.DeviceSignature
import com.romm.androidtv.controller.model.SlotConnectionState
import com.romm.androidtv.controller.policy.RemoteSlotPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RemoteSlotPolicyTest {
    @Test
    fun `moves remote from player one to highest unassigned slot`() {
        val slots = ControllerSlot.createAllSlots().toMutableList()
        slots[0] = slots[0].assign(DeviceSignature.VIRTUAL_REMOTE)

        val result = RemoteSlotPolicy.makeRoomForPhysicalController(slots)

        assertThat(result.remoteSlotIndex).isEqualTo(3)
        assertThat(result.slots[0].connectionState).isEqualTo(SlotConnectionState.UNASSIGNED)
        assertThat(result.slots[3].preferredSignature).isEqualTo(DeviceSignature.VIRTUAL_REMOTE)
        assertThat(result.slots).hasSize(4)
    }

    @Test
    fun `removes remote when all other slots are reserved`() {
        val physical = DeviceSignature("physical", 1, 1, "Controller")
        val slots = listOf(
            ControllerSlot(1).assign(DeviceSignature.VIRTUAL_REMOTE),
            ControllerSlot(2).assign(physical),
            ControllerSlot(3).assign(physical),
            ControllerSlot(4).assign(physical)
        )

        val result = RemoteSlotPolicy.makeRoomForPhysicalController(slots)

        assertThat(result.remoteSlotIndex).isNull()
        assertThat(result.slots[0].connectionState).isEqualTo(SlotConnectionState.UNASSIGNED)
        assertThat(result.slots.none {
            it.preferredSignature == DeviceSignature.VIRTUAL_REMOTE
        }).isTrue()
    }

    @Test
    fun `does not overwrite disconnected physical reservations`() {
        val physical = DeviceSignature("physical", 1, 1, "Controller")
        val slots = listOf(
            ControllerSlot(1).assign(DeviceSignature.VIRTUAL_REMOTE),
            ControllerSlot(2).assign(physical).disconnect(),
            ControllerSlot(3).assign(physical).disconnect(),
            ControllerSlot(4).assign(physical).disconnect()
        )

        val result = RemoteSlotPolicy.makeRoomForPhysicalController(slots)

        assertThat(result.remoteSlotIndex).isNull()
        assertThat(result.slots.drop(1).all {
            it.connectionState == SlotConnectionState.DISCONNECTED
        }).isTrue()
    }

    @Test
    fun `lifecycle cleanup removes a disconnected remote reservation`() {
        val slots = ControllerSlot.createAllSlots().toMutableList()
        slots[0] = slots[0].assign(DeviceSignature.VIRTUAL_REMOTE).disconnect()

        val result = RemoteSlotPolicy.removeRemoteReservation(slots)

        assertThat(result[0].connectionState).isEqualTo(SlotConnectionState.UNASSIGNED)
        assertThat(result[0].preferredSignature).isNull()
    }
}

package com.romm.androidtv.controller

import com.romm.androidtv.controller.model.*
import com.romm.androidtv.controller.policy.SlotAssignmentPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for SlotAssignmentPolicy.
 * Validates assignment, disconnect, reconnect, and clear logic.
 */
@DisplayName("SlotAssignmentPolicy — pure routing policy")
class SlotAssignmentPolicyTest {

    @Nested
    @DisplayName("findSlotForDevice — slot selection")
    inner class FindSlotTests {
        @Test
        @DisplayName("first device gets slot 0")
        fun `first device`() {
            val slots = ControllerSlot.createAllSlots()
            val sig = DeviceSignature("vid:0001-pid:0001-src:1689", 1, 1, "D1")
            assertThat(SlotAssignmentPolicy.findSlotForDevice(slots, sig)).isEqualTo(0)
        }

        @Test
        @DisplayName("second device gets slot 1")
        fun `second device`() {
            val slots = ControllerSlot.createAllSlots()
            val sig1 = DeviceSignature("vid:0001-pid:0001-src:1689", 1, 1, "D1")
            val s1 = SlotAssignmentPolicy.applyAssignment(slots, 0, sig1)
            val sig2 = DeviceSignature("vid:0002-pid:0002-src:1689", 2, 2, "D2")
            assertThat(SlotAssignmentPolicy.findSlotForDevice(s1, sig2)).isEqualTo(1)
        }

        @Test
        @DisplayName("disconnected device reconnects to same slot")
        fun `reconnect same slot`() {
            val slots = ControllerSlot.createAllSlots()
            val sig = DeviceSignature("vid:0001-pid:0001-src:1689", 1, 1, "D1")

            val assigned = SlotAssignmentPolicy.applyAssignment(slots, 0, sig)
            val disconnected = SlotAssignmentPolicy.applyDisconnect(assigned, 0)

            assertThat(SlotAssignmentPolicy.findSlotForDevice(disconnected, sig)).isEqualTo(0)
        }

        @Test
        @DisplayName("reconnect ignores display name changes")
        fun `reconnect ignores name`() {
            val slots = ControllerSlot.createAllSlots()
            val sigOriginal = DeviceSignature("vid:0001-pid:0001-src:1689", 1, 1, "Original Name")
            val sigRenamed = DeviceSignature("vid:0001-pid:0001-src:1689", 1, 1, "Updated Name")

            val assigned = SlotAssignmentPolicy.applyAssignment(slots, 0, sigOriginal)
            val disconnected = SlotAssignmentPolicy.applyDisconnect(assigned, 0)

            assertThat(SlotAssignmentPolicy.findSlotForDevice(disconnected, sigRenamed)).isEqualTo(0)
        }

        @Test
        @DisplayName("all slots full returns -1")
        fun `all full`() {
            var current = ControllerSlot.createAllSlots()
            for (i in 0 until ControllerSlot.SLOT_COUNT) {
                val sig = DeviceSignature("vid:000${i}-pid:000${i}-src:1689", i, i, "D$i")
                val idx = SlotAssignmentPolicy.findSlotForDevice(current, sig)
                current = SlotAssignmentPolicy.applyAssignment(current, idx, sig)
            }

            val overflow = DeviceSignature("vid:0005-pid:0005-src:1689", 5, 5, "D5")
            assertThat(SlotAssignmentPolicy.findSlotForDevice(current, overflow)).isEqualTo(-1)
        }

        @Test
        @DisplayName("skips CONNECTED slots to find DISCONNECTED match")
        fun `skip connected find disconnected`() {
            var current = ControllerSlot.createAllSlots()
            val sig1 = DeviceSignature("vid:0001-pid:0001-src:1689", 1, 1, "D1")
            val sig2 = DeviceSignature("vid:0002-pid:0002-src:1689", 2, 2, "D2")

            current = SlotAssignmentPolicy.applyAssignment(current, 0, sig1)
            current = SlotAssignmentPolicy.applyAssignment(current, 1, sig2)

            // Disconnect D2 from slot 1
            current = SlotAssignmentPolicy.applyDisconnect(current, 1)

            // D2 reconnects to slot 1 (not slot 2, which is unassigned)
            assertThat(SlotAssignmentPolicy.findSlotForDevice(current, sig2)).isEqualTo(1)
        }

        @Test
        @DisplayName("invalid slot index returns unchanged list")
        fun `invalid index`() {
            val slots = ControllerSlot.createAllSlots()
            val sig = DeviceSignature("vid:0001-pid:0001-src:1689", 1, 1, "D1")

            assertThat(SlotAssignmentPolicy.applyAssignment(slots, -1, sig)).isSameAs(slots)
            assertThat(SlotAssignmentPolicy.applyAssignment(slots, ControllerSlot.SLOT_COUNT, sig))
                .isSameAs(slots)
        }

        @Test
        @DisplayName("disconnect invalid index returns unchanged list")
        fun `disconnect invalid index`() {
            val slots = ControllerSlot.createAllSlots()
            assertThat(SlotAssignmentPolicy.applyDisconnect(slots, -1)).isSameAs(slots)
            assertThat(SlotAssignmentPolicy.applyDisconnect(slots, ControllerSlot.SLOT_COUNT))
                .isSameAs(slots)
        }
    }

    @Nested
    @DisplayName("applyAssignment — state transitions")
    inner class ApplyAssignmentTests {
        @Test
        @DisplayName("UNASSIGNED -> CONNECTED with signature")
        fun `unassigned to connected`() {
            val slots = ControllerSlot.createAllSlots()
            val sig = DeviceSignature("vid:0001-pid:0001-src:1689", 1, 1, "D1")
            val result = SlotAssignmentPolicy.applyAssignment(slots, 0, sig)

            assertThat(result[0].connectionState).isEqualTo(SlotConnectionState.CONNECTED)
            assertThat(result[0].preferredSignature).isEqualTo(sig)
        }

        @Test
        @DisplayName("DISCONNECTED -> CONNECTED preserves existing signature")
        fun `disconnected to connected`() {
            val slots = ControllerSlot.createAllSlots()
            val sig = DeviceSignature("vid:0001-pid:0001-src:1689", 1, 1, "D1")
            var result = SlotAssignmentPolicy.applyAssignment(slots, 0, sig)
            result = SlotAssignmentPolicy.applyDisconnect(result, 0)

            // Reconnect with potentially different name
            val sigRenamed = DeviceSignature("vid:0001-pid:0001-src:1689", 1, 1, "D1 Updated")
            result = SlotAssignmentPolicy.applyAssignment(result, 0, sigRenamed)

            assertThat(result[0].connectionState).isEqualTo(SlotConnectionState.CONNECTED)
            // Original signature preserved (reconnect doesn't change it)
            assertThat(result[0].preferredSignature).isEqualTo(sig)
        }

        @Test
        @DisplayName("CONNECTED slot is not overwritten")
        fun `connected not overwritten`() {
            val slots = ControllerSlot.createAllSlots()
            val sig1 = DeviceSignature("vid:0001-pid:0001-src:1689", 1, 1, "D1")
            val sig2 = DeviceSignature("vid:0002-pid:0002-src:1689", 2, 2, "D2")

            var result = SlotAssignmentPolicy.applyAssignment(slots, 0, sig1)
            result = SlotAssignmentPolicy.applyAssignment(result, 0, sig2)

            // Still sig1
            assertThat(result[0].preferredSignature).isEqualTo(sig1)
        }
    }

    @Nested
    @DisplayName("clearAllSlots — stop/focus-loss cleanup")
    inner class ClearAllTests {
        @Test
        @DisplayName("CONNECTED slots become DISCONNECTED with empty snapshots")
        fun `connected becomes disconnected`() {
            val slots = ControllerSlot.createAllSlots()
            val sig = DeviceSignature("vid:0001-pid:0001-src:1689", 1, 1, "D1")
            var result = SlotAssignmentPolicy.applyAssignment(slots, 0, sig)
            result = result.toMutableList().apply {
                this[0] = this[0].updateSnapshot(
                    GamepadSnapshot.withButton(GamepadSnapshot.EMPTY, LogicalControl.BUTTON_A, true)
                )
            } as List<ControllerSlot>

            val cleared = SlotAssignmentPolicy.clearAllSlots(result)
            assertThat(cleared[0].connectionState).isEqualTo(SlotConnectionState.DISCONNECTED)
            assertThat(cleared[0].currentSnapshot).isEqualTo(GamepadSnapshot.EMPTY)
        }

        @Test
        @DisplayName("UNASSIGNED slots remain UNASSIGNED")
        fun `unassigned stays unassigned`() {
            val slots = ControllerSlot.createAllSlots()
            val cleared = SlotAssignmentPolicy.clearAllSlots(slots)
            cleared.forEach { s ->
                assertThat(s.connectionState).isEqualTo(SlotConnectionState.UNASSIGNED)
            }
        }

        @Test
        @DisplayName("DISCONNECTED slots remain DISCONNECTED")
        fun `disconnected stays disconnected`() {
            val slots = ControllerSlot.createAllSlots()
            val sig = DeviceSignature("vid:0001-pid:0001-src:1689", 1, 1, "D1")
            var result = SlotAssignmentPolicy.applyAssignment(slots, 0, sig)
            result = SlotAssignmentPolicy.applyDisconnect(result, 0)

            val cleared = SlotAssignmentPolicy.clearAllSlots(result)
            assertThat(cleared[0].connectionState).isEqualTo(SlotConnectionState.DISCONNECTED)
        }
    }
}

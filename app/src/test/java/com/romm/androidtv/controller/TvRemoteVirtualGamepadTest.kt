package com.romm.androidtv.controller

import com.romm.androidtv.controller.model.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for TV remote -> virtual gamepad translation.
 *
 * Validates:
 * - D-pad directions map to gamepad buttons 12-15
 * - Enter/DPAD_CENTER maps to button A (index 0)
 * - Multiple simultaneous key presses produce correct combined snapshot
 * - Key release clears the corresponding button
 * - The browser contract remains exactly four slots
 */
@DisplayName("TV remote virtual gamepad translation")
class TvRemoteVirtualGamepadTest {

    private val sig = DeviceSignature(descriptor = "test", vendorId = 1, productId = 1, name = "Test")

    @Nested
    @DisplayName("Slot count and structure")
    inner class SlotStructureTests {

        @Test
        @DisplayName("createAllSlots produces exactly 4 slots (W3C browser contract)")
        fun `createAllSlots returns four slots`() {
            val slots = ControllerSlot.createAllSlots()
            assertThat(slots).hasSize(4)
        }

        @Test
        @DisplayName("SLOT_COUNT constant is 4")
        fun `slotCount is 4`() {
            assertThat(ControllerSlot.SLOT_COUNT).isEqualTo(4)
        }

        @Test
        @DisplayName("Player numbers range from 1 to 4")
        fun `playerNumbers are 1 through 4`() {
            val slots = ControllerSlot.createAllSlots()
            val playerNumbers = slots.map { it.playerNumber }
            assertThat(playerNumbers).containsExactly(1, 2, 3, 4)
        }

        @Test
        @DisplayName("Virtual remote signature is distinct from physical devices")
        fun `virtualRemoteSignature is unique`() {
            assertThat(DeviceSignature.VIRTUAL_REMOTE.descriptor).isEqualTo("virtual:remote")
            assertThat(DeviceSignature.VIRTUAL_REMOTE.vendorId).isEqualTo(0)
            assertThat(DeviceSignature.VIRTUAL_REMOTE.productId).isEqualTo(0)
        }

        @Test
        @DisplayName("Virtual remote does not match reconnect with physical device")
        fun `virtualRemoteDoesNotMatchPhysical`() {
            val physicalSig = DeviceSignature(
                descriptor = "vid:045e-pid:028e-src:64",
                vendorId = 0x045e,
                productId = 0x028e,
                name = "Xbox Controller"
            )
            assertThat(DeviceSignature.VIRTUAL_REMOTE.matchesReconnect(physicalSig)).isFalse()
        }
    }

    @Nested
    @DisplayName("GamepadSnapshot button mapping")
    inner class ButtonMappingTests {

        @Test
        @DisplayName("DPAD_UP maps to button index 12")
        fun `dpadUp maps to button 12`() {
            val snapshot = GamepadSnapshot.withButton(
                GamepadSnapshot.EMPTY,
                LogicalControl.DPAD_UP,
                pressed = true
            )
            assertThat(snapshot.buttons[12]).isEqualTo(1.0f)
            // All other buttons should be 0
            for (i in snapshot.buttons.indices) {
                if (i != 12) assertThat(snapshot.buttons[i]).isEqualTo(0.0f)
            }
        }

        @Test
        @DisplayName("DPAD_DOWN maps to button index 13")
        fun `dpadDown maps to button 13`() {
            val snapshot = GamepadSnapshot.withButton(
                GamepadSnapshot.EMPTY,
                LogicalControl.DPAD_DOWN,
                pressed = true
            )
            assertThat(snapshot.buttons[13]).isEqualTo(1.0f)
        }

        @Test
        @DisplayName("DPAD_LEFT maps to button index 14")
        fun `dpadLeft maps to button 14`() {
            val snapshot = GamepadSnapshot.withButton(
                GamepadSnapshot.EMPTY,
                LogicalControl.DPAD_LEFT,
                pressed = true
            )
            assertThat(snapshot.buttons[14]).isEqualTo(1.0f)
        }

        @Test
        @DisplayName("DPAD_RIGHT maps to button index 15")
        fun `dpadRight maps to button 15`() {
            val snapshot = GamepadSnapshot.withButton(
                GamepadSnapshot.EMPTY,
                LogicalControl.DPAD_RIGHT,
                pressed = true
            )
            assertThat(snapshot.buttons[15]).isEqualTo(1.0f)
        }

        @Test
        @DisplayName("BUTTON_A maps to button index 0")
        fun `buttonA maps to button 0`() {
            val snapshot = GamepadSnapshot.withButton(
                GamepadSnapshot.EMPTY,
                LogicalControl.BUTTON_A,
                pressed = true
            )
            assertThat(snapshot.buttons[0]).isEqualTo(1.0f)
        }

        @Test
        @DisplayName("Button release sets value to 0")
        fun `buttonRelease clears button`() {
            var snapshot = GamepadSnapshot.withButton(
                GamepadSnapshot.EMPTY,
                LogicalControl.DPAD_UP,
                pressed = true
            )
            assertThat(snapshot.buttons[12]).isEqualTo(1.0f)

            snapshot = GamepadSnapshot.withButton(snapshot, LogicalControl.DPAD_UP, pressed = false)
            assertThat(snapshot.buttons[12]).isEqualTo(0.0f)
        }

        @Test
        @DisplayName("Multiple simultaneous buttons are independent")
        fun `multipleButtonsIndependent`() {
            var snapshot = GamepadSnapshot.EMPTY
            snapshot = GamepadSnapshot.withButton(snapshot, LogicalControl.DPAD_UP, pressed = true)
            snapshot = GamepadSnapshot.withButton(snapshot, LogicalControl.BUTTON_A, pressed = true)

            assertThat(snapshot.buttons[12]).isEqualTo(1.0f)
            assertThat(snapshot.buttons[0]).isEqualTo(1.0f)
            // Other buttons should be 0
            assertThat(snapshot.buttons[13]).isEqualTo(0.0f)
            assertThat(snapshot.buttons[14]).isEqualTo(0.0f)
            assertThat(snapshot.buttons[15]).isEqualTo(0.0f)
        }
    }
}

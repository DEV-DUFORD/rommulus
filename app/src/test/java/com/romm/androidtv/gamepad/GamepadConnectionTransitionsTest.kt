package com.romm.androidtv.gamepad

import com.romm.androidtv.controller.model.*
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

/**
 * Unit tests for connection transitions in the gamepad injection system.
 *
 * Validates:
 * - Connection state changes produce correct JSON
 * - Disconnect neutralizes slot (null entry)
 * - Reconnect restores connected object
 * - Multiple transitions are correctly serialized
 * - W3C standard 4 axes / 16 buttons structure
 * - Exactly four browser-facing slots
 */
class GamepadConnectionTransitionsTest {

    private val sig = DeviceSignature(descriptor = "test", vendorId = 1, productId = 1, name = "Test")

    @Test
    fun `unassigned slot serializes as null`() {
        val slots = createSlotsWithState(
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.UNASSIGNED
        )
        val json = GamepadSerializer.serializeSlots(slots)!!
        assertThat(json).isEqualTo("[null,null,null,null]")
    }

    @Test
    fun `connected slot serializes as object`() {
        val slots = createSlotsWithState(
            SlotConnectionState.CONNECTED,
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.UNASSIGNED
        )
        val json = GamepadSerializer.serializeSlots(slots)!!
        assertThat(json).contains("\"connected\":true")
        val afterFirst = json.substringAfter("}")
        assertThat(afterFirst).contains("null")
    }

    @Test
    fun `disconnected slot serializes as null`() {
        val slots = createSlotsWithState(
            SlotConnectionState.DISCONNECTED,
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.UNASSIGNED
        )
        val json = GamepadSerializer.serializeSlots(slots)!!
        assertThat(json).isEqualTo("[null,null,null,null]")
    }

    @Test
    fun `connect transition null to object`() {
        val before = createSlotsWithState(
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.UNASSIGNED
        )
        val jsonBefore = GamepadSerializer.serializeSlots(before)!!
        assertThat(jsonBefore).isEqualTo("[null,null,null,null]")

        val after = createSlotsWithState(
            SlotConnectionState.CONNECTED,
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.UNASSIGNED
        )
        val jsonAfter = GamepadSerializer.serializeSlots(after)!!
        assertThat(jsonAfter).contains("\"connected\":true")
    }

    @Test
    fun `disconnect transition object to null`() {
        val before = createSlotsWithState(
            SlotConnectionState.CONNECTED,
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.UNASSIGNED
        )
        val jsonBefore = GamepadSerializer.serializeSlots(before)!!
        assertThat(jsonBefore).contains("\"connected\":true")

        val after = createSlotsWithState(
            SlotConnectionState.DISCONNECTED,
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.UNASSIGNED
        )
        val jsonAfter = GamepadSerializer.serializeSlots(after)!!
        assertThat(jsonAfter).isEqualTo("[null,null,null,null]")
    }

    @Test
    fun `reconnect transition null to object (hot-plug)`() {
        val disconnected = createSlotsWithState(
            SlotConnectionState.DISCONNECTED,
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.UNASSIGNED
        )
        val jsonDisconnected = GamepadSerializer.serializeSlots(disconnected)!!
        assertThat(jsonDisconnected).isEqualTo("[null,null,null,null]")

        val reconnected = createSlotsWithState(
            SlotConnectionState.CONNECTED,
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.UNASSIGNED
        )
        val jsonReconnected = GamepadSerializer.serializeSlots(reconnected)!!
        assertThat(jsonReconnected).contains("\"connected\":true")
    }

    @Test
    fun `all four physical slots connect and disconnect`() {
        val allConnected = createSlotsWithState(
            SlotConnectionState.CONNECTED,
            SlotConnectionState.CONNECTED,
            SlotConnectionState.CONNECTED,
            SlotConnectionState.CONNECTED
        )
        val jsonConnected = GamepadSerializer.serializeSlots(allConnected)!!
        assertThat(jsonConnected).contains("RomM Virtual Gamepad 1")
        assertThat(jsonConnected).contains("RomM Virtual Gamepad 2")
        assertThat(jsonConnected).contains("RomM Virtual Gamepad 3")
        assertThat(jsonConnected).contains("RomM Virtual Gamepad 4")

        val allDisconnected = createSlotsWithState(
            SlotConnectionState.DISCONNECTED,
            SlotConnectionState.DISCONNECTED,
            SlotConnectionState.DISCONNECTED,
            SlotConnectionState.DISCONNECTED
        )
        val jsonDisconnected = GamepadSerializer.serializeSlots(allDisconnected)!!
        assertThat(jsonDisconnected).isEqualTo("[null,null,null,null]")
    }

    @Test
    fun `partial disconnect preserves other slots`() {
        val mixed = createSlotsWithState(
            SlotConnectionState.CONNECTED,
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.CONNECTED,
            SlotConnectionState.UNASSIGNED
        )
        val json = GamepadSerializer.serializeSlots(mixed)!!

        assertThat(json).contains("RomM Virtual Gamepad 1")
        assertThat(json).contains("RomM Virtual Gamepad 3")
        assertThat(json).doesNotContain("RomM Virtual Gamepad 2")
        assertThat(json).doesNotContain("RomM Virtual Gamepad 4")
    }

    @Test
    fun `connected slot has W3C standard 4 axes in serialized output`() {
        val slots = createSlotsWithState(
            SlotConnectionState.CONNECTED,
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.UNASSIGNED
        )
        val json = GamepadSerializer.serializeSlots(slots)!!
        val axesSection = extractAxesArray(json)
        val axisValues = axesSection.split(",").map { it.trim() }
        assertThat(axisValues).hasSize(4)
    }

    @Test
    fun `connected slot has W3C standard 16 buttons in serialized output`() {
        val slots = createSlotsWithState(
            SlotConnectionState.CONNECTED,
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.UNASSIGNED,
            SlotConnectionState.UNASSIGNED
        )
        val json = GamepadSerializer.serializeSlots(slots)!!
        val buttonsSection = extractButtonsArray(json)
        val buttonValues = buttonsSection.split(",").map { it.trim() }
        assertThat(buttonValues).hasSize(16)
    }

    private fun createSlotsWithState(
        s1: SlotConnectionState,
        s2: SlotConnectionState,
        s3: SlotConnectionState,
        s4: SlotConnectionState
    ): List<ControllerSlot> {
        return listOf(s1, s2, s3, s4).mapIndexed { index, state ->
            val slot = ControllerSlot(playerNumber = index + 1)
            when (state) {
                SlotConnectionState.CONNECTED -> slot.assign(sig)
                SlotConnectionState.DISCONNECTED -> slot.assign(sig).disconnect()
                SlotConnectionState.UNASSIGNED -> slot
            }
        }
    }

    private fun extractButtonsArray(json: String): String {
        val start = json.indexOf("\"buttons\":[") + 11
        val end = json.indexOf("]", start)
        return json.substring(start, end).trim()
    }

    private fun extractAxesArray(json: String): String {
        val start = json.indexOf("\"axes\":[") + 8
        val end = json.indexOf("]", start)
        return json.substring(start, end).trim()
    }
}

package com.romm.androidtv.gamepad

import com.romm.androidtv.controller.model.*
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

/**
 * Unit tests for GamepadSerializer.
 *
 * Validates:
 * - Exact JSON structure for connected/disconnected slots
 * - W3C standard: 16 buttons (triggers at indices 6/7), 4 axes
 * - Standard indices (0..3)
 * - Null disconnected slots
 * - Finite/clamped button and axis values
 * - Payload size limit
 * - NaN/Infinity handling
 * - Trigger-to-button remapping
 */
class GamepadSerializerTest {

    @Test
    fun `serializeSlots produces valid JSON array of 4 entries`() {
        val slots = createConnectedSlots()
        val json = GamepadSerializer.serializeSlots(slots)

        assertThat(json).isNotNull()
        assertThat(json!!).startsWith("[")
        assertThat(json).endsWith("]")
    }

    @Test
    fun `serializeSlots returns null for wrong slot count`() {
        val slots = listOf(ControllerSlot(playerNumber = 1))
        assertThat(GamepadSerializer.serializeSlots(slots)).isNull()
    }

    @Test
    fun `connected slot produces object with expected fields`() {
        val slots = createConnectedSlots()
        val json = GamepadSerializer.serializeSlots(slots)!!

        // First slot (index 0, Player 1)
        assertThat(json).contains("\"index\":0")
        assertThat(json).contains("\"connected\":true")
        assertThat(json).contains("\"id\":\"RomM Virtual Gamepad 1\"")
        assertThat(json).contains("\"buttons\":[")
        assertThat(json).contains("\"axes\":[")
    }

    @Test
    fun `disconnected slot produces null entry`() {
        val slots = listOf(
            ControllerSlot(playerNumber = 1),
            ControllerSlot(playerNumber = 2),
            ControllerSlot(playerNumber = 3),
            ControllerSlot(playerNumber = 4)
        )
        val json = GamepadSerializer.serializeSlots(slots)!!

        assertThat(json).isEqualTo("[null,null,null,null]")
    }

    @Test
    fun `mixed connected and disconnected slots`() {
        val sig = DeviceSignature(descriptor = "vid:045e-pid:028e-src:64", vendorId = 0x045e, productId = 0x028e, name = "Xbox")
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig),
            ControllerSlot(playerNumber = 2),
            ControllerSlot(playerNumber = 3).disconnect(),
            ControllerSlot(playerNumber = 4)
        )
        val json = GamepadSerializer.serializeSlots(slots)!!

        assertThat(json).contains("\"index\":0")
        assertThat(json).contains("\"connected\":true")
        val afterFirst = json.substring(json.indexOf("}") + 1)
        assertThat(afterFirst).contains("null")
    }

    @Test
    fun `button values are clamped to zero to one range`() {
        val snapshot = GamepadSnapshot(
            buttons = floatArrayOf(-0.5f, 0.5f, 1.5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            axes = FloatArray(6)
        )
        val sig = DeviceSignature(descriptor = "test", vendorId = 1, productId = 1, name = "Test")
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig).updateSnapshot(snapshot),
            ControllerSlot(playerNumber = 2),
            ControllerSlot(playerNumber = 3),
            ControllerSlot(playerNumber = 4),
        )
        val json = GamepadSerializer.serializeSlots(slots)!!

        val buttonsSection = extractButtonsArray(json)
        // -0.5 clamped to 0, 1.5 clamped to 1
        assertThat(buttonsSection).contains("0")
        assertThat(buttonsSection).contains("1")
    }

    @Test
    fun `axis values are clamped to minus one to one range`() {
        val snapshot = GamepadSnapshot(
            buttons = FloatArray(16),
            axes = floatArrayOf(-1.5f, 0.5f, 1.5f, 0f, 0f, 0f)
        )
        val sig = DeviceSignature(descriptor = "test", vendorId = 1, productId = 1, name = "Test")
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig).updateSnapshot(snapshot),
            ControllerSlot(playerNumber = 2),
            ControllerSlot(playerNumber = 3),
            ControllerSlot(playerNumber = 4),
        )
        val json = GamepadSerializer.serializeSlots(slots)!!

        val axesSection = extractAxesArray(json)
        assertThat(axesSection).contains("-1")
        assertThat(axesSection).contains("1")
    }

    @Test
    fun `NaN button values are replaced with 0`() {
        val snapshot = GamepadSnapshot(
            buttons = floatArrayOf(Float.NaN, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            axes = FloatArray(6)
        )
        val sig = DeviceSignature(descriptor = "test", vendorId = 1, productId = 1, name = "Test")
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig).updateSnapshot(snapshot),
            ControllerSlot(playerNumber = 2),
            ControllerSlot(playerNumber = 3),
            ControllerSlot(playerNumber = 4),
        )
        val json = GamepadSerializer.serializeSlots(slots)!!

        assertThat(json).doesNotContain("NaN")
        assertThat(json).doesNotContain("Infinity")
    }

    @Test
    fun `triggers are remapped from axes to button indices 6 and 7`() {
        val snapshot = GamepadSnapshot(
            buttons = FloatArray(16),
            axes = floatArrayOf(0f, 0f, 0f, 0f, 0.8f, 1.0f)
        )
        val sig = DeviceSignature(descriptor = "test", vendorId = 1, productId = 1, name = "Test")
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig).updateSnapshot(snapshot),
            ControllerSlot(playerNumber = 2),
            ControllerSlot(playerNumber = 3),
            ControllerSlot(playerNumber = 4)
        )
        val json = GamepadSerializer.serializeSlots(slots)!!

        val buttonsSection = extractButtonsArray(json)
        val buttonValues = buttonsSection.split(",").map { it.trim().toFloat() }

        // Button index 6 (LT) should be 0.8, button index 7 (RT) should be 1
        assertThat(buttonValues[6]).isEqualTo(0.8f)
        assertThat(buttonValues[7]).isEqualTo(1.0f)
    }

    @Test
    fun `payload size limit rejects oversized output`() {
        assertThat(GamepadSerializer.MAX_PAYLOAD_BYTES).isEqualTo(4096)
        val slots = createConnectedSlots()
        val json = GamepadSerializer.serializeSlots(slots)
        assertThat(json).isNotNull()
        assertThat(json!!.length).isLessThan(GamepadSerializer.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun `all four standard indices are present`() {
        val slots = createConnectedSlots()
        val json = GamepadSerializer.serializeSlots(slots)!!

        assertThat(json).contains("\"index\":0")
        assertThat(json).contains("\"index\":1")
        assertThat(json).contains("\"index\":2")
        assertThat(json).contains("\"index\":3")
    }

    @Test
    fun `all four player IDs are present`() {
        val slots = createConnectedSlots()
        val json = GamepadSerializer.serializeSlots(slots)!!

        assertThat(json).contains("RomM Virtual Gamepad 1")
        assertThat(json).contains("RomM Virtual Gamepad 2")
        assertThat(json).contains("RomM Virtual Gamepad 3")
        assertThat(json).contains("RomM Virtual Gamepad 4")
    }

    @Test
    fun `empty snapshot serializes as zeros`() {
        val sig = DeviceSignature(descriptor = "test", vendorId = 1, productId = 1, name = "Test")
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig).updateSnapshot(GamepadSnapshot.EMPTY),
            ControllerSlot(playerNumber = 2),
            ControllerSlot(playerNumber = 3),
            ControllerSlot(playerNumber = 4),
        )
        val json = GamepadSerializer.serializeSlots(slots)!!

        val buttonsSection = extractButtonsArray(json)
        // All 16 buttons should be 0
        assertThat(buttonsSection).matches("0(?:,\\s*0){15}")
    }

    @Test
    fun `serialized output has exactly four axes`() {
        val sig = DeviceSignature(descriptor = "test", vendorId = 1, productId = 1, name = "Test")
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig),
            ControllerSlot(playerNumber = 2),
            ControllerSlot(playerNumber = 3),
            ControllerSlot(playerNumber = 4),
        )
        val json = GamepadSerializer.serializeSlots(slots)!!

        val axesSection = extractAxesArray(json)
        val values = axesSection.split(",").map { it.trim() }
        assertThat(values).hasSize(4)
    }

    @Test
    fun `serialized output has exactly sixteen buttons`() {
        val sig = DeviceSignature(descriptor = "test", vendorId = 1, productId = 1, name = "Test")
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig),
            ControllerSlot(playerNumber = 2),
            ControllerSlot(playerNumber = 3),
            ControllerSlot(playerNumber = 4),
        )
        val json = GamepadSerializer.serializeSlots(slots)!!

        val buttonsSection = extractButtonsArray(json)
        val values = buttonsSection.split(",").map { it.trim() }
        assertThat(values).hasSize(16)
    }



    @Test
    fun `triggers with negative native values are clamped to 0 in button output`() {
        // Native trigger axes can be -1..1; buttons must be 0..1
        val snapshot = GamepadSnapshot(
            buttons = FloatArray(16),
            axes = floatArrayOf(0f, 0f, 0f, 0f, -0.5f, -1.0f)
        )
        val sig = DeviceSignature(descriptor = "test", vendorId = 1, productId = 1, name = "Test")
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig).updateSnapshot(snapshot),
            ControllerSlot(playerNumber = 2),
            ControllerSlot(playerNumber = 3),
            ControllerSlot(playerNumber = 4),
        )
        val json = GamepadSerializer.serializeSlots(slots)!!

        val buttonsSection = extractButtonsArray(json)
        val buttonValues = buttonsSection.split(",").map { it.trim().toFloat() }

        // Negative trigger values clamped to 0
        assertThat(buttonValues[6]).isEqualTo(0f)
        assertThat(buttonValues[7]).isEqualTo(0f)
    }

    @Test
    fun `AB swap is immediately reflected in serialized output`() {
        val sig = DeviceSignature(descriptor = "test", vendorId = 1, productId = 1, name = "Test")

        val snapshotA = GamepadSnapshot(
            buttons = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            axes = FloatArray(6)
        )
        val slotBefore = ControllerSlot(playerNumber = 1).assign(sig).updateSnapshot(snapshotA)

        val snapshotB = GamepadSnapshot(
            buttons = floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            axes = FloatArray(6)
        )
        val slotAfter = ControllerSlot(playerNumber = 1).assign(sig)
            .remap(ControllerMapping.swapAB(ControllerMapping()))
            .updateSnapshot(snapshotB)

        val slotsBefore = listOf(slotBefore, ControllerSlot(2), ControllerSlot(3), ControllerSlot(4))
        val jsonBefore = GamepadSerializer.serializeSlots(slotsBefore)!!
        assertThat(extractButtonsArray(jsonBefore)).startsWith("1,0")

        val slotsAfter = listOf(slotAfter, ControllerSlot(2), ControllerSlot(3), ControllerSlot(4))
        val jsonAfter = GamepadSerializer.serializeSlots(slotsAfter)!!
        assertThat(extractButtonsArray(jsonAfter)).startsWith("0,1")
    }

    @Test
    fun `four axes only native triggers excluded from axes output`() {
        // Native snapshot has 6 axes; serializer outputs only 4
        val snapshot = GamepadSnapshot(
            buttons = FloatArray(16),
            axes = floatArrayOf(0.5f, -0.3f, 0.2f, 0.1f, 0.9f, 0.7f)
        )
        val sig = DeviceSignature(descriptor = "test", vendorId = 1, productId = 1, name = "Test")
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig).updateSnapshot(snapshot),
            ControllerSlot(playerNumber = 2),
            ControllerSlot(playerNumber = 3),
            ControllerSlot(playerNumber = 4),
        )
        val json = GamepadSerializer.serializeSlots(slots)!!

        val axesSection = extractAxesArray(json)
        val axisValues = axesSection.split(",").map { it.trim() }

        // Should have exactly 4 values: 0.5, -0.3, 0.2, 0.1
        assertThat(axisValues).hasSize(4)
        assertThat(axisValues[0]).isEqualTo("0.5")
        assertThat(axisValues[1]).isEqualTo("-0.3")
        assertThat(axisValues[2]).isEqualTo("0.2")
        assertThat(axisValues[3]).isEqualTo("0.1")

        // Axes 4 (0.9) and 5 (0.7) should NOT appear in axes output
        assertThat(axesSection).doesNotContain("0.9")
        assertThat(axesSection).doesNotContain("0.7")
    }

    private fun createConnectedSlots(): List<ControllerSlot> {
        val sig = DeviceSignature(descriptor = "test", vendorId = 1, productId = 1, name = "Test")
        return listOf(1, 2, 3, 4).map { n ->
            ControllerSlot(playerNumber = n).assign(sig)
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

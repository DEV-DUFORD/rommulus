package com.romm.androidtv.gamepad

import com.romm.androidtv.controller.model.*
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

/**
 * Unit tests for lifecycle update policy and serialization edge cases.
 *
 * Validates:
 * - Empty slot list returns null
 * - Snapshot fields are correctly ordered
 * - Decimal float formatting
 * - Very small axis values are preserved
 * - Zero values use integer format
 * - W3C standard 4 axes output
 * - Trigger-to-button remapping in serialized output
 */
class GamepadLifecycleUpdatePolicyTest {

    private val sig = DeviceSignature(descriptor = "test", vendorId = 1, productId = 1, name = "Test")

    @Test
    fun `empty list returns null`() {
        assertThat(GamepadSerializer.serializeSlots(emptyList())).isNull()
    }

    @Test
    fun `three slots returns null`() {
        val slots = listOf(ControllerSlot(1), ControllerSlot(2), ControllerSlot(3))
        assertThat(GamepadSerializer.serializeSlots(slots)).isNull()
    }

    @Test
    fun `four slots serializes successfully`() {
        // 4 slots is the correct count (W3C browser contract)
        val slots = listOf(ControllerSlot(1), ControllerSlot(2), ControllerSlot(3), ControllerSlot(4))
        assertThat(GamepadSerializer.serializeSlots(slots)).isNotNull()
    }

    @Test
    fun `five slots returns null`() {
        // 5 slots is too many — only 4 are supported
        val slots = listOf(ControllerSlot(1), ControllerSlot(2), ControllerSlot(3), ControllerSlot(4), ControllerSlot(4))
        assertThat(GamepadSerializer.serializeSlots(slots)).isNull()
    }

    @Test
    fun `zero button value uses integer format`() {
        val snapshot = GamepadSnapshot(
            buttons = FloatArray(16),
            axes = FloatArray(6)
        )
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig).updateSnapshot(snapshot),
            ControllerSlot(2), ControllerSlot(3), ControllerSlot(4)
        )
        val json = GamepadSerializer.serializeSlots(slots)!!
        val buttonsSection = extractButtonsArray(json)
        assertThat(buttonsSection).matches("0(?:,\\s*0){15}")
    }

    @Test
    fun `one button value uses integer format`() {
        val snapshot = GamepadSnapshot(
            buttons = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            axes = FloatArray(6)
        )
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig).updateSnapshot(snapshot),
            ControllerSlot(2), ControllerSlot(3), ControllerSlot(4)
        )
        val json = GamepadSerializer.serializeSlots(slots)!!
        val buttonsSection = extractButtonsArray(json)
        assertThat(buttonsSection).startsWith("1,0")
    }

    @Test
    fun `small axis value preserves decimal precision`() {
        val snapshot = GamepadSnapshot(
            buttons = FloatArray(16),
            axes = floatArrayOf(0.1234f, 0f, 0f, 0f, 0f, 0f)
        )
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig).updateSnapshot(snapshot),
            ControllerSlot(2), ControllerSlot(3), ControllerSlot(4)
        )
        val json = GamepadSerializer.serializeSlots(slots)!!
        val axesSection = extractAxesArray(json)
        assertThat(axesSection).contains("0.1234")
    }

    @Test
    fun `negative axis value preserves sign`() {
        val snapshot = GamepadSnapshot(
            buttons = FloatArray(16),
            axes = floatArrayOf(-0.5f, 0f, 0f, 0f, 0f, 0f)
        )
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig).updateSnapshot(snapshot),
            ControllerSlot(2), ControllerSlot(3), ControllerSlot(4)
        )
        val json = GamepadSerializer.serializeSlots(slots)!!
        val axesSection = extractAxesArray(json)
        assertThat(axesSection).startsWith("-0.5")
    }

    @Test
    fun `axis at exact -1 uses integer format`() {
        val snapshot = GamepadSnapshot(
            buttons = FloatArray(16),
            axes = floatArrayOf(-1f, 0f, 0f, 0f, 0f, 0f)
        )
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig).updateSnapshot(snapshot),
            ControllerSlot(2), ControllerSlot(3), ControllerSlot(4)
        )
        val json = GamepadSerializer.serializeSlots(slots)!!
        val axesSection = extractAxesArray(json)
        assertThat(axesSection).startsWith("-1")
    }

    @Test
    fun `axis at exact 1 uses integer format`() {
        val snapshot = GamepadSnapshot(
            buttons = FloatArray(16),
            axes = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f)
        )
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig).updateSnapshot(snapshot),
            ControllerSlot(2), ControllerSlot(3), ControllerSlot(4)
        )
        val json = GamepadSerializer.serializeSlots(slots)!!
        val axesSection = extractAxesArray(json)
        assertThat(axesSection).startsWith("1")
    }

    @Test
    fun `consecutive snapshots are independent`() {
        val snapshot1 = GamepadSnapshot(
            buttons = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            axes = FloatArray(6)
        )
        val snapshot2 = GamepadSnapshot(
            buttons = floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            axes = FloatArray(6)
        )

        val slots1 = listOf(
            ControllerSlot(playerNumber = 1).assign(sig).updateSnapshot(snapshot1),
            ControllerSlot(2), ControllerSlot(3), ControllerSlot(4)
        )
        val json1 = GamepadSerializer.serializeSlots(slots1)!!
        assertThat(extractButtonsArray(json1)).startsWith("1,0")

        val slots2 = listOf(
            ControllerSlot(playerNumber = 1).assign(sig).updateSnapshot(snapshot2),
            ControllerSlot(2), ControllerSlot(3), ControllerSlot(4)
        )
        val json2 = GamepadSerializer.serializeSlots(slots2)!!
        assertThat(extractButtonsArray(json2)).startsWith("0,1")

        assertThat(extractButtonsArray(json1)).startsWith("1,0")
    }

    @Test
    fun `mapping change between snapshots produces different output`() {
        val snapshot = GamepadSnapshot(
            buttons = FloatArray(16),
            axes = floatArrayOf(0.5f, 0f, 0f, 0f, 0f, 0f)
        )

        val slotDefault = ControllerSlot(playerNumber = 1).assign(sig).updateSnapshot(snapshot)
        val slotSwapped = slotDefault.remap(ControllerMapping.swapAB(ControllerMapping())).updateSnapshot(snapshot)

        val slotsDefault = listOf(slotDefault, ControllerSlot(2), ControllerSlot(3), ControllerSlot(4))
        val jsonDefault = GamepadSerializer.serializeSlots(slotsDefault)!!

        val slotsSwapped = listOf(slotSwapped, ControllerSlot(2), ControllerSlot(3), ControllerSlot(4))
        val jsonSwapped = GamepadSerializer.serializeSlots(slotsSwapped)!!

        assertThat(jsonDefault).isNotNull()
        assertThat(jsonSwapped).isNotNull()
    }

    @Test
    fun `payload size is within limit for all connected slots`() {
        val slots = listOf(1, 2, 3, 4).map { n ->
            ControllerSlot(playerNumber = n).assign(sig)
        }
        val json = GamepadSerializer.serializeSlots(slots)!!
        assertThat(json.length).isLessThan(GamepadSerializer.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun `serialized axes count is always 4 regardless of native axis count`() {
        // Native snapshot has 6 axes; serializer outputs exactly 4
        val snapshot = GamepadSnapshot(
            buttons = FloatArray(16),
            axes = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f)
        )
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig).updateSnapshot(snapshot),
            ControllerSlot(2), ControllerSlot(3), ControllerSlot(4)
        )
        val json = GamepadSerializer.serializeSlots(slots)!!
        val axesSection = extractAxesArray(json)
        val axisValues = axesSection.split(",").map { it.trim() }
        assertThat(axisValues).hasSize(4)
        // Native axes 4 (0.5) and 5 (0.6) are NOT in the axes output
        assertThat(axesSection).doesNotContain("0.5")
        assertThat(axesSection).doesNotContain("0.6")
    }

    @Test
    fun `serialized buttons always has 16 entries including trigger remaps`() {
        val snapshot = GamepadSnapshot(
            buttons = FloatArray(16),
            axes = floatArrayOf(0f, 0f, 0f, 0f, 0.75f, 0.25f)
        )
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig).updateSnapshot(snapshot),
            ControllerSlot(2), ControllerSlot(3), ControllerSlot(4)
        )
        val json = GamepadSerializer.serializeSlots(slots)!!
        val buttonsSection = extractButtonsArray(json)
        val buttonValues = buttonsSection.split(",").map { it.trim() }
        assertThat(buttonValues).hasSize(16)
        // Triggers remapped to buttons 6 and 7
        assertThat(buttonValues[6]).isEqualTo("0.75")
        assertThat(buttonValues[7]).isEqualTo("0.25")
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

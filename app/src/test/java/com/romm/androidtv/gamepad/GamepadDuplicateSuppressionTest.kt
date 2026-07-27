package com.romm.androidtv.gamepad

import com.romm.androidtv.controller.model.*
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

/**
 * Unit tests for duplicate suppression and idempotence in the gamepad injection system.
 *
 * Validates:
 * - Identical snapshots produce identical JSON (deterministic serialization)
 * - Idempotent script generation
 * - No duplicate entries in serialized output
 * - Null slots are consistently null
 * - defineProperty usage
 */
class GamepadDuplicateSuppressionTest {

    private val sig = DeviceSignature(descriptor = "test", vendorId = 1, productId = 1, name = "Test")

    @Test
    fun `identical snapshots produce identical JSON`() {
        val snapshot = GamepadSnapshot(
            buttons = floatArrayOf(1f, 0.5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            axes = floatArrayOf(0.75f, -0.25f, 0f, 0f, 0f, 0f)
        )
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig).updateSnapshot(snapshot),
            ControllerSlot(playerNumber = 2).assign(sig).updateSnapshot(GamepadSnapshot.EMPTY),
            ControllerSlot(playerNumber = 3),
            ControllerSlot(playerNumber = 4),
        )

        val json1 = GamepadSerializer.serializeSlots(slots)!!
        val json2 = GamepadSerializer.serializeSlots(slots)!!
        assertThat(json1).isEqualTo(json2)
    }

    @Test
    fun `script generation is idempotent`() {
        val origin = "https://romm.example.com"
        val script1 = GamepadInjectionScript.build(origin)
        val script2 = GamepadInjectionScript.build(origin)
        assertThat(script1).isEqualTo(script2)
    }

    @Test
    fun `no duplicate gamepad entries in serialized output`() {
        val slots = listOf(1, 2, 3, 4).map { n ->
            ControllerSlot(playerNumber = n).assign(sig)
        }
        val json = GamepadSerializer.serializeSlots(slots)!!

        assertThat(json).contains("RomM Virtual Gamepad 1")
        assertThat(json).contains("RomM Virtual Gamepad 2")
        assertThat(json).contains("RomM Virtual Gamepad 3")
        assertThat(json).contains("RomM Virtual Gamepad 4")
        

        val count1 = json.split("RomM Virtual Gamepad 1").size - 1
        val count2 = json.split("RomM Virtual Gamepad 2").size - 1
        assertThat(count1).isEqualTo(1)
        assertThat(count2).isEqualTo(1)
    }

    @Test
    fun `null slots are consistently null across calls`() {
        val slots = listOf(
            ControllerSlot(playerNumber = 1),
            ControllerSlot(playerNumber = 2),
            ControllerSlot(playerNumber = 3),
            ControllerSlot(playerNumber = 4),
        )

        repeat(5) {
            val json = GamepadSerializer.serializeSlots(slots)!!
            assertThat(json).isEqualTo("[null,null,null,null]")
        }
    }

    @Test
    fun `physical gamepad hiding only virtual slots appear`() {
        val sig = DeviceSignature(
            descriptor = "vid:045e-pid:028e-src:64",
            vendorId = 0x045e,
            productId = 0x028e,
            name = "Xbox Wireless Controller"
        )
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig),
            ControllerSlot(playerNumber = 2),
            ControllerSlot(playerNumber = 3),
            ControllerSlot(playerNumber = 4),
        )
        val json = GamepadSerializer.serializeSlots(slots)!!

        assertThat(json).doesNotContain("Xbox")
        assertThat(json).doesNotContain("045e")
        assertThat(json).doesNotContain("028e")
        assertThat(json).contains("RomM Virtual Gamepad 1")
    }

    @Test
    fun `script idempotence marker prevents double installation`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")

        assertThat(script).contains("if (window.__rommGamepadOverride) return;")
        assertThat(script).contains("window.__rommGamepadOverride = true;")
    }

    @Test
    fun `script does not override getGamepads on wrong origin`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")

        val originCheckIndex = script.indexOf("document.location.origin !== ALLOWED_ORIGIN")
        val definePropIndex = script.indexOf("defineProperty(navigator, 'getGamepads'")
        assertThat(originCheckIndex).isLessThan(definePropIndex)
    }

    @Test
    fun `multiple different origins produce independent scripts`() {
        val origin1 = "https://romm.example.com"
        val origin2 = "https://romm.other.com"

        val script1 = GamepadInjectionScript.build(origin1)
        val script2 = GamepadInjectionScript.build(origin2)

        assertThat(script1).contains("ALLOWED_ORIGIN = '$origin1'")
        assertThat(script1).doesNotContain(origin2)
        assertThat(script2).contains("ALLOWED_ORIGIN = '$origin2'")
        assertThat(script2).doesNotContain(origin1)
    }

    @Test
    fun `standard index ordering is preserved`() {
        val slots = listOf(1, 2, 3, 4).map { n ->
            ControllerSlot(playerNumber = n).assign(sig)
        }
        val json = GamepadSerializer.serializeSlots(slots)!!

        val idx0 = json.indexOf("\"index\":0")
        val idx1 = json.indexOf("\"index\":1")
        val idx2 = json.indexOf("\"index\":2")
        val idx3 = json.indexOf("\"index\":3")

        assertThat(idx0).isLessThan(idx1)
        assertThat(idx1).isLessThan(idx2)
        assertThat(idx2).isLessThan(idx3)
    }

    @Test
    fun `script uses defineProperty for robust override`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("defineProperty(navigator, 'getGamepads'")
        assertThat(script).contains("configurable: true")
    }

    @Test
    fun `script has W3C standard NUM_AXES = 4`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("NUM_AXES = 4")
    }

    @Test
    fun `script has W3C standard NUM_BUTTONS = 16`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("NUM_BUTTONS = 16")
    }
}

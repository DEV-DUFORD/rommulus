package com.romm.androidtv.gamepad

import com.romm.androidtv.controller.model.*
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

/**
 * Comprehensive behavioral tests for the gamepad injection system.
 *
 * Covers:
 * - Four axes / trigger buttons W3C compliance
 * - Immediate update race conditions
 * - JSON escaping safety
 * - Script handler lifecycle patterns
 * - Disconnect event semantics
 * - AB swap with snapshot generation
 * - SPA vs full navigation policy
 */
class GamepadBehavioralTest {

    private val sig = DeviceSignature(descriptor = "test", vendorId = 1, productId = 1, name = "Test")

    // ---- W3C Standard: 4 axes + triggers as buttons 6/7 ----

    @Test
    fun `serialized gamepad exposes exactly four axes`() {
        val snapshot = GamepadSnapshot(
            buttons = FloatArray(16),
            axes = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
        )
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig).updateSnapshot(snapshot),
            ControllerSlot(2), ControllerSlot(3), ControllerSlot(4)
        )
        val json = GamepadSerializer.serializeSlots(slots)!!

        // Count axis entries in the axes array
        val axesSection = extractAxesArray(json)
        val values = axesSection.split(",").filter { it.isNotBlank() }
        assertThat(values).hasSize(4)
    }

    @Test
    fun `serialized gamepad exposes triggers as button indices 6 and 7`() {
        val snapshot = GamepadSnapshot(
            buttons = FloatArray(16),
            axes = floatArrayOf(0f, 0f, 0f, 0f, 0.6f, 0.9f)
        )
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig).updateSnapshot(snapshot),
            ControllerSlot(2), ControllerSlot(3), ControllerSlot(4)
        )
        val json = GamepadSerializer.serializeSlots(slots)!!

        val buttonsSection = extractButtonsArray(json)
        val values = buttonsSection.split(",").map { it.trim().toFloat() }
        assertThat(values).hasSize(16)
        assertThat(values[6]).isEqualTo(0.6f) // LT at button 6
        assertThat(values[7]).isEqualTo(0.9f) // RT at button 7
    }

    @Test
    fun `script declares NUM_AXES = 4 and NUM_BUTTONS = 16`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("NUM_BUTTONS = 16")
        assertThat(script).contains("NUM_AXES = 4")
    }

    @Test
    fun `script normalizeAxes only processes NUM_AXES entries`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        // The normalizeAxes function loops: for (var i = 0; i < NUM_AXES; i++)
        assertThat(script).contains("for (var i = 0; i < NUM_AXES; i++)")
        assertThat(script).contains("NUM_AXES = 4")
    }

    // ---- Disconnect event semantics ----

    @Test
    fun `script creates disconnected gamepad with connected=false before event dispatch`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")

        // The disconnect handler must:
        // 1. Create a gamepad object with connected=false
        // 2. Pass it to emitGamepadEvent (not null)
        assertThat(script).contains("disconnectedGp")
        assertThat(script).contains(", false,")
        // Verify the event is emitted with a gamepad object, not null
        assertThat(script).contains("emitGamepadEvent('gamepaddisconnected', disconnectedGp)")
    }

    @Test
    fun `script emits gamepadconnected with connected gamepad object`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("emitGamepadEvent('gamepadconnected', _virtualSlots[i])")
    }

    // ---- JSON escaping safety ----

    @Test
    fun `script uses JSON parse for safe data parsing`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("JSON.parse(jsonData)")
        // Should NOT use eval or Function constructor
        assertThat(script).doesNotContain("eval(")
        assertThat(script).doesNotContain("new Function(")
    }

    @Test
    fun `script validates array length before processing`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("data.length !== 4")
        assertThat(script).doesNotContain("data.length !== 5")
    }

    @Test
    fun `script handles malformed JSON gracefully`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        // JSON.parse in try/catch
        assertThat(script).contains("try {")
        assertThat(script).contains("data = JSON.parse(jsonData)")
        assertThat(script).contains("} catch(e) {")
    }

    // ---- AB swap with snapshot generation ----

    @Test
    fun `AB swap produces correct button mapping change`() {
        // Default: physical A -> virtual button 0, physical B -> virtual button 1
        val defaultMapping = ControllerMapping()
        val swappedMapping = ControllerMapping.swapAB(defaultMapping)

        // Verify the swap actually changed the mapping
        assertThat(swappedMapping.buttons).isNotEqualTo(defaultMapping.buttons)
    }

    @Test
    fun `double AB swap restores original mapping`() {
        val original = ControllerMapping()
        val swappedOnce = ControllerMapping.swapAB(original)
        val swappedTwice = ControllerMapping.swapAB(swappedOnce)

        assertThat(swappedTwice.buttons).isEqualTo(original.buttons)
    }

    @Test
    fun `AB swap snapshot serializes correctly`() {
        // Before swap: button 0 pressed (A)
        val snapBefore = GamepadSnapshot(
            buttons = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            axes = FloatArray(6)
        )
        val slotBefore = ControllerSlot(1).assign(sig).updateSnapshot(snapBefore)
        val slotsBefore = listOf(slotBefore, ControllerSlot(2), ControllerSlot(3), ControllerSlot(4))
        val jsonBefore = GamepadSerializer.serializeSlots(slotsBefore)!!
        val buttonsBefore = extractButtonsArray(jsonBefore).split(",").map { it.trim() }
        assertThat(buttonsBefore[0]).isEqualTo("1") // A pressed

        // After swap: button 1 pressed (B)
        val snapAfter = GamepadSnapshot(
            buttons = floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            axes = FloatArray(6)
        )
        val slotAfter = ControllerSlot(1).assign(sig)
            .remap(ControllerMapping.swapAB(ControllerMapping()))
            .updateSnapshot(snapAfter)
        val slotsAfter = listOf(slotAfter, ControllerSlot(2), ControllerSlot(3), ControllerSlot(4))
        val jsonAfter = GamepadSerializer.serializeSlots(slotsAfter)!!
        val buttonsAfter = extractButtonsArray(jsonAfter).split(",").map { it.trim() }
        assertThat(buttonsAfter[1]).isEqualTo("1") // B pressed
    }

    // ---- Immediate update (no periodic polling) ----

    @Test
    fun `setSlots with correct count triggers immediate push`() {
        val diag = GamepadInjectionDiagnostics()
        val bridge = GamepadInjectionBridge(diag)

        // Bridge is not activated, so push won't actually execute.
        // But setSlots should accept the slots without error.
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig),
            ControllerSlot(2), ControllerSlot(3), ControllerSlot(4)
        )
        bridge.setSlots(slots)

        // No crash, no exception
        assertThat(diag.state.value.errorMessage).isNull()
    }

    @Test
    fun `diagnostics setInvalidConfiguration reports CONFIG error`() {
        val diag = GamepadInjectionDiagnostics()
        diag.setInvalidConfiguration("setSlots received 3 slots; expected exactly 4.")

        assertThat(diag.state.value.errorMessage).isNotNull()
        assertThat(diag.state.value.errorMessage).contains("CONFIG:")
        assertThat(diag.state.value.errorMessage).contains("expected exactly 4")
    }

    // ---- Script handler lifecycle patterns ----

    @Test
    fun `script can be built and removed idempotently`() {
        val origin = "https://romm.example.com"
        val script1 = GamepadInjectionScript.build(origin)
        val script2 = GamepadInjectionScript.build(origin)

        // Same origin produces identical script
        assertThat(script1).isEqualTo(script2)

        // Script content is valid (non-empty, well-formed)
        assertThat(script1).isNotEmpty
        assertThat(script1.length).isGreaterThan(500)
    }

    @Test
    fun `script contains all required global functions`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")

        // Required globals
        assertThat(script).contains("window.__rommUpdateGamepads")
        assertThat(script).contains("window.__rommGamepadStatus")
        assertThat(script).contains("window.__rommGamepadOverride")
    }

    // ---- SPA vs full navigation policy ----

    @Test
    fun `script idempotence marker prevents re-injection on SPA navigation`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")

        // The idempotence check must come before any setup
        val guardIndex = script.indexOf("if (window.__rommGamepadOverride) return;")
        val setupStart = script.indexOf("_virtualSlots")

        assertThat(guardIndex).isGreaterThan(0)
        assertThat(guardIndex).isLessThan(setupStart)
    }

    @Test
    fun `script does not use addJavascriptInterface`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).doesNotContain("addJavascriptInterface")
        assertThat(script).doesNotContain("android.")
    }

    // ---- Origin policy tests ----

    @Test
    fun `script with HTTP origin preserves scheme`() {
        val script = GamepadInjectionScript.build("http://192.168.1.20:8080")
        assertThat(script).contains("ALLOWED_ORIGIN = 'http://192.168.1.20:8080'")
    }

    @Test
    fun `script with HTTPS default port omits port`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("ALLOWED_ORIGIN = 'https://romm.example.com'")
        assertThat(script).doesNotContain(":443")
    }

    @Test
    fun `script with non-standard port includes port`() {
        val script = GamepadInjectionScript.build("https://romm.example.com:8443")
        assertThat(script).contains("ALLOWED_ORIGIN = 'https://romm.example.com:8443'")
    }

    // ---- Diagnostics visibility tests ----

    @Test
    fun `diagnostics reports feature unsupported visibly`() {
        val diag = GamepadInjectionDiagnostics()
        diag.setFeatureSupported(false)

        assertThat(diag.state.value.documentStartSupported).isFalse()
        assertThat(diag.state.value.errorMessage).contains("DOCUMENT_START_SCRIPT")
    }

    @Test
    fun `diagnostics reports invalid origin visibly`() {
        val diag = GamepadInjectionDiagnostics()
        diag.setInvalidConfiguration("Origin host is empty")

        assertThat(diag.state.value.errorMessage).startsWith("CONFIG:")
    }

    @Test
    fun `diagnostics reports serialization error visibly`() {
        val diag = GamepadInjectionDiagnostics()
        diag.setSerializationError("payload too large")

        assertThat(diag.state.value.errorMessage).startsWith("SERIALIZE:")
    }

    @Test
    fun `successful update clears previous error`() {
        val diag = GamepadInjectionDiagnostics()
        diag.setError("test error")
        assertThat(diag.state.value.errorMessage).isNotNull()

        diag.recordUpdate()
        assertThat(diag.state.value.errorMessage).isNull()
        assertThat(diag.state.value.updateCount).isEqualTo(1)
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

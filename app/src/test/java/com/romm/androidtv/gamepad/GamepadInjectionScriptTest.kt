package com.romm.androidtv.gamepad

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

/**
 * Unit tests for GamepadInjectionScript JavaScript generation.
 *
 * Validates:
 * - Origin embedding correctness
 * - Idempotence marker presence
 * - Standard Gamepad field names
 * - No placeholder leakage
 * - W3C compliance: 4 axes, triggers as buttons 6/7
 * - defineProperty usage for getGamepads
 * - Disconnect event semantics (connected=false before dispatch)
 */
class GamepadInjectionScriptTest {

    @Test
    fun `build embeds allowed origin as string literal`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("https://romm.example.com")
        assertThat(script).doesNotContain(GamepadInjectionScript.ALLOWED_ORIGIN_PLACEHOLDER)
    }

    @Test
    fun `build escapes backslashes and single quotes in origin`() {
        val script = GamepadInjectionScript.build("http://host\\\\'example.com")
        assertThat(script).contains("\\\\\\\\'")
    }

    @Test
    fun `build contains idempotence marker check`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("__rommGamepadOverride")
        assertThat(script).contains("if (window.__rommGamepadOverride) return;")
    }

    @Test
    fun `build contains origin self-check`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("document.location.origin")
        assertThat(script).contains("ALLOWED_ORIGIN")
    }

    @Test
    fun `build uses defineProperty for getGamepads`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("defineProperty(navigator, 'getGamepads'")
        assertThat(script).contains("configurable: true")
        assertThat(script).contains("enumerable: true")
    }

    @Test
    fun `build creates exactly 4 virtual slots`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("[null, null, null, null]")
        assertThat(script).doesNotContain("[null, null, null, null, null]")
    }

    @Test
    fun `build creates Gamepad objects with standard fields`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("id:")
        assertThat(script).contains("index:")
        assertThat(script).contains("connected:")
        assertThat(script).contains("mapping:")
        assertThat(script).contains("timestamp:")
        assertThat(script).contains("buttons:")
        assertThat(script).contains("axes:")
    }

    @Test
    fun `build uses W3C standard 16 buttons and 4 axes`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("NUM_BUTTONS = 16")
        assertThat(script).contains("NUM_AXES = 4")
    }

    @Test
    fun `build exposes __rommUpdateGamepads global function`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("window.__rommUpdateGamepads = function")
    }

    @Test
    fun `build emits gamepadconnected event on connection`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("gamepadconnected")
    }

    @Test
    fun `build emits gamepaddisconnected event on disconnection`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("gamepaddisconnected")
    }

    @Test
    fun `build includes GamepadEvent fallback`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("GamepadEvent")
        assertThat(script).contains("createEvent")
    }

    @Test
    fun `build exposes diagnostics status object`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("__rommGamepadStatus")
        assertThat(script).contains("injected: true")
        assertThat(script).contains("numButtons:")
        assertThat(script).contains("numAxes:")
    }

    @Test
    fun `build normalizes buttons to pressed touched value objects`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("pressed:")
        assertThat(script).contains("touched:")
        assertThat(script).contains("value:")
    }

    @Test
    fun `build clamps button values to zero to one range`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("Math.max(0")
        assertThat(script).contains("Math.min(1")
    }

    @Test
    fun `build clamps axis values to minus one to one range`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("Math.max(-1")
    }

    @Test
    fun `build validates finite numeric values`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("isFinite")
    }

    @Test
    fun `build returns null for disconnected slots in getGamepads`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("result.push(null)")
    }

    @Test
    fun `build with http origin preserves scheme`() {
        val script = GamepadInjectionScript.build("http://192.168.1.20:8080")
        assertThat(script).contains("http://192.168.1.20:8080")
    }

    @Test
    fun `build with port in origin preserves port`() {
        val script = GamepadInjectionScript.build("https://romm.example.com:3443")
        assertThat(script).contains("https://romm.example.com:3443")
    }

    @Test
    fun `build sets connected false before dispatching disconnect event`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        // The disconnect handler must create a gamepad with connected=false
        // and pass it to emitGamepadEvent, not null.
        assertThat(script).contains("createVirtualGamepad(")
        assertThat(script).contains(", false,")
        // Verify the pattern: disconnected gamepad created before event emission
        val disconnectSection = script.substringAfter("gamepaddisconnected")
            .substringBefore("};", "")
        // The script should create a disconnected version before emitting
        assertThat(script).contains("disconnectedGp")
    }

    @Test
    fun `build parses JSON safely via JSON parse`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("JSON.parse(jsonData)")
    }

    @Test
    fun `build accepts exactly four native slots and acknowledges updates`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("data.length !== 4")
        assertThat(script).contains("return true;")
        assertThat(script).doesNotContain("data.length !== 5")
    }

    @Test
    fun `build has fallback assignment if defineProperty fails`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("catch(e)")
        assertThat(script).contains("getGamepads = function()")
    }

    @Test
    fun `build origin check comes before getGamepads override`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        val originCheckIndex = script.indexOf("document.location.origin !== ALLOWED_ORIGIN")
        val definePropIndex = script.indexOf("defineProperty(navigator, 'getGamepads'")
        assertThat(originCheckIndex).isGreaterThan(0)
        assertThat(definePropIndex).isGreaterThan(originCheckIndex)
    }
}

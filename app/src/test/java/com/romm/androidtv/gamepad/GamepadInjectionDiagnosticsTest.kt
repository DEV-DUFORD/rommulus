package com.romm.androidtv.gamepad

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

/**
 * Unit tests for GamepadInjectionDiagnostics.
 *
 * Validates:
 * - Feature support state transitions
 * - Error message visibility
 * - Monotonic update counter
 * - Timestamp tracking
 * - Reset behavior
 * - Invalid configuration errors
 * - Serialization error visibility
 */
class GamepadInjectionDiagnosticsTest {

    @Test
    fun `initial state has all defaults`() {
        val diag = GamepadInjectionDiagnostics()
        val state = diag.state.value

        assertThat(state.documentStartSupported).isFalse()
        assertThat(state.scriptInjected).isFalse()
        assertThat(state.allowedOrigin).isNull()
        assertThat(state.lastUpdateEpochMs).isZero()
        assertThat(state.updateCount).isZero()
        assertThat(state.errorMessage).isNull()
    }

    @Test
    fun `setFeatureSupported true clears error`() {
        val diag = GamepadInjectionDiagnostics()
        diag.setFeatureSupported(true)

        assertThat(diag.state.value.documentStartSupported).isTrue()
        assertThat(diag.state.value.errorMessage).isNull()
    }

    @Test
    fun `setFeatureSupported false sets descriptive error`() {
        val diag = GamepadInjectionDiagnostics()
        diag.setFeatureSupported(false)

        assertThat(diag.state.value.documentStartSupported).isFalse()
        assertThat(diag.state.value.errorMessage).contains("DOCUMENT_START_SCRIPT")
    }

    @Test
    fun `setScriptInjected true clears error when feature supported`() {
        val diag = GamepadInjectionDiagnostics()
        diag.setFeatureSupported(true)
        diag.setScriptInjected(true, "https://romm.example.com")

        assertThat(diag.state.value.scriptInjected).isTrue()
        assertThat(diag.state.value.allowedOrigin).isEqualTo("https://romm.example.com")
        assertThat(diag.state.value.errorMessage).isNull()
    }

    @Test
    fun `setScriptInjected false sets error when feature supported`() {
        val diag = GamepadInjectionDiagnostics()
        diag.setFeatureSupported(true)
        diag.setScriptInjected(false, null)

        assertThat(diag.state.value.scriptInjected).isFalse()
        assertThat(diag.state.value.errorMessage).contains("failed")
    }

    @Test
    fun `setScriptInjected false does NOT set error when feature unsupported`() {
        val diag = GamepadInjectionDiagnostics()
        diag.setFeatureSupported(false)
        diag.setScriptInjected(false, null)

        assertThat(diag.state.value.errorMessage).contains("DOCUMENT_START_SCRIPT")
    }

    @Test
    fun `recordUpdate increments counter monotonically`() {
        val diag = GamepadInjectionDiagnostics()

        repeat(10) {
            diag.recordUpdate()
        }

        assertThat(diag.state.value.updateCount).isEqualTo(10)
    }

    @Test
    fun `recordUpdate sets lastUpdateEpochMs`() {
        val diag = GamepadInjectionDiagnostics()
        diag.recordUpdate()

        assertThat(diag.state.value.lastUpdateEpochMs).isGreaterThan(0)
        assertThat(diag.state.value.lastUpdateEpochMs).isLessThanOrEqualTo(System.currentTimeMillis())
    }

    @Test
    fun `recordUpdate preserves existing feature state`() {
        val diag = GamepadInjectionDiagnostics()
        diag.setFeatureSupported(true)
        diag.setScriptInjected(true, "https://romm.example.com")
        diag.recordUpdate()

        val state = diag.state.value
        assertThat(state.documentStartSupported).isTrue()
        assertThat(state.scriptInjected).isTrue()
        assertThat(state.allowedOrigin).isEqualTo("https://romm.example.com")
        assertThat(state.updateCount).isEqualTo(1)
    }

    @Test
    fun `setError overrides previous error message`() {
        val diag = GamepadInjectionDiagnostics()
        diag.setError("First error")
        assertThat(diag.state.value.errorMessage).isEqualTo("First error")

        diag.setError("Second error")
        assertThat(diag.state.value.errorMessage).isEqualTo("Second error")
    }

    @Test
    fun `reset restores all defaults`() {
        val diag = GamepadInjectionDiagnostics()
        diag.setFeatureSupported(true)
        diag.setScriptInjected(true, "https://romm.example.com")
        diag.recordUpdate()
        diag.recordUpdate()

        diag.reset()
        val state = diag.state.value

        assertThat(state.documentStartSupported).isFalse()
        assertThat(state.scriptInjected).isFalse()
        assertThat(state.allowedOrigin).isNull()
        assertThat(state.lastUpdateEpochMs).isZero()
        assertThat(state.updateCount).isZero()
        assertThat(state.errorMessage).isNull()
    }

    @Test
    fun `StateFlow value reflects state changes`() {
        val diag = GamepadInjectionDiagnostics()
        assertThat(diag.state.value.documentStartSupported).isFalse()

        diag.setFeatureSupported(true)
        assertThat(diag.state.value.documentStartSupported).isTrue()
    }

    @Test
    fun `setInvalidConfiguration produces CONFIG prefixed error`() {
        val diag = GamepadInjectionDiagnostics()
        diag.setInvalidConfiguration("slot count mismatch")

        assertThat(diag.state.value.errorMessage).isEqualTo("CONFIG: slot count mismatch")
    }

    @Test
    fun `setSerializationError produces SERIALIZE prefixed error`() {
        val diag = GamepadInjectionDiagnostics()
        diag.setSerializationError("payload too large")

        assertThat(diag.state.value.errorMessage).isEqualTo("SERIALIZE: payload too large")
    }

    @Test
    fun `recordUpdate clears previous error`() {
        val diag = GamepadInjectionDiagnostics()
        diag.setError("Previous error")
        assertThat(diag.state.value.errorMessage).isNotNull()

        diag.recordUpdate()
        assertThat(diag.state.value.errorMessage).isNull()
    }

    @Test
    fun `invalid slot count error is visible`() {
        val diag = GamepadInjectionDiagnostics()
        diag.setInvalidConfiguration("setSlots received 3 slots; expected exactly 4.")

        assertThat(diag.state.value.errorMessage).contains("CONFIG:")
        assertThat(diag.state.value.errorMessage).contains("3 slots")
        assertThat(diag.state.value.errorMessage).contains("expected exactly 4")
    }
}

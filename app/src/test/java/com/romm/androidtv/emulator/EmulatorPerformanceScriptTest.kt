package com.romm.androidtv.emulator

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EmulatorPerformanceScriptTest {

    @Test
    fun `build restricts policy to configured origin`() {
        val script = EmulatorPerformanceScript.build("https://romm.example.com")

        assertThat(script).contains("https://romm.example.com")
        assertThat(script).contains("document.location.origin !== ALLOWED_ORIGIN")
    }

    @Test
    fun `build forces expensive EmulatorJS options off`() {
        val script = EmulatorPerformanceScript.build("https://romm.example.com")

        assertThat(script).contains("shader: 'disabled'")
        assertThat(script).contains("rewindEnabled: 'disabled'")
        assertThat(script).contains("defineProperty(window, 'EJS_defaultOptions'")
    }

    @Test
    fun `build preserves unrecognized core options`() {
        val script = EmulatorPerformanceScript.build("https://romm.example.com")

        assertThat(script).contains(": value;")
        assertThat(script).contains("hasOwnProperty.call(forcedOptions, property)")
    }

    @Test
    fun `build does not substitute Picodrive for Genesis Plus GX by default`() {
        val script = EmulatorPerformanceScript.build("https://romm.example.com")

        assertThat(script).doesNotContain("genesis_plus_gx")
        assertThat(script).doesNotContain("picodrive")
        assertThat(script).contains("defineProperty(window, 'EJS_core'")
        assertThat(script).contains(": value;")
    }

    @Test
    fun `build omits the Genesis fallback when explicitly disabled`() {
        val script = EmulatorPerformanceScript.build(
            "https://romm.example.com",
            enableUnvalidatedGenesisFallback = false
        )

        assertThat(script).doesNotContain("genesis_plus_gx")
        assertThat(script).doesNotContain("picodrive")
    }

    @Test
    fun `build only substitutes Picodrive for Genesis Plus GX when explicitly enabled`() {
        val script = EmulatorPerformanceScript.build(
            "https://romm.example.com",
            enableUnvalidatedGenesisFallback = true
        )

        assertThat(script).contains("genesis_plus_gx: 'picodrive'")
        assertThat(script).contains("defineProperty(window, 'EJS_core'")
        assertThat(script).contains(": value;")
    }

    @Test
    fun `build exposes an idempotent diagnostics marker`() {
        val script = EmulatorPerformanceScript.build("https://romm.example.com")

        assertThat(script).contains("if (window.__rommEmulatorPerformancePolicy) return;")
        assertThat(script).contains("window.__rommEmulatorPerformancePolicy =")
    }
}

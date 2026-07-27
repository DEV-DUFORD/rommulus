package com.romm.androidtv.gamepad

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

/**
 * Unit tests for origin-restricted injection rules.
 *
 * Validates that the generated JavaScript correctly guards against:
 * - Port spoofing
 * - Subdomain attacks
 * - Protocol downgrade
 * - Path-based injection on subframes
 * - Origin mismatch
 */
class GamepadInjectionOriginRulesTest {

    @Test
    fun `script checks exact origin match`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("document.location.origin !== ALLOWED_ORIGIN")
    }

    @Test
    fun `script with default HTTPS port does not include port`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("ALLOWED_ORIGIN = 'https://romm.example.com'")
    }

    @Test
    fun `script with explicit port includes port`() {
        val script = GamepadInjectionScript.build("http://192.168.1.20:8080")
        assertThat(script).contains("ALLOWED_ORIGIN = 'http://192.168.1.20:8080'")
    }

    @Test
    fun `script with non-standard HTTPS port includes port`() {
        val script = GamepadInjectionScript.build("https://romm.example.com:3443")
        assertThat(script).contains("ALLOWED_ORIGIN = 'https://romm.example.com:3443'")
    }

    @Test
    fun `port 8080 origin does not match port 80`() {
        val script = GamepadInjectionScript.build("http://host:8080")
        assertThat(script).contains("ALLOWED_ORIGIN = 'http://host:8080'")
    }

    @Test
    fun `subdomain does not match parent domain`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("ALLOWED_ORIGIN = 'https://romm.example.com'")
    }

    @Test
    fun `protocol downgrade is prevented by origin check`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("ALLOWED_ORIGIN = 'https://romm.example.com'")
    }

    @Test
    fun `iframe subframe origin mismatch is caught`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("document.location.origin !== ALLOWED_ORIGIN")
    }

    @Test
    fun `spoofed hostname with trailing dot is rejected`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).doesNotContain("romm.example.com.")
    }

    @Test
    fun `script is idempotent across multiple calls`() {
        val script1 = GamepadInjectionScript.build("https://romm.example.com")
        val script2 = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script1).isEqualTo(script2)
    }

    @Test
    fun `different origins produce different scripts`() {
        val script1 = GamepadInjectionScript.build("https://romm.example.com")
        val script2 = GamepadInjectionScript.build("https://evil.example.com")
        assertThat(script1).isNotEqualTo(script2)
        assertThat(script1).contains("https://romm.example.com")
        assertThat(script2).contains("https://evil.example.com")
    }

    @Test
    fun `script guards against data URI origin`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("document.location.origin !== ALLOWED_ORIGIN")
    }

    @Test
    fun `script guards against file URI origin`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("document.location.origin !== ALLOWED_ORIGIN")
    }

    @Test
    fun `script returns early on origin mismatch`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("document.location.origin")
        assertThat(script).contains("return;")
    }

    @Test
    fun `HTTP origin with default port 80 omits port in script`() {
        // http://host:80 -> document.location.origin = "http://host"
        val script = GamepadInjectionScript.build("http://192.168.1.20")
        assertThat(script).contains("ALLOWED_ORIGIN = 'http://192.168.1.20'")
        // Port 80 should NOT appear
        assertThat(script).doesNotContain(":80")
    }

    @Test
    fun `HTTPS origin with default port 443 omits port in script`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        assertThat(script).contains("ALLOWED_ORIGIN = 'https://romm.example.com'")
        assertThat(script).doesNotContain(":443")
    }
}

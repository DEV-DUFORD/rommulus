package com.romm.desktop.storage.secret.dbus

import com.romm.desktop.storage.secret.KeyringState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * Exercises the REAL [SecretServiceDbusBackend] against a live Secret Service on the session bus.
 *
 * Enabled only when `rommulus.secretServiceBus` is non-blank (plumbed from `$DBUS_SESSION_BUS_ADDRESS`
 * by build.gradle.kts) AND a Secret Service is actually reachable — otherwise each test aborts via
 * [Assumptions] and Gradle reports it as SKIPPED, never failed (this is the macOS/no-bus case).
 *
 * `rommulus.secretServiceMode` selects which assertions apply:
 *  - "available" (or unset) -> normal round-trip / isolation / overwrite / delete / clearAll tests.
 *  - "locked"               -> fail-closed test: state()==Locked, store() false, retrieve() null.
 *  - "unavailable"          -> fail-closed test: state()==Unavailable, store() false, retrieve() null.
 *
 * All @Test methods use block bodies `{ ... }` deliberately: an expression body returning a
 * non-Unit value makes JUnit silently skip the test.
 */
@EnabledIfSystemProperty(named = "rommulus.secretServiceBus", matches = ".+")
@EnabledIfSystemProperty(named = "rommulus.secretServiceMode", matches = ".+")
class SecretServiceDbusBackendConformanceTest {

    private val backend = SecretServiceDbusBackend(timeoutMillis = 5_000L)

    // --- gating ---------------------------------------------------------------

    private fun mode(): String = System.getProperty("rommulus.secretServiceMode") ?: ""

    /** Every test requires a non-blank bus address; abort (skip) otherwise. */
    private fun assumeBus() {
        Assumptions.assumeTrue(
            (System.getProperty("rommulus.secretServiceBus") ?: "").isNotBlank(),
            "no D-Bus session bus configured (rommulus.secretServiceBus is blank)",
        )
    }

    /** Normal-data-path tests: need an unlocked, reachable Secret Service. */
    private fun assumeAvailable() {
        assumeBus()
        Assumptions.assumeTrue(
            mode() in setOf("", "available"),
            "not running in available mode (ROM_SECRET_MODE=${mode()})",
        )
        Assumptions.assumeTrue(
            backend.state() is KeyringState.Available,
            "no unlocked Secret Service on this bus; skipping data-path tests",
        )
    }

    /** Best-effort wipe between tests; a no-op when locked/unavailable (fail-closed contract). */
    @BeforeEach
    fun clearState() {
        backend.deleteAll()
    }

    // --- available mode ---------------------------------------------------------

    @Test
    fun `set get round trips`() {
        assumeAvailable()
        assertThat(backend.store("scope-1", "top-secret")).isTrue()
        assertThat(backend.retrieve("scope-1")).isEqualTo("top-secret")
    }

    @Test
    fun `absent scope returns null`() {
        assumeAvailable()
        assertThat(backend.retrieve("does-not-exist")).isNull()
    }

    @Test
    fun `scopes are isolated across origins`() {
        assumeAvailable()
        assertThat(backend.store("origin-a|user", "value-a")).isTrue()
        assertThat(backend.retrieve("origin-b|user")).isNull()
        assertThat(backend.retrieve("origin-a|user")).isEqualTo("value-a")
    }

    @Test
    fun `second write overwrites`() {
        assumeAvailable()
        assertThat(backend.store("scope-1", "first")).isTrue()
        assertThat(backend.store("scope-1", "second")).isTrue()
        assertThat(backend.retrieve("scope-1")).isEqualTo("second")
    }

    @Test
    fun `delete removes only matching scope`() {
        assumeAvailable()
        assertThat(backend.store("scope-a", "a")).isTrue()
        assertThat(backend.store("scope-b", "b")).isTrue()
        backend.delete("scope-a")
        assertThat(backend.retrieve("scope-a")).isNull()
        assertThat(backend.retrieve("scope-b")).isEqualTo("b")
    }

    @Test
    fun `clearAll removes every scope`() {
        assumeAvailable()
        assertThat(backend.store("scope-a", "a")).isTrue()
        assertThat(backend.store("scope-b", "b")).isTrue()
        backend.deleteAll()
        assertThat(backend.retrieve("scope-a")).isNull()
        assertThat(backend.retrieve("scope-b")).isNull()
    }

    @Test
    fun `state is available when unlocked`() {
        assumeAvailable()
        assertThat(backend.state()).isEqualTo(KeyringState.Available)
    }

    // --- fail-closed modes -------------------------------------------------------

    @Test
    fun `locked keyring fails closed`() {
        assumeBus()
        Assumptions.assumeTrue(mode() == "locked", "not running in locked mode (ROM_SECRET_MODE=${mode()})")
        assertThat(backend.state()).isEqualTo(KeyringState.Locked)
        assertThat(backend.store("scope-1", "top-secret")).isFalse()
        assertThat(backend.retrieve("scope-1")).isNull()
    }

    @Test
    fun `unavailable service fails closed`() {
        assumeBus()
        Assumptions.assumeTrue(mode() == "unavailable", "not running in unavailable mode (ROM_SECRET_MODE=${mode()})")
        assertThat(backend.state()).isEqualTo(KeyringState.Unavailable)
        assertThat(backend.store("scope-1", "top-secret")).isFalse()
        assertThat(backend.retrieve("scope-1")).isNull()
    }
}

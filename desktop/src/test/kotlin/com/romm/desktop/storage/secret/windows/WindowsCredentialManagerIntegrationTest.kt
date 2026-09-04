package com.romm.desktop.storage.secret.windows

import com.romm.desktop.storage.secret.KeyringState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.UUID

/**
 * Real Windows Credential Manager round-trip (plans/WINDOWS_IMPL.md §4.3) — the host-native
 * confirmation for the JNA seam and backend against the live Win32 API.
 *
 * Gated two ways so it is inert on macOS/Linux dev machines and in unconfigured CI:
 *  - `@EnabledOnOs(OS.WINDOWS)` — only runs on a Windows host;
 *  - `rommulus.windowsCredentialIntegration` system property (plumbed from the
 *    `ROMM_WINDOWS_CREDENTIAL_INTEGRATION` env var in `desktop/build.gradle.kts`) must be
 *    `true` — set it on the `windows-2022` runner:
 *
 * ```powershell
 * $env:ROMM_WINDOWS_CREDENTIAL_INTEGRATION = "1"
 * .\gradlew :desktop:test --tests "com.romm.desktop.storage.secret.windows.WindowsCredentialManagerIntegrationTest"
 * ```
 *
 * Every run uses a UUID-scoped target and deletes it afterwards (including on failure), so the
 * test leaves no credentials behind.
 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "rommulus.windowsCredentialIntegration", matches = "true")
@DisplayName("Windows Credential Manager — real round-trip (gated)")
class WindowsCredentialManagerIntegrationTest {

    private val api = JnaWindowsCredentialApi()
    private val backend = WindowsCredentialBackend(api)

    private val scope = "https://integration-test.romm.app|rommulus-it-${UUID.randomUUID()}"
    private val target = WindowsCredentialTargets.targetForScope(scope)

    @AfterEach
    fun cleanup() {
        backend.delete(scope)
    }

    @Test
    fun `credential manager is available on this host`() {
        assertThat(backend.state()).isEqualTo(KeyringState.Available)
    }

    @Test
    fun `store, replace, retrieve, and delete round-trip against the real credential manager`() {
        val first = "it-token-${UUID.randomUUID()}"
        val second = "it-token-${UUID.randomUUID()}"

        assertThat(backend.store(scope, first)).`as`("initial store").isTrue()
        assertThat(backend.retrieve(scope)).isEqualTo(first)

        // Replace-on-write: same target, new value.
        assertThat(backend.store(scope, second)).`as`("replace store").isTrue()
        assertThat(backend.retrieve(scope)).isEqualTo(second)

        // The credential is enumerable under the app prefix.
        val enumerated = api.enumerateTargets(WindowsCredentialTargets.APP_PREFIX + "*")
        assertThat(enumerated).isInstanceOf(CredentialEnumerateResult.Ok::class.java)
        assertThat((enumerated as CredentialEnumerateResult.Ok).targetNames).contains(target)

        // Exact delete: the credential is gone, and deleting again is idempotent success.
        backend.delete(scope)
        assertThat(backend.retrieve(scope)).isNull()
        backend.delete(scope)
        assertThat(backend.retrieve(scope)).isNull()
    }

    @Test
    fun `deleteAll removes the app credential and leaves foreign targets untouched`() {
        backend.store(scope, "it-token-${UUID.randomUUID()}")

        backend.deleteAll()

        assertThat(backend.retrieve(scope)).isNull()
        // The app target is gone; any foreign (non-RomMulus) credentials on the runner remain —
        // deleteAll only ever deletes app-owned targets (see the fake-seam unit test for the
        // foreign-target isolation proof).
        val remaining = api.enumerateTargets(WindowsCredentialTargets.APP_PREFIX + "*")
        assertThat(remaining).isInstanceOf(CredentialEnumerateResult.Ok::class.java)
        assertThat((remaining as CredentialEnumerateResult.Ok).targetNames).doesNotContain(target)
    }
}

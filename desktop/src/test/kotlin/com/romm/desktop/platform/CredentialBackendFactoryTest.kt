package com.romm.desktop.platform

import com.romm.androidtv.storage.AppPaths
import com.romm.desktop.storage.secret.FileSecretBackend
import com.romm.desktop.storage.secret.UnavailableSecretServiceFallback
import com.romm.desktop.storage.secret.dbus.SecretServiceDbusBackend
import com.romm.desktop.storage.secret.windows.FakeWindowsCredentialApi
import com.romm.desktop.storage.secret.windows.WindowsCredentialBackend
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Credential backend selection (plans/WINDOWS_IMPL.md §4.1/§4.3): the factory is the single place
 * that decides which credential storage a host may use. The critical Windows guarantee —
 * Credential Manager only, never the D-Bus backend or the plaintext file fallback — is asserted
 * here so it holds before the startup integration lane wires the factory into Main.
 */
@DisplayName("Credential backend factory — platform selection")
class CredentialBackendFactoryTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * [AppPaths] rooted in a temp dir so the Linux wiring never touches the real home.
     * Properties are getters (not vals) because [tempDir] is injected by JUnit after
     * construction.
     */
    private val appPaths = object : AppPaths {
        override val configDir: Path get() = tempDir.resolve("config")
        override val dataDir: Path get() = tempDir.resolve("data")
        override val stateDir: Path get() = tempDir.resolve("state")
        override val cacheDir: Path get() = tempDir.resolve("cache")
    }

    @Test
    fun `windows x86_64 selects the Windows credential backend`() {
        val backend = CredentialBackendFactory.create(
            DesktopPlatformDetector.detect("Windows 11", "amd64"),
            appPaths,
        )
        assertThat(backend).isInstanceOf(WindowsCredentialBackend::class.java)
    }

    @Test
    fun `windows never selects the D-Bus or plaintext file fallback`() {
        val backend = CredentialBackendFactory.create(
            DesktopPlatformDetector.detect("Windows 10", "x86_64"),
            appPaths,
        )
        // The fallback wrapper is the only production type that pairs a primary with the
        // FileSecretBackend; Windows must not produce it (no plaintext token fallback).
        assertThat(backend).isNotInstanceOf(UnavailableSecretServiceFallback::class.java)
        assertThat(backend).isNotInstanceOf(FileSecretBackend::class.java)
        assertThat(backend).isNotInstanceOf(SecretServiceDbusBackend::class.java)
    }

    @Test
    fun `linux x86_64 keeps the current D-Bus primary with file fallback`() {
        val backend = CredentialBackendFactory.create(
            DesktopPlatformDetector.detect("Linux", "amd64"),
            appPaths,
        )
        assertThat(backend).isInstanceOf(UnavailableSecretServiceFallback::class.java)
        assertThat(backend).isNotInstanceOf(WindowsCredentialBackend::class.java)
    }

    @Test
    fun `macOS development host keeps the current Linux-compatible wiring`() {
        val backend = CredentialBackendFactory.create(
            DesktopPlatformDetector.detect("Mac OS X", "aarch64"),
            appPaths,
        )
        assertThat(backend).isInstanceOf(UnavailableSecretServiceFallback::class.java)
        assertThat(backend).isNotInstanceOf(WindowsCredentialBackend::class.java)
    }

    @Test
    fun `unsupported hosts throw instead of selecting any backend`() {
        assertThatThrownBy {
            CredentialBackendFactory.create(DesktopPlatformDetector.detect("SunOS", "sparc"), appPaths)
        }.isInstanceOf(UnsupportedHostException::class.java)

        assertThatThrownBy {
            CredentialBackendFactory.create(DesktopPlatformDetector.detect("Linux", "aarch64"), appPaths)
        }.isInstanceOf(UnsupportedHostException::class.java)
    }

    @Test
    fun `windows selection is stable across repeated calls`() {
        val first = CredentialBackendFactory.create(DesktopPlatformDetector.detect("Windows 11", "amd64"), appPaths)
        val second = CredentialBackendFactory.create(DesktopPlatformDetector.detect("Windows 11", "amd64"), appPaths)
        assertThat(first).isInstanceOf(WindowsCredentialBackend::class.java)
        assertThat(second).isInstanceOf(WindowsCredentialBackend::class.java)
    }

    @Test
    fun `injected windows api seam is used by the selected backend`() {
        val fake = FakeWindowsCredentialApi()
        val backend = CredentialBackendFactory.create(
            DesktopPlatformDetector.detect("Windows 11", "amd64"),
            appPaths,
            windowsApi = fake,
        )
        assertThat(backend).isInstanceOf(WindowsCredentialBackend::class.java)
        // The backend must route through the injected seam, not construct its own.
        assertThat(backend.store("https://romm.example.com|alice", "tok")).isTrue()
        assertThat(fake.writeCount).isEqualTo(1)
        assertThat(backend.retrieve("https://romm.example.com|alice")).isEqualTo("tok")
    }
}

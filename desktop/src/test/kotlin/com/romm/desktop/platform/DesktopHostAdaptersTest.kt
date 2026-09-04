package com.romm.desktop.platform

import com.romm.desktop.controller.DefaultControllerEnvironmentPolicy
import com.romm.desktop.controller.LinuxControllerEnvironmentPolicy
import com.romm.desktop.platform.security.LinuxFileSecurityPolicy
import com.romm.desktop.platform.security.WindowsFileSecurityPolicy
import com.romm.desktop.storage.paths.JnaWindowsKnownFolderResolver
import com.romm.desktop.storage.paths.WindowsAppPaths
import com.romm.desktop.storage.paths.XdgAppPaths
import com.romm.desktop.storage.secret.FileSecretBackend
import com.romm.desktop.storage.secret.UnavailableSecretServiceFallback
import com.romm.desktop.storage.secret.dbus.SecretServiceDbusBackend
import com.romm.desktop.storage.secret.windows.WindowsCredentialBackend
import com.romm.desktop.ui.input.NoopVirtualKeyboardLauncher
import com.romm.desktop.ui.input.SteamVirtualKeyboardLauncher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Host-adapter composition (Phase 1, plans/WINDOWS_IMPL.md §3.1/§4): the bundle is ONE pure
 * function of ONE [PlatformDetectionResult]. These tests are host-neutral — they run on macOS
 * development hosts and in CI without loading any JNA native or touching the real profile:
 * constructing the Windows bundle is inert (every Win32 seam loads lazily on first use), so the
 * selection guarantees are asserted by type, never by side effect.
 */
@DisplayName("Host adapter bundle — pure composition from one detection result")
class DesktopHostAdaptersTest {

    // ------------------------------------------------------------- Linux production host

    @Test
    fun `linux x86_64 keeps the historical wiring unchanged`() {
        val adapters = desktopHostAdapters(DesktopPlatformDetector.detect("Linux", "amd64"))

        assertThat(adapters.appPaths).isInstanceOf(XdgAppPaths::class.java)
        assertThat(adapters.securityPolicy).isInstanceOf(LinuxFileSecurityPolicy::class.java)
        // Historical D-Bus primary + owner-only file fallback, never the Windows backend.
        assertThat(adapters.secretBackend).isInstanceOf(UnavailableSecretServiceFallback::class.java)
        assertThat(adapters.secretBackend).isNotInstanceOf(WindowsCredentialBackend::class.java)
        assertThat(adapters.layout).isSameAs(LinuxNativeArtifactLayout)
        assertThat(adapters.hostOs).isEqualTo(HostOs.LINUX)
        // Linux is the only host with the JInput Linux plugin policy and the Steam keyboard.
        assertThat(adapters.controllerEnvironmentPolicy)
            .isInstanceOf(LinuxControllerEnvironmentPolicy::class.java)
        assertThat(adapters.virtualKeyboardLauncher)
            .isInstanceOf(SteamVirtualKeyboardLauncher::class.java)
        assertThat(adapters.windowsKnownFolders).isNull()
    }

    // ------------------------------------------------------------- macOS development host

    @Test
    fun `macOS development host keeps XDG paths and the current credential fallback`() {
        val adapters = desktopHostAdapters(DesktopPlatformDetector.detect("Mac OS X", "aarch64"))

        assertThat(adapters.appPaths).isInstanceOf(XdgAppPaths::class.java)
        assertThat(adapters.securityPolicy).isInstanceOf(LinuxFileSecurityPolicy::class.java)
        // The current D-Bus + file fallback wiring — host-neutral development keeps working.
        assertThat(adapters.secretBackend).isInstanceOf(UnavailableSecretServiceFallback::class.java)
        assertThat(adapters.layout).isSameAs(LinuxNativeArtifactLayout)
        assertThat(adapters.hostOs).isEqualTo(HostOs.MACOS)
        // ...but with the non-Linux controller policy and the no-op keyboard.
        assertThat(adapters.controllerEnvironmentPolicy)
            .isInstanceOf(DefaultControllerEnvironmentPolicy::class.java)
        assertThat(adapters.virtualKeyboardLauncher).isSameAs(NoopVirtualKeyboardLauncher)
    }

    // ------------------------------------------------------------- Windows production host

    @Test
    fun `windows x86_64 selects the full Windows bundle without loading JNA`() {
        // Must be constructible on ANY host (this test runs on macOS): construction is pure and
        // lazy — no Win32 library is loaded until a path/credential operation actually runs.
        val adapters = desktopHostAdapters(DesktopPlatformDetector.detect("Windows 11", "amd64"))

        assertThat(adapters.appPaths).isInstanceOf(WindowsAppPaths::class.java)
        assertThat(adapters.securityPolicy).isInstanceOf(WindowsFileSecurityPolicy::class.java)
        // Credential Manager ONLY, via the factory.
        assertThat(adapters.secretBackend).isInstanceOf(WindowsCredentialBackend::class.java)
        assertThat(adapters.layout).isSameAs(WindowsNativeArtifactLayout)
        assertThat(adapters.hostOs).isEqualTo(HostOs.WINDOWS)
        assertThat(adapters.controllerEnvironmentPolicy)
            .isInstanceOf(DefaultControllerEnvironmentPolicy::class.java)
        assertThat(adapters.virtualKeyboardLauncher).isSameAs(NoopVirtualKeyboardLauncher)
    }

    @Test
    fun `windows never selects XDG, D-Bus, file credentials, Linux JInput, or the Steam launcher`() {
        val adapters = desktopHostAdapters(DesktopPlatformDetector.detect("Windows 10", "x86_64"))

        assertThat(adapters.appPaths).isNotInstanceOf(XdgAppPaths::class.java)
        // No plaintext fallback wrapper, no D-Bus backend, no file backend — ever.
        assertThat(adapters.secretBackend).isNotInstanceOf(UnavailableSecretServiceFallback::class.java)
        assertThat(adapters.secretBackend).isNotInstanceOf(SecretServiceDbusBackend::class.java)
        assertThat(adapters.secretBackend).isNotInstanceOf(FileSecretBackend::class.java)
        // No Linux JInput plugin policy and no Steam on-screen keyboard.
        assertThat(adapters.controllerEnvironmentPolicy)
            .isNotInstanceOf(LinuxControllerEnvironmentPolicy::class.java)
        assertThat(adapters.virtualKeyboardLauncher)
            .isNotInstanceOf(SteamVirtualKeyboardLauncher::class.java)
    }

    @Test
    fun `windows bundle shares ONE known-folder resolver between paths and security policy`() {
        val adapters = desktopHostAdapters(DesktopPlatformDetector.detect("Windows 11", "amd64"))

        assertThat(adapters.windowsKnownFolders)
            .isInstanceOf(JnaWindowsKnownFolderResolver::class.java)
        // The same instance backs WindowsAppPaths, so the app can never resolve paths outside
        // the containment roots the security policy was built from.
        val windowsPaths = adapters.appPaths as WindowsAppPaths
        assertThat(windowsPaths.knownFolders).isSameAs(adapters.windowsKnownFolders)
    }

    // ------------------------------------------------------------- unsupported hosts

    @Test
    fun `unsupported hosts fail before any adapter is created`() {
        for (result in listOf(
            DesktopPlatformDetector.detect("SunOS", "sparc"),
            DesktopPlatformDetector.detect("Linux", "aarch64"),
            DesktopPlatformDetector.detect("Windows 11", "aarch64"),
            DesktopPlatformDetector.detect("", ""),
        )) {
            assertThatThrownBy { desktopHostAdapters(result) }
                .isInstanceOf(UnsupportedHostException::class.java)
        }
    }

    // ------------------------------------------------------------- plan/bundle coherence

    @Test
    fun `startup plan and adapter bundle agree on the artifact layout for every proceeding host`() {
        for ((osName, osArch) in listOf("Linux" to "amd64", "Windows 11" to "amd64", "Mac OS X" to "aarch64")) {
            val result = DesktopPlatformDetector.detect(osName, osArch)
            val plan = desktopStartupPlan(result) as DesktopStartupPlan.Proceed
            val adapters = desktopHostAdapters(result)

            assertThat(adapters.layout).`as`("$osName/$osArch layout must match the startup plan")
                .isSameAs(plan.layout)
        }
    }
}

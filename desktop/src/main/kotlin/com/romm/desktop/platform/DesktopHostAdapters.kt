package com.romm.desktop.platform

import com.romm.androidtv.storage.AppPaths
import com.romm.desktop.controller.ControllerEnvironmentPolicy
import com.romm.desktop.platform.security.FileSecurityPolicies
import com.romm.desktop.platform.security.FileSecurityPolicy
import com.romm.desktop.platform.security.JnaWindowsAclApplier
import com.romm.desktop.platform.security.LinuxFileSecurityPolicy
import com.romm.desktop.storage.paths.JnaWindowsKnownFolderResolver
import com.romm.desktop.storage.paths.WindowsAppPaths
import com.romm.desktop.storage.paths.WindowsKnownFolderResolver
import com.romm.desktop.storage.paths.XdgAppPaths
import com.romm.desktop.storage.secret.SecretBackend
import com.romm.desktop.ui.input.VirtualKeyboardLauncher

/**
 * The single coherent host-adapter bundle selected from ONE [PlatformDetectionResult]
 * (Phase 1, plans/WINDOWS_IMPL.md §3.1/§4). Startup performs host detection exactly once and
 * derives every platform adapter from this result — feature code never sniffs `os.name` or
 * re-detects the host:
 *
 * - **Linux x86_64** → the historical wiring, unchanged: [XdgAppPaths], POSIX
 *   [LinuxFileSecurityPolicy], the freedesktop Secret Service backend with the owner-only file
 *   fallback (via [CredentialBackendFactory]), [LinuxNativeArtifactLayout], the Linux JInput
 *   controller policy, and the Steam virtual keyboard.
 * - **macOS (development-only)** → the same XDG/file-credential wiring as Linux so host-neutral
 *   development keeps working (`.slim/deepwork/windows-phase-0.md`), but with the non-Linux
 *   (default) controller policy and the no-op virtual keyboard — macOS never masquerades as a
 *   production ABI.
 * - **Windows x86_64** → one shared [JnaWindowsKnownFolderResolver] backs BOTH
 *   [WindowsAppPaths] and the containment roots of
 *   `FileSecurityPolicies.forWindows(resolver, JnaWindowsAclApplier())`; credentials come from
 *   the Windows Credential Manager ONLY through [CredentialBackendFactory] (never the D-Bus or
 *   plaintext file backends); the layout is [WindowsNativeArtifactLayout]; the controller policy
 *   is the non-Linux default; and the virtual keyboard is a no-op. JNA natives stay lazy: all
 *   Windows adapters load their Win32 libraries on first use, so constructing this bundle on any
 *   host is inert until a Windows path/credential operation actually runs.
 * - **Unsupported hosts** → [UnsupportedHostException] BEFORE any adapter is constructed.
 */
internal data class DesktopHostAdapters(
    val appPaths: AppPaths,
    val securityPolicy: FileSecurityPolicy,
    val secretBackend: SecretBackend,
    val layout: NativeArtifactLayout,
    val hostOs: HostOs,
    val controllerEnvironmentPolicy: ControllerEnvironmentPolicy,
    val virtualKeyboardLauncher: VirtualKeyboardLauncher,
    /**
     * The ONE shared Windows Known Folder resolver backing both [appPaths] and the containment
     * roots of [securityPolicy] (null on non-Windows hosts). Exposed so composition tests can
     * assert the paths and the policy cannot drift onto different profile roots.
     */
    val windowsKnownFolders: WindowsKnownFolderResolver? = null,
)

/**
 * Builds the [DesktopHostAdapters] bundle for [result]. Pure composition: no host sniffing, no
 * I/O, and (on Windows) no JNA native loading — every adapter defers platform work to first use.
 * Unsupported results throw [UnsupportedHostException] before any adapter is created.
 */
internal fun desktopHostAdapters(result: PlatformDetectionResult): DesktopHostAdapters = when (result) {
    is PlatformDetectionResult.Production -> when (result.platform) {
        LinuxX86_64Platform -> posixBundle(HostOs.LINUX, result)
        WindowsX86_64Platform -> windowsBundle(result)
    }

    // macOS development-only host: the current Linux-compatible wiring (XDG paths + file
    // credential fallback) with non-Linux controller / no-op keyboard policies.
    is PlatformDetectionResult.DevelopmentOnly -> posixBundle(HostOs.MACOS, result)

    // Fail before any adapter exists — startup never constructs a bundle for an unsupported host.
    is PlatformDetectionResult.Unsupported -> throw UnsupportedHostException(result.detail)
}

/** Linux production and macOS development hosts: the historical XDG + POSIX + D-Bus/file wiring. */
private fun posixBundle(hostOs: HostOs, result: PlatformDetectionResult): DesktopHostAdapters {
    val securityPolicy = LinuxFileSecurityPolicy()
    val appPaths = XdgAppPaths(securityPolicy = securityPolicy)
    return DesktopHostAdapters(
        appPaths = appPaths,
        securityPolicy = securityPolicy,
        secretBackend = CredentialBackendFactory.create(result, appPaths),
        layout = LinuxNativeArtifactLayout,
        hostOs = hostOs,
        controllerEnvironmentPolicy = ControllerEnvironmentPolicy.forHostOs(hostOs),
        virtualKeyboardLauncher = VirtualKeyboardLauncher.forHostOs(hostOs),
    )
}

/** Windows x86_64: Known Folder paths + NTFS ACL policy + Credential Manager, all from one resolver. */
private fun windowsBundle(result: PlatformDetectionResult): DesktopHostAdapters {
    // ONE shared resolver instance: the same object backs [WindowsAppPaths] and the security
    // policy's trusted roots, so the app can never write outside the roots its paths resolve
    // under (FileSecurityPolicies.forWindows contract).
    val knownFolders = JnaWindowsKnownFolderResolver()
    val securityPolicy = FileSecurityPolicies.forWindows(knownFolders, JnaWindowsAclApplier())
    val appPaths = WindowsAppPaths(knownFolders, securityPolicy)
    return DesktopHostAdapters(
        appPaths = appPaths,
        securityPolicy = securityPolicy,
        secretBackend = CredentialBackendFactory.create(result, appPaths),
        layout = WindowsNativeArtifactLayout,
        hostOs = HostOs.WINDOWS,
        controllerEnvironmentPolicy = ControllerEnvironmentPolicy.forHostOs(HostOs.WINDOWS),
        virtualKeyboardLauncher = VirtualKeyboardLauncher.forHostOs(HostOs.WINDOWS),
        windowsKnownFolders = knownFolders,
    )
}

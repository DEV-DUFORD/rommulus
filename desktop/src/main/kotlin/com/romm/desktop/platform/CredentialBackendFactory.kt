package com.romm.desktop.platform

import com.romm.androidtv.storage.AppPaths
import com.romm.desktop.storage.secret.FileSecretBackend
import com.romm.desktop.storage.secret.SecretBackend
import com.romm.desktop.storage.secret.UnavailableSecretServiceFallback
import com.romm.desktop.storage.secret.dbus.SecretServiceDbusBackend
import com.romm.desktop.storage.secret.windows.JnaWindowsCredentialApi
import com.romm.desktop.storage.secret.windows.WindowsCredentialApi
import com.romm.desktop.storage.secret.windows.WindowsCredentialBackend

/**
 * Selects the platform [SecretBackend] from the normalized host detection result
 * (plans/WINDOWS_IMPL.md §4.1/§4.3). This is the single place that decides which credential
 * storage a host may use, so feature code never constructs backends directly.
 *
 * Selection rules:
 *  - **Windows x86_64** → [WindowsCredentialBackend] over the real JNA Credential Manager seam.
 *    Windows receives Credential Manager ONLY: the D-Bus backend and the plaintext
 *    [FileSecretBackend] are never constructed on this host (no plaintext token fallback is
 *    acceptable on Windows — if Credential Manager is unavailable, login stays unauthenticated
 *    with an actionable error).
 *  - **Linux x86_64** → the historical wiring, unchanged: freedesktop Secret Service primary with
 *    the owner-only file fallback (hosts such as Steam Deck Gaming Mode have no Secret Service
 *    provider).
 *  - **Development-only hosts (macOS)** → the same Linux-compatible wiring the current startup
 *    uses, so host-neutral development and tests keep working (`.slim/deepwork/windows-phase-0.md`).
 *  - **Unsupported hosts** → [UnsupportedHostException]; startup fails fast before any wiring.
 */
object CredentialBackendFactory {

    /**
     * @param windowsApi Injectable [WindowsCredentialApi] seam for the Windows backend (tests and
     *   startup wiring pass a fake or a pre-configured instance). When null, the production
     *   [JnaWindowsCredentialApi] is constructed — but only on the Windows branch, so Linux/macOS
     *   hosts never instantiate the Win32 seam.
     */
    fun create(
        result: PlatformDetectionResult,
        appPaths: AppPaths,
        windowsApi: WindowsCredentialApi? = null,
    ): SecretBackend = when (result) {
        is PlatformDetectionResult.Production -> when (result.platform) {
            WindowsX86_64Platform -> windowsBackend(windowsApi)
            LinuxX86_64Platform -> linuxBackend(appPaths)
        }

        is PlatformDetectionResult.DevelopmentOnly -> linuxBackend(appPaths)

        is PlatformDetectionResult.Unsupported -> throw UnsupportedHostException(result.detail)
    }

    /** Windows: Credential Manager only — never the D-Bus or file fallbacks. */
    private fun windowsBackend(api: WindowsCredentialApi?): SecretBackend =
        WindowsCredentialBackend(api ?: JnaWindowsCredentialApi())

    /** Linux production and macOS development: the current D-Bus + owner-only-file wiring. */
    private fun linuxBackend(appPaths: AppPaths): SecretBackend = UnavailableSecretServiceFallback(
        primary = SecretServiceDbusBackend(),
        fallback = FileSecretBackend(
            appPaths.stateDir.resolve("credentials").resolve("client-tokens.properties"),
        ),
    )
}

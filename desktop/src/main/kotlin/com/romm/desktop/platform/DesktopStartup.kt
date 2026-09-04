package com.romm.desktop.platform

/**
 * Startup decision derived from [DesktopPlatformDetector] at process entry
 * (plans/WINDOWS_IMPL.md §3.1). This is the single place that decides, from the normalized host
 * detection result, whether the desktop may start and with which artifact layout — so feature
 * code never sniffs `os.name` itself.
 */
internal sealed interface DesktopStartupPlan {
    /**
     * Proceed with startup using [layout]. When [note] is non-null it is a development-host
     * notice that must be logged (the host is not a production build target).
     */
    data class Proceed(val layout: NativeArtifactLayout, val note: String? = null) : DesktopStartupPlan

    /** The host cannot run this build: print [message] and exit non-zero before any wiring. */
    data class FailFast(val message: String) : DesktopStartupPlan
}

/**
 * Maps a [PlatformDetectionResult] to a startup plan:
 *
 * - **Linux x86_64** → proceed with the Linux artifact layout — the historical desktop
 *   behavior, unchanged.
 * - **macOS (any arch)** → explicit supported development path
 *   (`.slim/deepwork/windows-phase-0.md`): proceed with the current Linux-compatible dev wiring
 *   (XDG paths + file credentials) for host-neutral builds and tests, with a logged notice that
 *   no production build identity is advertised.
 * - **Windows x86_64** → proceed with [WindowsNativeArtifactLayout]. The Phase 1 Windows
 *   adapters (Known Folder [AppPaths][com.romm.desktop.storage.paths.WindowsAppPaths], NTFS ACL
 *   policy, Credential Manager backend) are selected from the same detection result by
 *   [desktopHostAdapters], so startup never pairs the Windows layout with Linux XDG wiring.
 * - **Unsupported** → fail fast with the detector's diagnostic, before any adapter is created.
 */
internal fun desktopStartupPlan(result: PlatformDetectionResult): DesktopStartupPlan = when (result) {
    is PlatformDetectionResult.Production -> when (result.platform) {
        LinuxX86_64Platform -> DesktopStartupPlan.Proceed(LinuxNativeArtifactLayout)

        WindowsX86_64Platform -> DesktopStartupPlan.Proceed(WindowsNativeArtifactLayout)
    }

    is PlatformDetectionResult.DevelopmentOnly -> DesktopStartupPlan.Proceed(
        layout = LinuxNativeArtifactLayout,
        note = "${result.detail} Continuing with the Linux-compatible development wiring; no " +
            "production build identity is advertised or shipped from this host.",
    )

    is PlatformDetectionResult.Unsupported -> DesktopStartupPlan.FailFast(result.detail)
}

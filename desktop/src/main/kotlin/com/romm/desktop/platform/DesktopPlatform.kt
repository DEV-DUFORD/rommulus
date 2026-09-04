package com.romm.desktop.platform

import com.romm.androidtv.emulation.model.NativeBuildIdentities

/** Normalized host OS family used by [DesktopPlatformDetector]. */
enum class HostOs {
    LINUX,
    WINDOWS,
    MACOS,
    UNKNOWN,
}

/**
 * A production desktop platform: a first-class native build identity the app may advertise and
 * ship (plans/WINDOWS_IMPL.md §3.1). Development-only hosts (e.g. macOS) are deliberately NOT
 * [DesktopPlatform]s — they must never masquerade as a production ABI.
 */
sealed interface DesktopPlatform {
    /** The native build identity this platform produces (see [NativeBuildIdentities]). */
    val buildIdentity: String
}

/** Linux x86_64 desktop platform (`linux-x86_64`). */
object LinuxX86_64Platform : DesktopPlatform {
    override val buildIdentity: String = NativeBuildIdentities.LINUX_X86_64
}

/** Windows x86_64 desktop platform (`windows-x86_64`). */
object WindowsX86_64Platform : DesktopPlatform {
    override val buildIdentity: String = NativeBuildIdentities.WINDOWS_X86_64
}

/** Outcome of normalized host detection. */
sealed interface PlatformDetectionResult {
    /** The host maps to a production [DesktopPlatform]. */
    data class Production(val platform: DesktopPlatform) : PlatformDetectionResult

    /**
     * The host is supported for development (host-neutral builds, tests, and configuration) but
     * has no production build identity; it must never be advertised as one.
     */
    data class DevelopmentOnly(val detail: String) : PlatformDetectionResult

    /** The host cannot run a production RomMulus desktop build; [detail] explains why. */
    data class Unsupported(val detail: String) : PlatformDetectionResult
}

/** Thrown when startup requires a production platform but the host does not provide one. */
class UnsupportedHostException(message: String) : IllegalStateException(message)

/**
 * Normalized OS/architecture detection for the desktop shell (plans/WINDOWS_IMPL.md §3.1).
 *
 * Detection is pure and injectable: [detect] takes raw `os.name`/`os.arch` values, so tests never
 * read the real host. [detectHost] is the production seam that reads the JVM system properties
 * once at startup. The concrete platform is chosen from these normalized values only — feature
 * code must not sniff `os.name` itself.
 *
 * macOS is an explicit development-only host (`.slim/deepwork/windows-phase-0.md`): it supports
 * ordinary host-neutral development and tests, but [detect] never maps it to a production
 * identity, so a macOS dev run cannot mis-advertise `linux-x86_64` or `windows-x86_64`.
 */
object DesktopPlatformDetector {

    /** Detects the platform for raw (unnormalized) JVM `os.name`/`os.arch` values. */
    fun detect(osName: String, osArch: String): PlatformDetectionResult =
        when (normalizeOs(osName)) {
            HostOs.LINUX -> linuxOrUnsupported(osName, osArch)
            HostOs.WINDOWS -> windowsOrUnsupported(osName, osArch)
            HostOs.MACOS -> PlatformDetectionResult.DevelopmentOnly(
                "macOS ('$osName', arch '$osArch') is a development-only host: it supports " +
                    "host-neutral builds and tests but has no production RomMulus build identity; " +
                    "it must never be advertised as ${NativeBuildIdentities.LINUX_X86_64} or " +
                    "${NativeBuildIdentities.WINDOWS_X86_64}",
            )
            HostOs.UNKNOWN -> PlatformDetectionResult.Unsupported(
                "unrecognized host OS '$osName' (arch '$osArch'); supported production hosts are " +
                    "Linux x86_64 (${NativeBuildIdentities.LINUX_X86_64}) and Windows x86_64 " +
                    "(${NativeBuildIdentities.WINDOWS_X86_64})",
            )
        }

    /** Production seam: detects the real JVM host from system properties. */
    fun detectHost(): PlatformDetectionResult =
        detect(System.getProperty("os.name") ?: "", System.getProperty("os.arch") ?: "")

    /**
     * Production seam: the normalized host OS family of the real JVM. Feature code (e.g. the
     * desktop coordinator) selects platform strategies from this normalized result instead of
     * sniffing `os.name` itself; tests inject an explicit [HostOs].
     */
    fun detectHostOs(): HostOs =
        normalizeOs(System.getProperty("os.name") ?: "")

    /**
     * Startup requirement (plans/WINDOWS_IMPL.md §3.1): unsupported hosts must fail with a clear
     * diagnostic rather than silently selecting Linux. Development-only hosts (macOS) are rejected
     * here too — production startup never masquerades as a production ABI.
     */
    fun requireProduction(osName: String, osArch: String): DesktopPlatform =
        when (val result = detect(osName, osArch)) {
            is PlatformDetectionResult.Production -> result.platform
            is PlatformDetectionResult.DevelopmentOnly -> throw UnsupportedHostException(result.detail)
            is PlatformDetectionResult.Unsupported -> throw UnsupportedHostException(result.detail)
        }

    private fun linuxOrUnsupported(osName: String, osArch: String): PlatformDetectionResult =
        if (normalizeArch(osArch) == "x86_64") {
            PlatformDetectionResult.Production(LinuxX86_64Platform)
        } else {
            PlatformDetectionResult.Unsupported(
                "host '$osName' has architecture '$osArch'; only x86_64 is supported (production " +
                    "identity ${NativeBuildIdentities.LINUX_X86_64})",
            )
        }

    private fun windowsOrUnsupported(osName: String, osArch: String): PlatformDetectionResult =
        if (normalizeArch(osArch) == "x86_64") {
            PlatformDetectionResult.Production(WindowsX86_64Platform)
        } else {
            PlatformDetectionResult.Unsupported(
                "host '$osName' has architecture '$osArch'; only x86_64 is supported (production " +
                    "identity ${NativeBuildIdentities.WINDOWS_X86_64}); Windows on ARM through x64 " +
                    "emulation is an initial non-goal (plans/WINDOWS_IMPL.md §1)",
            )
        }

    /** Normalizes a raw `os.name` value into a [HostOs] family. */
    fun normalizeOs(osName: String): HostOs {
        val name = osName.trim().lowercase()
        return when {
            name.isEmpty() -> HostOs.UNKNOWN
            name.contains("win") -> HostOs.WINDOWS
            name.contains("linux") -> HostOs.LINUX
            name.contains("mac") || name.contains("darwin") || name.contains("os x") -> HostOs.MACOS
            else -> HostOs.UNKNOWN
        }
    }

    /** Normalizes common `os.arch` spellings (`amd64`, `x86_64`, ...) to canonical values. */
    fun normalizeArch(osArch: String): String = when (osArch.trim().lowercase()) {
        "amd64", "x86_64", "x86-64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> osArch.trim().lowercase()
    }
}

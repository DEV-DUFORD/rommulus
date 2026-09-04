package com.romm.desktop.platform

import com.romm.androidtv.emulation.model.NativeBuildIdentities
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("DesktopPlatformDetector — injectable normalized host detection")
class DesktopPlatformDetectionTest {

    // ------------------------------------------------------------- production hosts

    @Test
    fun `linux x86_64 detects the Linux production platform`() {
        val result = DesktopPlatformDetector.detect("Linux", "amd64")

        assertThat(result).isEqualTo(PlatformDetectionResult.Production(LinuxX86_64Platform))
        assertThat((result as PlatformDetectionResult.Production).platform.buildIdentity)
            .isEqualTo(NativeBuildIdentities.LINUX_X86_64)
    }

    @Test
    fun `windows x86_64 detects the Windows production platform`() {
        val result = DesktopPlatformDetector.detect("Windows 11", "amd64")

        assertThat(result).isEqualTo(PlatformDetectionResult.Production(WindowsX86_64Platform))
        assertThat((result as PlatformDetectionResult.Production).platform.buildIdentity)
            .isEqualTo(NativeBuildIdentities.WINDOWS_X86_64)
    }

    @Test
    fun `os name and arch spellings normalize case-insensitively`() {
        assertThat(DesktopPlatformDetector.detect("linux", "x86_64"))
            .isEqualTo(PlatformDetectionResult.Production(LinuxX86_64Platform))
        assertThat(DesktopPlatformDetector.detect("  Linux  ", "x86-64"))
            .isEqualTo(PlatformDetectionResult.Production(LinuxX86_64Platform))
        assertThat(DesktopPlatformDetector.detect("WINDOWS 10", "AMD64"))
            .isEqualTo(PlatformDetectionResult.Production(WindowsX86_64Platform))
    }

    // ------------------------------------------------------------- macOS development host

    @Test
    fun `macOS is an explicit development-only host, never a production identity`() {
        val result = DesktopPlatformDetector.detect("Mac OS X", "aarch64")

        assertThat(result).isInstanceOf(PlatformDetectionResult.DevelopmentOnly::class.java)
        assertThat((result as PlatformDetectionResult.DevelopmentOnly).detail)
            .contains("development-only")
            // The diagnostic must name the identities it refuses to masquerade as.
            .contains(NativeBuildIdentities.LINUX_X86_64)
            .contains(NativeBuildIdentities.WINDOWS_X86_64)
    }

    @Test
    fun `macOS x86_64 is still development-only`() {
        val result = DesktopPlatformDetector.detect("Mac OS X", "x86_64")

        assertThat(result).isInstanceOf(PlatformDetectionResult.DevelopmentOnly::class.java)
    }

    @Test
    fun `requireProduction rejects macOS with a clear diagnostic instead of mis-advertising Linux`() {
        val error = assertThrows<UnsupportedHostException> {
            DesktopPlatformDetector.requireProduction("Mac OS X", "aarch64")
        }

        assertThat(error.message).contains("development-only").contains(NativeBuildIdentities.LINUX_X86_64)
    }

    // ------------------------------------------------------------- unsupported hosts

    @Test
    fun `linux aarch64 is unsupported with a clear diagnostic`() {
        val result = DesktopPlatformDetector.detect("Linux", "aarch64")

        assertThat(result).isInstanceOf(PlatformDetectionResult.Unsupported::class.java)
        assertThat((result as PlatformDetectionResult.Unsupported).detail)
            .contains("aarch64")
            .contains(NativeBuildIdentities.LINUX_X86_64)
    }

    @Test
    fun `windows on arm is unsupported because x64 emulation is an initial non-goal`() {
        val result = DesktopPlatformDetector.detect("Windows 11", "aarch64")

        assertThat(result).isInstanceOf(PlatformDetectionResult.Unsupported::class.java)
        assertThat((result as PlatformDetectionResult.Unsupported).detail)
            .contains(NativeBuildIdentities.WINDOWS_X86_64)
    }

    @Test
    fun `unrecognized hosts are unsupported and name the offending OS`() {
        val result = DesktopPlatformDetector.detect("SunOS", "sparc")

        assertThat(result).isInstanceOf(PlatformDetectionResult.Unsupported::class.java)
        assertThat((result as PlatformDetectionResult.Unsupported).detail).contains("SunOS")
    }

    @Test
    fun `requireProduction throws for unsupported hosts rather than silently selecting Linux`() {
        val error = assertThrows<UnsupportedHostException> {
            DesktopPlatformDetector.requireProduction("Linux", "aarch64")
        }

        assertThat(error.message).contains("aarch64")
    }

    @Test
    fun `requireProduction returns the platform for supported hosts`() {
        assertThat(DesktopPlatformDetector.requireProduction("Linux", "amd64"))
            .isSameAs(LinuxX86_64Platform)
        assertThat(DesktopPlatformDetector.requireProduction("Windows 10", "x86_64"))
            .isSameAs(WindowsX86_64Platform)
    }

    // ------------------------------------------------------------- host seam

    @Test
    fun `detectHost returns a result for the real JVM host without throwing`() {
        // Host-neutral by construction: whatever the CI/dev machine is, detection must yield a
        // well-formed result (production, development-only, or unsupported) — never an exception.
        assertThat(DesktopPlatformDetector.detectHost()).isNotNull
    }
}

package com.romm.desktop.platform

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Startup/platform mapping (plans/WINDOWS_IMPL.md §3.1): the decision made at process entry from
 * [DesktopPlatformDetector] results — which hosts proceed, with which artifact layout, and which
 * fail fast before any Linux wiring is constructed.
 */
@DisplayName("Desktop startup plan — host detection to startup mapping")
class DesktopStartupPlanTest {

    // ------------------------------------------------------------- Linux production host

    @Test
    fun `linux x86_64 proceeds with the Linux artifact layout and no notice`() {
        val plan = desktopStartupPlan(DesktopPlatformDetector.detect("Linux", "amd64"))

        assertThat(plan).isInstanceOf(DesktopStartupPlan.Proceed::class.java)
        val proceed = plan as DesktopStartupPlan.Proceed
        assertThat(proceed.layout).isSameAs(LinuxNativeArtifactLayout)
        assertThat(proceed.note).isNull()
    }

    // ------------------------------------------------------------- macOS development host

    @Test
    fun `macOS Apple Silicon is an explicit supported development path on the Linux-compatible wiring`() {
        val plan = desktopStartupPlan(DesktopPlatformDetector.detect("Mac OS X", "aarch64"))

        assertThat(plan).isInstanceOf(DesktopStartupPlan.Proceed::class.java)
        val proceed = plan as DesktopStartupPlan.Proceed
        // The dev path reuses the current Linux-compatible wiring (XDG paths + file credentials),
        // never a Windows layout, and logs that it is development-only.
        assertThat(proceed.layout).isSameAs(LinuxNativeArtifactLayout)
        assertThat(proceed.note)
            .isNotBlank()
            .contains("development-only")
    }

    @Test
    fun `macOS x86_64 is also a development path, not a production identity`() {
        val plan = desktopStartupPlan(DesktopPlatformDetector.detect("Mac OS X", "x86_64"))

        assertThat(plan).isInstanceOf(DesktopStartupPlan.Proceed::class.java)
        val proceed = plan as DesktopStartupPlan.Proceed
        assertThat(proceed.layout).isSameAs(LinuxNativeArtifactLayout)
        assertThat(proceed.note).isNotBlank()
    }

    // ------------------------------------------------------------- Windows production host (Phase 1)

    @Test
    fun `windows x86_64 proceeds with the Windows artifact layout and no notice`() {
        val plan = desktopStartupPlan(DesktopPlatformDetector.detect("Windows 11", "amd64"))

        assertThat(plan).isInstanceOf(DesktopStartupPlan.Proceed::class.java)
        val proceed = plan as DesktopStartupPlan.Proceed
        // The Windows layout — never the Linux one (that pairing would misuse XDG wiring on
        // Windows); the full Windows adapter bundle is selected from the same result by
        // desktopHostAdapters.
        assertThat(proceed.layout).isSameAs(WindowsNativeArtifactLayout)
        assertThat(proceed.note).isNull()
    }

    @Test
    fun `windows on arm fails fast (unsupported arch)`() {
        val plan = desktopStartupPlan(DesktopPlatformDetector.detect("Windows 11", "aarch64"))

        assertThat(plan).isInstanceOf(DesktopStartupPlan.FailFast::class.java)
        assertThat((plan as DesktopStartupPlan.FailFast).message).contains("aarch64")
    }

    // ------------------------------------------------------------- unsupported hosts

    @Test
    fun `unsupported linux arch fails fast with the detector diagnostic`() {
        val plan = desktopStartupPlan(DesktopPlatformDetector.detect("Linux", "aarch64"))

        assertThat(plan).isInstanceOf(DesktopStartupPlan.FailFast::class.java)
        assertThat((plan as DesktopStartupPlan.FailFast).message).contains("aarch64")
    }

    @Test
    fun `unrecognized hosts fail fast naming the offending OS`() {
        val plan = desktopStartupPlan(DesktopPlatformDetector.detect("SunOS", "sparc"))

        assertThat(plan).isInstanceOf(DesktopStartupPlan.FailFast::class.java)
        assertThat((plan as DesktopStartupPlan.FailFast).message).contains("SunOS")
    }
}

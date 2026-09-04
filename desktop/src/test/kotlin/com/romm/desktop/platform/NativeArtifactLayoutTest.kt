package com.romm.desktop.platform

import com.romm.androidtv.emulation.model.NativeBuildIdentities
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@DisplayName("NativeArtifactLayout — Linux/Windows artifact naming and installed-core scan")
class NativeArtifactLayoutTest {

    // ------------------------------------------------------------- Linux layout (regression)

    @Test
    fun `linux layout keeps the historical rommulus_player executable name`() {
        assertThat(LinuxNativeArtifactLayout.playerExecutableName).isEqualTo("rommulus_player")
        assertThat(LinuxNativeArtifactLayout.buildIdentity).isEqualTo(NativeBuildIdentities.LINUX_X86_64)
    }

    @Test
    fun `linux core names accept both shared library spellings in preference order`() {
        assertThat(LinuxNativeArtifactLayout.coreLibraryFileNames("gambatte"))
            .containsExactly("libgambatte.so", "libgambatte_core.so")
    }

    @Test
    fun `linux scan extracts sorted core ids from core shared libraries`(@TempDir dir: Path) {
        Files.write(dir.resolve("libgambatte_core.so"), byteArrayOf(0))
        Files.write(dir.resolve("libfoo.so"), byteArrayOf(0))
        Files.write(dir.resolve("notes.txt"), byteArrayOf(0))

        assertThat(LinuxNativeArtifactLayout.scanInstalledCoreIds(dir)).containsExactly("foo", "gambatte")
    }

    @Test
    fun `linux scan returns an empty list when the directory does not exist`() {
        assertThat(LinuxNativeArtifactLayout.scanInstalledCoreIds(Path.of("/nonexistent", "rommulus", "cores")))
            .isEmpty()
    }

    @Test
    fun `linux resolveCoreLibraryPath prefers the first existing candidate`(@TempDir dir: Path) {
        Files.write(dir.resolve("libgambatte_core.so"), byteArrayOf(0))

        assertThat(LinuxNativeArtifactLayout.resolveCoreLibraryPath(dir, "gambatte"))
            .isEqualTo(dir.resolve("libgambatte_core.so"))
    }

    @Test
    fun `linux resolveCoreLibraryPath falls back to the canonical shared library name when nothing is installed`(@TempDir dir: Path) {
        assertThat(LinuxNativeArtifactLayout.resolveCoreLibraryPath(dir, "gambatte"))
            .isEqualTo(dir.resolve("libgambatte.so"))
    }

    @Test
    fun `linux deriveAllowedCores emits approved cores with manifest revisions in sorted order`() {
        // Byte-identical to the pre-extraction behavior (PlayerProcessLauncherTest).
        assertThat(LinuxNativeArtifactLayout.deriveAllowedCores(listOf("gambatte", "test_core")))
            .isEqualTo("gambatte=96174369b3c30d9fc57c926fa3379c273dc6a9a5;test_core=1")
    }

    // ------------------------------------------------------------- Windows layout (Phase 0 naming)

    @Test
    fun `windows layout names the player executable and the canonical core dll`() {
        assertThat(WindowsNativeArtifactLayout.playerExecutableName).isEqualTo("rommulus-player.exe")
        assertThat(WindowsNativeArtifactLayout.buildIdentity).isEqualTo(NativeBuildIdentities.WINDOWS_X86_64)
        assertThat(WindowsNativeArtifactLayout.coreLibraryFileNames("gambatte"))
            .containsExactly("gambatte_core.dll", "gambatte.dll")
    }

    @Test
    fun `windows scan extracts sorted core ids from core dlls and ignores foreign files`(@TempDir dir: Path) {
        Files.write(dir.resolve("gambatte_core.dll"), byteArrayOf(0))
        Files.write(dir.resolve("foo.dll"), byteArrayOf(0))
        // Foreign artifacts must not be mistaken for cores.
        Files.write(dir.resolve("libgambatte_core.so"), byteArrayOf(0))
        Files.write(dir.resolve("notes.txt"), byteArrayOf(0))

        assertThat(WindowsNativeArtifactLayout.scanInstalledCoreIds(dir)).containsExactly("foo", "gambatte")
    }

    @Test
    fun `windows scan returns an empty list when the directory does not exist`() {
        assertThat(WindowsNativeArtifactLayout.scanInstalledCoreIds(Path.of("/nonexistent", "rommulus", "cores")))
            .isEmpty()
    }

    @Test
    fun `windows resolveCoreLibraryPath prefers the canonical name when both candidates exist`(@TempDir dir: Path) {
        Files.write(dir.resolve("gambatte_core.dll"), byteArrayOf(0))
        Files.write(dir.resolve("gambatte.dll"), byteArrayOf(0))

        assertThat(WindowsNativeArtifactLayout.resolveCoreLibraryPath(dir, "gambatte"))
            .isEqualTo(dir.resolve("gambatte_core.dll"))
    }

    @Test
    fun `windows resolveCoreLibraryPath accepts the compatibility alias when only it is installed`(@TempDir dir: Path) {
        Files.write(dir.resolve("gambatte.dll"), byteArrayOf(0))

        assertThat(WindowsNativeArtifactLayout.resolveCoreLibraryPath(dir, "gambatte"))
            .isEqualTo(dir.resolve("gambatte.dll"))
    }

    @Test
    fun `windows resolveCoreLibraryPath falls back to the canonical core dll name when nothing is installed`(@TempDir dir: Path) {
        assertThat(WindowsNativeArtifactLayout.resolveCoreLibraryPath(dir, "gambatte"))
            .isEqualTo(dir.resolve("gambatte_core.dll"))
    }

    @Test
    fun `windows scan of the synthetic test core dll recovers the manifest id through the lossy strip`(@TempDir dir: Path) {
        // Mirrors the Linux libtest_core.so → "test" behavior; derivation resolves the ambiguity.
        Files.write(dir.resolve("test_core.dll"), byteArrayOf(0))

        assertThat(WindowsNativeArtifactLayout.scanInstalledCoreIds(dir)).containsExactly("test")
    }

    @Test
    fun `no production core is launchable on windows yet (phase 0 guardrail)`(@TempDir dir: Path) {
        // Until a core passes its Windows gate (plans/WINDOWS_IMPL.md §6.4), nothing is approved
        // for windows-x86_64, so the derived allowlist must stay empty even with cores installed.
        Files.write(dir.resolve("gambatte_core.dll"), byteArrayOf(0))
        Files.write(dir.resolve("test_core.dll"), byteArrayOf(0))
        Files.write(dir.resolve("dolphin_core.dll"), byteArrayOf(0))
        Files.write(dir.resolve("lrps2_core.dll"), byteArrayOf(0))

        val installed = WindowsNativeArtifactLayout.scanInstalledCoreIds(dir)
        assertThat(installed).containsExactly("dolphin", "gambatte", "lrps2", "test")
        assertThat(WindowsNativeArtifactLayout.deriveAllowedCores(installed)).isEmpty()
    }
}

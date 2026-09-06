package com.romm.desktop.platform

import com.romm.androidtv.emulation.model.CoreManifest
import com.romm.androidtv.emulation.model.NativeBuildIdentities
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class WindowsNativeBundleTest {
    @TempDir
    lateinit var root: Path

    private fun bundle(): WindowsNativeBundle {
        val paths = listOf(
            "native/rommulus-player.exe", "native/SDL3.dll", "native/libEGL.dll",
            "native/libGLESv2.dll", "core-manifest.json", "app/desktop.jar",
            "runtime/bin/server/jvm.dll",
        ) + CoreManifest.approvedEntries()
            .filter { NativeBuildIdentities.WINDOWS_X86_64 in it.supportedAbis }
            .map { "native/cores/${it.coreId}_core.dll" }
        val entries = paths.map { relative ->
            val file = root.resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, relative)
            val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
                .joinToString("") { "%02x".format(it) }
            "$hash  $relative"
        }
        Files.write(root.resolve("PACKAGE.sha256"), entries)
        return WindowsNativeBundle(root.toRealPath())
    }

    @Test
    fun `package verifies and keeps native binaries separate from user data`() {
        val bundle = bundle()
        bundle.verify()
        assertThat(bundle.coresDirectory).isEqualTo(root.toRealPath().resolve("native/cores"))
    }

    @Test
    fun `modified or missing native files fail rather than falling back to PATH`() {
        val bundle = bundle()
        Files.writeString(bundle.playerExecutable, "changed")
        assertThatThrownBy { bundle.verify() }.hasMessageContaining("checksum mismatch")
        Files.delete(bundle.playerExecutable)
        assertThatThrownBy { bundle.verify() }.hasMessageContaining("missing")
    }

    @Test
    fun `manifest must cover every required binary`() {
        val bundle = bundle()
        val manifest = root.resolve("PACKAGE.sha256")
        Files.write(manifest, Files.readAllLines(manifest).filterNot { "libEGL.dll" in it })
        assertThatThrownBy { bundle.verify() }.hasMessageContaining("missing")
    }

    @Test
    fun `manifest cannot escape the package`() {
        val bundle = bundle()
        Files.writeString(root.resolve("PACKAGE.sha256"), "${"0".repeat(64)}  ../outside.dll\n")
        assertThatThrownBy { bundle.verify() }.hasMessageContaining("Invalid package path")
    }

    @Test
    fun `only packaged Windows startup selects the bundle`() {
        val launcher = Files.writeString(root.resolve("RomMulus.exe"), "launcher")
        assertThat(WindowsNativeBundle.fromLauncher(WindowsNativeArtifactLayout, launcher.toString())?.root)
            .isEqualTo(root.toRealPath())
        assertThat(WindowsNativeBundle.fromLauncher(LinuxNativeArtifactLayout, launcher.toString())).isNull()
        assertThat(WindowsNativeBundle.fromLauncher(WindowsNativeArtifactLayout, null)).isNull()
    }
}

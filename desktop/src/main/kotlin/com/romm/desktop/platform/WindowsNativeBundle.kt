package com.romm.desktop.platform

import com.romm.androidtv.emulation.model.CoreManifest
import com.romm.androidtv.emulation.model.NativeBuildIdentities
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/** Immutable native binaries beside the jpackage launcher; saves remain in AppPaths. */
data class WindowsNativeBundle(val root: Path) {
    val playerExecutable: Path = root.resolve("native/rommulus-player.exe")
    val coresDirectory: Path = root.resolve("native/cores")

    fun verify() {
        val manifest = root.resolve("PACKAGE.sha256")
        require(Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            "Incomplete Windows installation: PACKAGE.sha256 is missing. Extract the complete package."
        }
        val entries = linkedMapOf<String, String>()
        Files.readAllLines(manifest).forEach { line ->
            val match = Regex("^([0-9a-fA-F]{64})  (.+)$").matchEntire(line)
                ?: error("Invalid package checksum entry: $line")
            val relative = match.groupValues[2]
            val file = root.resolve(relative).normalize()
            require(file.startsWith(root) && !Path.of(relative).isAbsolute && file != root) {
                "Invalid package path: $relative"
            }
            require(entries.put(relative, match.groupValues[1].lowercase()) == null) {
                "Duplicate package checksum entry: $relative"
            }
            require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && file.toRealPath().startsWith(root)) {
                "Incomplete Windows installation: $relative is missing or outside the package."
            }
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual == entries[relative]) {
                "Windows package checksum mismatch: $relative. Extract a fresh copy."
            }
        }
        val required = listOf(
            "native/rommulus-player.exe", "native/SDL3.dll", "native/libEGL.dll",
            "native/libGLESv2.dll", "core-manifest.json", "app/desktop.jar",
            "runtime/bin/server/jvm.dll",
        ) + CoreManifest.approvedEntries()
            .filter { NativeBuildIdentities.WINDOWS_X86_64 in it.supportedAbis }
            .map { "native/cores/${it.coreId}_core.dll" }
        require(entries.keys.containsAll(required)) {
            "Incomplete Windows package manifest: missing ${required.filterNot(entries::containsKey)}"
        }
    }

    companion object {
        fun fromLauncher(
            layout: NativeArtifactLayout,
            launcherPath: String? = System.getProperty("jpackage.app-path"),
        ): WindowsNativeBundle? {
            if (layout.buildIdentity != NativeBuildIdentities.WINDOWS_X86_64 || launcherPath == null) {
                return null
            }
            val launcher = Path.of(launcherPath)
            require(launcher.isAbsolute) { "jpackage launcher path must be absolute" }
            return WindowsNativeBundle(launcher.toRealPath().parent)
        }
    }
}

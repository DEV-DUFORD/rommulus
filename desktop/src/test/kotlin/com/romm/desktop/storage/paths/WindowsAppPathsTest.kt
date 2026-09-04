package com.romm.desktop.storage.paths

import com.romm.desktop.platform.security.FileSensitivity
import com.romm.desktop.platform.security.PathPermissionProfile
import com.romm.desktop.platform.security.RecordingFileSecurityPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WindowsAppPathsTest {

    @TempDir
    lateinit var tempDir: Path

    /** Fake known-folder seam backed by temp dirs (the JNA resolver is tested on windows-2022). */
    private class FakeKnownFolders(private val appData: Path, private val localAppData: Path) :
        WindowsKnownFolderResolver {
        override fun roamingAppData(): Path = appData
        override fun localAppData(): Path = localAppData
    }

    private fun paths(policy: RecordingFileSecurityPolicy = RecordingFileSecurityPolicy()): Pair<WindowsAppPaths, RecordingFileSecurityPolicy> {
        val folders = FakeKnownFolders(tempDir.resolve("appdata"), tempDir.resolve("localappdata"))
        return WindowsAppPaths(folders, policy) to policy
    }

    @Test
    fun `layout resolves under the plan known-folder roots`() {
        val (windows, _) = paths()

        assertThat(windows.configDir).isEqualTo(tempDir.resolve("appdata").resolve("RomMulus"))
        assertThat(windows.dataDir).isEqualTo(tempDir.resolve("localappdata").resolve("RomMulus").resolve("data"))
        assertThat(windows.stateDir).isEqualTo(tempDir.resolve("localappdata").resolve("RomMulus").resolve("state"))
        assertThat(windows.cacheDir).isEqualTo(tempDir.resolve("localappdata").resolve("RomMulus").resolve("cache"))
    }

    @Test
    fun `directories are created on first access`() {
        val (windows, _) = paths()

        val configDir = windows.configDir
        val dataDir = windows.dataDir
        val stateDir = windows.stateDir
        val cacheDir = windows.cacheDir

        assertThat(Files.isDirectory(configDir)).isTrue()
        assertThat(Files.isDirectory(dataDir)).isTrue()
        assertThat(Files.isDirectory(stateDir)).isTrue()
        assertThat(Files.isDirectory(cacheDir)).isTrue()
    }

    @Test
    fun `sensitive roots are hardened and normal roots are not`() {
        val (windows, policy) = paths()
        windows.configDir
        windows.dataDir
        windows.stateDir
        windows.cacheDir

        // data + state carry sensitive app data; config + cache do not.
        assertThat(policy.sensitiveHardeningCount).isEqualTo(2)
        val byName = policy.calls.associateBy { it.path.fileName.toString() }
        assertThat(byName["RomMulus"]!!.profile).isEqualTo(PathPermissionProfile.CONFIG_DIRECTORY)
        assertThat(byName["RomMulus"]!!.sensitivity).isEqualTo(FileSensitivity.NORMAL)
        assertThat(byName["data"]!!.sensitivity).isEqualTo(FileSensitivity.SENSITIVE)
        assertThat(byName["state"]!!.sensitivity).isEqualTo(FileSensitivity.SENSITIVE)
        assertThat(byName["cache"]!!.sensitivity).isEqualTo(FileSensitivity.NORMAL)
    }

    @Test
    fun `directory creation errors are surfaced, not swallowed`() {
        // Occupy the known-folder root with a regular file: createDirectories must fail and the
        // failure must propagate — no partially initialized profile.
        Files.writeString(tempDir.resolve("appdata"), "not a directory")
        val (windows, _) = paths()

        assertThatThrownBy { windows.configDir }
            .isInstanceOf(java.io.IOException::class.java)
    }

    @Test
    fun `environment resolver reads the known folder variables`() {
        val appData = tempDir.resolve("ad").toString()
        val localAppData = tempDir.resolve("lad").toString()
        val resolver = EnvironmentWindowsKnownFolderResolver(
            mapOf("APPDATA" to appData, "LOCALAPPDATA" to localAppData),
        )

        assertThat(resolver.roamingAppData()).isEqualTo(Path.of(appData))
        assertThat(resolver.localAppData()).isEqualTo(Path.of(localAppData))
    }

    @Test
    fun `environment resolver fails explicitly when a variable is missing or blank`() {
        assertThatThrownBy { EnvironmentWindowsKnownFolderResolver(emptyMap()).roamingAppData() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("APPDATA")
        assertThatThrownBy { EnvironmentWindowsKnownFolderResolver(mapOf("LOCALAPPDATA" to "")).localAppData() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("LOCALAPPDATA")
    }
}

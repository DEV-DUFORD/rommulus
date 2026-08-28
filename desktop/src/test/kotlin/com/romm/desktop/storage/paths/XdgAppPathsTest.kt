package com.romm.desktop.storage.paths

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

class XdgAppPathsTest {

    @TempDir
    lateinit var tempDir: Path

    private fun homeDir(): Path = tempDir.resolve("home")

    private fun setupHome(): Path {
        val config = homeDir().resolve(".config")
        val localShare = homeDir().resolve(".local").resolve("share")
        val localState = homeDir().resolve(".local").resolve("state")
        val cache = homeDir().resolve(".cache")
        Files.createDirectories(config)
        Files.createDirectories(localShare)
        Files.createDirectories(localState)
        Files.createDirectories(cache)
        return homeDir()
    }

    @Test
    fun `env override maps each XDG var to correct subdir`() {
        val xdgEnv = mapOf(
            "XDG_CONFIG_HOME" to tempDir.resolve("my_config").toString(),
            "XDG_DATA_HOME" to tempDir.resolve("my_data").toString(),
            "XDG_STATE_HOME" to tempDir.resolve("my_state").toString(),
            "XDG_CACHE_HOME" to tempDir.resolve("my_cache").toString()
        )
        val paths = XdgAppPaths(xdgEnv, homeDir())

        assertThat(paths.configDir).isEqualTo(tempDir.resolve("my_config/rommulus"))
        assertThat(paths.dataDir).isEqualTo(tempDir.resolve("my_data/rommulus"))
        assertThat(paths.stateDir).isEqualTo(tempDir.resolve("my_state/rommulus"))
        assertThat(paths.cacheDir).isEqualTo(tempDir.resolve("my_cache/rommulus"))
    }

    @Test
    fun `fallback when env vars are absent`() {
        val home = setupHome()
        val paths = XdgAppPaths(emptyMap(), home)

        assertThat(paths.configDir).isEqualTo(home.resolve(".config/rommulus"))
        assertThat(paths.dataDir).isEqualTo(home.resolve(".local/share/rommulus"))
        assertThat(paths.stateDir).isEqualTo(home.resolve(".local/state/rommulus"))
        assertThat(paths.cacheDir).isEqualTo(home.resolve(".cache/rommulus"))
    }

    @Test
    fun `null XDG env value behaves like unset`() {
        val home = setupHome()
        @Suppress("UNCHECKED_CAST")
        val xdgEnv = mapOf<String, String?>(
            "XDG_CONFIG_HOME" to null,
            "XDG_DATA_HOME" to null,
            "XDG_STATE_HOME" to null,
            "XDG_CACHE_HOME" to null
        )
        val paths = XdgAppPaths(xdgEnv, home)

        assertThat(paths.configDir).isEqualTo(home.resolve(".config/rommulus"))
        assertThat(paths.dataDir).isEqualTo(home.resolve(".local/share/rommulus"))
        assertThat(paths.stateDir).isEqualTo(home.resolve(".local/state/rommulus"))
        assertThat(paths.cacheDir).isEqualTo(home.resolve(".cache/rommulus"))
    }

    @Test
    fun `blank XDG env value behaves like unset`() {
        val home = setupHome()
        val xdgEnv = mapOf(
            "XDG_CONFIG_HOME" to "",
            "XDG_DATA_HOME" to "   ",
            "XDG_STATE_HOME" to "",
            "XDG_CACHE_HOME" to "   "
        )
        val paths = XdgAppPaths(xdgEnv, home)

        assertThat(paths.configDir).isEqualTo(home.resolve(".config/rommulus"))
        assertThat(paths.dataDir).isEqualTo(home.resolve(".local/share/rommulus"))
        assertThat(paths.stateDir).isEqualTo(home.resolve(".local/state/rommulus"))
        assertThat(paths.cacheDir).isEqualTo(home.resolve(".cache/rommulus"))
    }

    @Test
    fun `directories are created on first access`() {
        val xdgEnv = mapOf(
            "XDG_CONFIG_HOME" to tempDir.resolve("cfg").toString(),
            "XDG_DATA_HOME" to tempDir.resolve("dat").toString(),
            "XDG_STATE_HOME" to tempDir.resolve("stt").toString(),
            "XDG_CACHE_HOME" to tempDir.resolve("cch").toString()
        )
        val paths = XdgAppPaths(xdgEnv, homeDir())

        // Access all dirs to trigger creation
        val configDir = paths.configDir
        val dataDir = paths.dataDir
        val stateDir = paths.stateDir
        val cacheDir = paths.cacheDir

        assertThat(Files.exists(configDir)).isTrue()
        assertThat(Files.exists(dataDir)).isTrue()
        assertThat(Files.exists(stateDir)).isTrue()
        assertThat(Files.exists(cacheDir)).isTrue()

        // Verify they are directories
        assertThat(Files.isDirectory(configDir)).isTrue()
        assertThat(Files.isDirectory(dataDir)).isTrue()
        assertThat(Files.isDirectory(stateDir)).isTrue()
        assertThat(Files.isDirectory(cacheDir)).isTrue()
    }

    @Test
    fun `directories have expected POSIX permissions`() {
        // Only run on POSIX filesystems
        val supported = try {
            val testDir = Files.createTempDirectory("perm_test")
            Files.setPosixFilePermissions(testDir, setOf(PosixFilePermission.OWNER_READ))
            Files.delete(testDir)
            true
        } catch (_: UnsupportedOperationException) {
            false
        }
        if (!supported) return

        val xdgEnv = mapOf(
            "XDG_CONFIG_HOME" to tempDir.resolve("cfg").toString(),
            "XDG_DATA_HOME" to tempDir.resolve("dat").toString(),
            "XDG_STATE_HOME" to tempDir.resolve("stt").toString(),
            "XDG_CACHE_HOME" to tempDir.resolve("cch").toString()
        )
        val paths = XdgAppPaths(xdgEnv, homeDir())

        val configPerms = PosixFilePermissions.toString(Files.getPosixFilePermissions(paths.configDir))
        val dataPerms = PosixFilePermissions.toString(Files.getPosixFilePermissions(paths.dataDir))
        val statePerms = PosixFilePermissions.toString(Files.getPosixFilePermissions(paths.stateDir))
        val cachePerms = PosixFilePermissions.toString(Files.getPosixFilePermissions(paths.cacheDir))

        // Config: 0755 → rwxr-xr-x
        assertThat(configPerms).isEqualTo("rwxr-xr-x")
        // Data/State/Cache: 0700 → rwx------
        assertThat(dataPerms).isEqualTo("rwx------")
        assertThat(statePerms).isEqualTo("rwx------")
        assertThat(cachePerms).isEqualTo("rwx------")
    }

    @Test
    fun `home expansion uses provided homeDir`() {
        val home = setupHome()
        val paths = XdgAppPaths(emptyMap(), home)

        assertThat(paths.configDir).startsWith(home)
        assertThat(paths.dataDir).startsWith(home)
        assertThat(paths.stateDir).startsWith(home)
        assertThat(paths.cacheDir).startsWith(home)
    }

    @Test
    fun `idempotent directory creation`() {
        val xdgEnv = mapOf(
            "XDG_CONFIG_HOME" to tempDir.resolve("idempotent_cfg").toString(),
            "XDG_DATA_HOME" to tempDir.resolve("idempotent_dat").toString(),
            "XDG_STATE_HOME" to tempDir.resolve("idempotent_stt").toString(),
            "XDG_CACHE_HOME" to tempDir.resolve("idempotent_cch").toString()
        )
        val paths = XdgAppPaths(xdgEnv, homeDir())

        // Access twice — should not throw or change permissions
        val firstAccess = paths.configDir
        val secondAccess = paths.configDir

        assertThat(firstAccess).isEqualTo(secondAccess)
        assertThat(Files.exists(firstAccess)).isTrue()
    }
}

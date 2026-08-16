package com.romm.desktop.storage.paths

import com.romm.androidtv.storage.AppPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * XDG Base Directory compliant [AppPaths] implementation for Linux desktop.
 *
 * Directory layout:
 * - config:  `$XDG_CONFIG_HOME/rommulus` or `~/.config/rommulus`
 * - data:    `$XDG_DATA_HOME/rommulus` or `~/.local/share/rommulus`
 * - state:   `$XDG_STATE_HOME/rommulus` or `~/.local/state/rommulus`
 * - cache:   `$XDG_CACHE_HOME/rommulus` or `~/.cache/rommulus`
 *
 * Per §9 of LINUX_X64.md:
 * - config is 0755 (user-writable, group-readable — standard for config)
 * - data/state/cache are 0700 (user-only, per "user-only write permissions")
 *
 * Constructor accepts injectable parameters so tests can override env vars
 * and home directory without mutating the real environment:
 * - `xdgEnv`: map of XDG env var names → values, defaults to `System.getenv()`
 * - `homeDir`: the user's home directory, defaults to the JVM-resolved home
 *
 * Null or blank values in `xdgEnv` are treated as unset (fall through to defaults).
 */
class XdgAppPaths(
    private val xdgEnv: Map<String, String?> = System.getenv() as Map<String, String?>,
    private val homeDir: Path = Path.of(System.getProperty("user.home"))
) : AppPaths {

    private val configBase: Path by lazy { resolveXdgOrFallback("XDG_CONFIG_HOME", homeDir.resolve(".config"), "rommulus") }
    private val dataBase: Path by lazy { resolveXdgOrFallback("XDG_DATA_HOME", homeDir.resolve(".local").resolve("share"), "rommulus") }
    private val stateBase: Path by lazy { resolveXdgOrFallback("XDG_STATE_HOME", homeDir.resolve(".local").resolve("state"), "rommulus") }
    private val cacheBase: Path by lazy { resolveXdgOrFallback("XDG_CACHE_HOME", homeDir.resolve(".cache"), "rommulus") }

    override val configDir: Path get() = ensureDirectory(configBase, CONFIG_PERMISSIONS)
    override val dataDir: Path get() = ensureDirectory(dataBase, DATA_PERMISSIONS)
    override val stateDir: Path get() = ensureDirectory(stateBase, DATA_PERMISSIONS)
    override val cacheDir: Path get() = ensureDirectory(cacheBase, DATA_PERMISSIONS)

    private fun resolveXdgOrFallback(envVar: String, fallback: Path, appSubdir: String): Path {
        val value = xdgEnv[envVar]
        if (value != null && value.isNotBlank()) {
            return Path.of(value).resolve(appSubdir)
        }
        return fallback.resolve(appSubdir)
    }

    private fun ensureDirectory(base: Path, perms: Set<PosixFilePermission>): Path {
        if (!Files.exists(base)) {
            Files.createDirectories(base)
            try {
                Files.setPosixFilePermissions(base, perms)
            } catch (_: UnsupportedOperationException) {
                // Non-POSIX filesystems (e.g., Windows, FAT) don't support
                // PosixFilePermissions — permissions are not enforced.
            }
        }
        return base
    }

    internal companion object {
        private val CONFIG_PERMISSIONS: Set<PosixFilePermission> = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE
        ) // 0755: user-writable, group/others readable (standard for config)

        private val DATA_PERMISSIONS: Set<PosixFilePermission> = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
        ) // 0700: user-only (per §9 "user-only write permissions")
    }
}

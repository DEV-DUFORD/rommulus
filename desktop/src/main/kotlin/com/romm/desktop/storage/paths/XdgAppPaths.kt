package com.romm.desktop.storage.paths

import com.romm.androidtv.storage.AppPaths
import com.romm.desktop.platform.security.FileSecurityPolicies
import com.romm.desktop.platform.security.FileSecurityPolicy
import com.romm.desktop.platform.security.FileSensitivity
import com.romm.desktop.platform.security.PathPermissionProfile
import java.nio.file.Path

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
 *
 * Permission hardening is routed through the injected [securityPolicy]
 * (plans/WINDOWS_IMPL.md §4.2): on Linux this applies exactly the historical modes — config
 * 0755, data/state/cache 0700, applied only when the directory is created.
 */
class XdgAppPaths(
    private val xdgEnv: Map<String, String?> = System.getenv() as Map<String, String?>,
    private val homeDir: Path = Path.of(System.getProperty("user.home")),
    private val securityPolicy: FileSecurityPolicy = FileSecurityPolicies.default(),
) : AppPaths {

    private val configBase: Path by lazy { resolveXdgOrFallback("XDG_CONFIG_HOME", homeDir.resolve(".config"), "rommulus") }
    private val dataBase: Path by lazy { resolveXdgOrFallback("XDG_DATA_HOME", homeDir.resolve(".local").resolve("share"), "rommulus") }
    private val stateBase: Path by lazy { resolveXdgOrFallback("XDG_STATE_HOME", homeDir.resolve(".local").resolve("state"), "rommulus") }
    private val cacheBase: Path by lazy { resolveXdgOrFallback("XDG_CACHE_HOME", homeDir.resolve(".cache"), "rommulus") }

    override val configDir: Path get() = ensureDirectory(configBase, PathPermissionProfile.CONFIG_DIRECTORY, FileSensitivity.NORMAL)
    override val dataDir: Path get() = ensureDirectory(dataBase, PathPermissionProfile.USER_ONLY_DIRECTORY, FileSensitivity.SENSITIVE)
    override val stateDir: Path get() = ensureDirectory(stateBase, PathPermissionProfile.USER_ONLY_DIRECTORY, FileSensitivity.SENSITIVE)
    override val cacheDir: Path get() = ensureDirectory(cacheBase, PathPermissionProfile.USER_ONLY_DIRECTORY, FileSensitivity.NORMAL)

    private fun resolveXdgOrFallback(envVar: String, fallback: Path, appSubdir: String): Path {
        val value = xdgEnv[envVar]
        if (value != null && value.isNotBlank()) {
            return Path.of(value).resolve(appSubdir)
        }
        return fallback.resolve(appSubdir)
    }

    private fun ensureDirectory(
        base: Path,
        profile: PathPermissionProfile,
        sensitivity: FileSensitivity,
    ): Path {
        // Create-only hardening: pre-existing directories keep their owner-set permissions.
        securityPolicy.createDirectoryIfAbsent(base, profile, sensitivity)
        return base
    }
}

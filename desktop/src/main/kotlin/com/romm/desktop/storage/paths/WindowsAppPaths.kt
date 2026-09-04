package com.romm.desktop.storage.paths

import com.romm.androidtv.storage.AppPaths
import com.romm.desktop.platform.security.FileSecurityPolicies
import com.romm.desktop.platform.security.FileSecurityPolicy
import com.romm.desktop.platform.security.FileSensitivity
import com.romm.desktop.platform.security.PathPermissionProfile
import java.nio.file.Path

/**
 * Fakeable seam over the Windows Known Folder APIs (plans/WINDOWS_IMPL.md §3.4,
 * .slim/deepwork/windows-phase-0.md Phase 1 lane 1).
 *
 * Two resolvers exist behind the seam:
 * - [JnaWindowsKnownFolderResolver] calls the real Windows Known Folder API
 *   (`SHGetKnownFolderPath` for `FOLDERID_RoamingAppData` / `FOLDERID_LocalAppData`) and is what
 *   startup integration passes in; it honors per-user folder redirection and returns wide
 *   (Unicode/long-path safe) paths.
 * - [EnvironmentWindowsKnownFolderResolver] reads the same environment variables the shell sets
 *   for every interactive user; it fails explicitly when they are missing or blank (no silent
 *   home-relative fallback) and is the fully injectable default used by tests and by the
 *   process-default security policy until startup wires the JNA resolver.
 */
interface WindowsKnownFolderResolver {
    /** Roaming per-user app data (`%APPDATA%`). */
    fun roamingAppData(): Path

    /** Local per-user app data (`%LOCALAPPDATA%`). */
    fun localAppData(): Path
}

/**
 * Environment-based [WindowsKnownFolderResolver]: reads `APPDATA`/`LOCALAPPDATA` from an
 * injectable environment map (default: the real process environment). Missing or blank values
 * fail explicitly — a Windows profile without these variables cannot provide the containment
 * roots the file-security policy requires.
 */
class EnvironmentWindowsKnownFolderResolver(
    private val env: Map<String, String> = System.getenv(),
) : WindowsKnownFolderResolver {

    override fun roamingAppData(): Path = requireFolder("APPDATA")

    override fun localAppData(): Path = requireFolder("LOCALAPPDATA")

    private fun requireFolder(name: String): Path {
        val value = env[name]
        if (value.isNullOrBlank()) {
            throw IllegalStateException(
                "Windows known folder $name is not set; cannot resolve RomMulus app paths on this host",
            )
        }
        return Path.of(value)
    }
}

/**
 * Windows x86_64 [AppPaths] implementation (plans/WINDOWS_IMPL.md §3.4):
 *
 * - config:  `%APPDATA%\RomMulus`
 * - data:    `%LOCALAPPDATA%\RomMulus\data`   (database, saves, cores, BIOS)
 * - state:   `%LOCALAPPDATA%\RomMulus\state`  (logs, launch journals, instance lock)
 * - cache:   `%LOCALAPPDATA%\RomMulus\cache`
 *
 * Directories are created on first access and hardened through the injected [securityPolicy]
 * (containment + ACL on Windows; see [com.romm.desktop.platform.security.WindowsFileSecurityPolicy]).
 * Directory creation errors are surfaced — a partially initialized profile must not be used
 * (plans/WINDOWS_IMPL.md §4.2). Paths are plain JVM [Path] values: Unicode and long paths are
 * supported without any ANSI code-page round trip.
 */
class WindowsAppPaths(
    /**
     * Exposed (module-internal) so composition tests can assert the startup bundle passes ONE
     * shared resolver to both this class and the file-security policy's containment roots.
     */
    internal val knownFolders: WindowsKnownFolderResolver = EnvironmentWindowsKnownFolderResolver(),
    private val securityPolicy: FileSecurityPolicy = FileSecurityPolicies.default(),
) : AppPaths {

    override val configDir: Path
        get() = ensureDirectory(
            knownFolders.roamingAppData().resolve(APP_ROOT),
            PathPermissionProfile.CONFIG_DIRECTORY,
            FileSensitivity.NORMAL,
        )

    override val dataDir: Path
        get() = ensureDirectory(
            knownFolders.localAppData().resolve(APP_ROOT).resolve("data"),
            PathPermissionProfile.USER_ONLY_DIRECTORY,
            FileSensitivity.SENSITIVE,
        )

    override val stateDir: Path
        get() = ensureDirectory(
            knownFolders.localAppData().resolve(APP_ROOT).resolve("state"),
            PathPermissionProfile.USER_ONLY_DIRECTORY,
            FileSensitivity.SENSITIVE,
        )

    override val cacheDir: Path
        get() = ensureDirectory(
            knownFolders.localAppData().resolve(APP_ROOT).resolve("cache"),
            PathPermissionProfile.USER_ONLY_DIRECTORY,
            FileSensitivity.NORMAL,
        )

    private fun ensureDirectory(
        base: Path,
        profile: PathPermissionProfile,
        sensitivity: FileSensitivity,
    ): Path {
        securityPolicy.createDirectoryIfAbsent(base, profile, sensitivity)
        return base
    }

    companion object {
        /** Per-plan application root directory name under the known folders. */
        const val APP_ROOT = "RomMulus"
    }
}

package com.romm.desktop.platform.security

import com.romm.desktop.platform.DesktopPlatformDetector
import com.romm.desktop.platform.HostOs
import com.romm.desktop.storage.paths.EnvironmentWindowsKnownFolderResolver
import com.romm.desktop.storage.paths.WindowsKnownFolderResolver
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Permission profile the desktop applies to a path it creates or manages
 * (plans/WINDOWS_IMPL.md §4.2). The profiles consolidate the mode sets that were previously
 * duplicated across paths, logging, locking, journals, player logs, SQLite, and BIOS staging:
 *
 * - [CONFIG_DIRECTORY] = 0755 on Linux (XDG config directory);
 * - [USER_ONLY_DIRECTORY] = 0700 on Linux;
 * - [USER_ONLY_FILE] = 0600 on Linux.
 *
 * On Windows the profile is informational: hardening is driven by [FileSensitivity] and the
 * NTFS ACL seam, not by POSIX mode bits.
 */
enum class PathPermissionProfile {
    /** 0755: owner rwx, group/other rx — XDG config directory (Linux). */
    CONFIG_DIRECTORY,

    /** 0700: owner rwx only — user-only directories (state/data/cache/session/firmware/lock). */
    USER_ONLY_DIRECTORY,

    /** 0600: owner rw only — user-only files (journals, logs, credentials, database, firmware). */
    USER_ONLY_FILE;

    /** The exact POSIX mode bits this profile maps to on Linux. */
    fun posixPermissions(): Set<PosixFilePermission> = when (this) {
        CONFIG_DIRECTORY -> setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE,
        )
        USER_ONLY_DIRECTORY -> setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        USER_ONLY_FILE -> setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
    }
}

/**
 * Sensitivity of the data at a managed path (plans/WINDOWS_IMPL.md §4.2). Drives how strictly
 * a [FileSecurityPolicy] must establish security:
 *
 * - [NORMAL]: non-sensitive app data (caches, advisory locks). Hardening is best-effort; an
 *   unavailable permission model degrades to the historical no-op on Linux and to containment
 *   verification only on Windows.
 * - [SENSITIVE]: tokens, BIOS/firmware, databases, journals, logs. The policy must establish
 *   user-only security (POSIX mode bits on Linux, containment + ACL on Windows) or fail
 *   explicitly — never a silent success-shaped fallback.
 */
enum class FileSensitivity {
    NORMAL,
    SENSITIVE,
}

/** Thrown when a [FileSecurityPolicy] cannot establish the security a path requires. */
class FileSecurityException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Consolidated permission/ACL hardening for desktop-managed paths (plans/WINDOWS_IMPL.md §4.2).
 *
 * Every production call site that previously invoked `Files.setPosixFilePermissions` directly
 * routes through this interface instead:
 *
 * - [LinuxFileSecurityPolicy] applies the historical POSIX modes exactly; on a filesystem
 *   without POSIX permission bits it is a no-op for [FileSensitivity.NORMAL] paths (the
 *   historical behavior) and fails explicitly for [FileSensitivity.SENSITIVE] ones.
 * - [WindowsFileSecurityPolicy] verifies containment in the current user's profile roots,
 *   rejects reparse points/symlinks, and applies a current-user-only NTFS ACL through the
 *   fakeable [WindowsAclApplier] seam for sensitive paths — failing explicitly when security
 *   cannot be established.
 */
interface FileSecurityPolicy {
    /**
     * Ensure [path] exists as a directory (creating it and any parents when absent) and apply
     * hardening whether or not the directory already existed. Used where the historical code
     * re-applied mode bits on every access (e.g. the instance-lock directory).
     */
    fun ensureDirectory(path: Path, profile: PathPermissionProfile, sensitivity: FileSensitivity)

    /**
     * Ensure [path] exists as a directory and apply hardening when this call created it.
     * Creation errors are surfaced to the caller; do not catch them here.
     *
     * Pre-existing directories — intentional platform divergence:
     * - [LinuxFileSecurityPolicy]: hardening is create-only. Pre-existing directories keep
     *   whatever mode bits their owner set (the historical XDG behavior).
     * - [WindowsFileSecurityPolicy]: containment is re-verified and sensitive ACLs are
     *   re-applied on every call, even for pre-existing directories. NTFS has no owner-set
     *   "mode bits" to preserve, the ACE set always grants the owning user (plus SYSTEM) full
     *   control, so re-application is idempotent and enforces the required security instead of
     *   trusting whatever DACL happened to be there.
     */
    fun createDirectoryIfAbsent(path: Path, profile: PathPermissionProfile, sensitivity: FileSensitivity)

    /** Apply hardening to an existing regular file. */
    fun hardenFile(path: Path, profile: PathPermissionProfile, sensitivity: FileSensitivity)
}

/**
 * Linux (and macOS development host) [FileSecurityPolicy]: applies the exact historical POSIX
 * mode bits for each [PathPermissionProfile].
 *
 * On a filesystem without POSIX permission bits (non-POSIX mounts), [FileSensitivity.NORMAL]
 * paths keep the historical silent no-op; [FileSensitivity.SENSITIVE] paths fail explicitly —
 * an unsupported filesystem must not yield a success-shaped fallback for sensitive data
 * (plans/WINDOWS_IMPL.md §4.2).
 */
class LinuxFileSecurityPolicy : FileSecurityPolicy {

    override fun ensureDirectory(path: Path, profile: PathPermissionProfile, sensitivity: FileSensitivity) {
        Files.createDirectories(path)
        applyPermissions(path, profile, sensitivity)
    }

    override fun createDirectoryIfAbsent(
        path: Path,
        profile: PathPermissionProfile,
        sensitivity: FileSensitivity,
    ) {
        if (!Files.exists(path)) {
            Files.createDirectories(path)
            applyPermissions(path, profile, sensitivity)
        }
    }

    override fun hardenFile(path: Path, profile: PathPermissionProfile, sensitivity: FileSensitivity) {
        applyPermissions(path, profile, sensitivity)
    }

    private fun applyPermissions(
        path: Path,
        profile: PathPermissionProfile,
        sensitivity: FileSensitivity,
    ) {
        if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, profile.posixPermissions())
            return
        }
        if (sensitivity == FileSensitivity.SENSITIVE) {
            throw FileSecurityException(
                "cannot establish user-only permissions for sensitive path $path: the filesystem " +
                    "does not support POSIX permission bits; refusing to continue with a " +
                    "success-shaped fallback",
            )
        }
        // NORMAL on a non-POSIX filesystem: historical no-op.
    }
}

/**
 * Fakeable Win32 seam for applying NTFS ACLs (plans/WINDOWS_IMPL.md §4.2). The real production
 * implementation is [JnaWindowsAclApplier] (current user + SYSTEM only); tests supply fakes.
 * Implementations must throw when the ACL cannot be established (unsupported filesystem,
 * Win32 error, or not yet configured).
 */
fun interface WindowsAclApplier {
    /** Restrict [path] to the current user (plus SYSTEM). Throws if the ACL cannot be established. */
    fun applyCurrentUserOnlyAcl(path: Path)
}

/**
 * Fail-closed [WindowsAclApplier] used by the process-default policy ([FileSecurityPolicies.forHost])
 * on Windows until startup integration selects a real applier (e.g. [JnaWindowsAclApplier])
 * through [FileSecurityPolicies.forWindows]. It always fails, which is exactly what sensitive
 * Windows paths require before real ACL operations are wired: an explicit refusal rather than a
 * silent success-shaped fallback (plans/WINDOWS_IMPL.md §4.2).
 */
object UnconfiguredWindowsAclApplier : WindowsAclApplier {
    override fun applyCurrentUserOnlyAcl(path: Path): Nothing = throw IllegalStateException(
        "Windows ACL hardening is not configured for $path: startup integration must select a " +
            "Win32-backed WindowsAclApplier (JnaWindowsAclApplier) via FileSecurityPolicies.forWindows " +
            "before sensitive Windows paths are managed (plans/WINDOWS_IMPL.md §4.2)",
    )
}

/**
 * Windows [FileSecurityPolicy] (plans/WINDOWS_IMPL.md §4.2):
 *
 * - every managed path must be contained in one of [trustedRoots] (the current user's
 *   `%APPDATA%` / `%LOCALAPPDATA%` roots) after absolute normalization — otherwise the
 *   operation fails, for any sensitivity;
 * - an existing reparse point/symlink at the target is rejected;
 * - [FileSensitivity.SENSITIVE] paths additionally get a current-user-only NTFS ACL through
 *   [aclApplier]; any failure there is surfaced as a [FileSecurityException];
 * - [FileSensitivity.NORMAL] paths rely on containment plus the per-user profile inheritance
 *   and are not ACL'd explicitly;
 * - unlike [LinuxFileSecurityPolicy], [createDirectoryIfAbsent] re-verifies containment and
 *   re-applies sensitive ACLs even when the directory already exists (see the interface docs
 *   for why this divergence is intentional).
 */
class WindowsFileSecurityPolicy(
    private val trustedRootsProvider: () -> List<Path>,
    private val aclApplier: WindowsAclApplier,
) : FileSecurityPolicy {

    /** Eager roots for callers (and tests) that already hold the resolved paths. */
    constructor(trustedRoots: List<Path>, aclApplier: WindowsAclApplier) :
        this({ trustedRoots }, aclApplier)

    /**
     * Resolver-backed roots (the startup seam): resolved LAZILY on first use, so constructing
     * the policy never touches the platform Known Folder API — JNA natives stay unloaded until a
     * path operation actually runs (plans/WINDOWS_IMPL.md §3.4/§4.2).
     */
    constructor(knownFolders: WindowsKnownFolderResolver, aclApplier: WindowsAclApplier) :
        this({ listOf(knownFolders.roamingAppData(), knownFolders.localAppData()) }, aclApplier)

    private val normalizedRoots: List<Path> by lazy {
        trustedRootsProvider().map { it.toAbsolutePath().normalize() }
    }

    override fun ensureDirectory(path: Path, profile: PathPermissionProfile, sensitivity: FileSensitivity) {
        requireContained(path)
        Files.createDirectories(path)
        harden(path, sensitivity)
    }

    override fun createDirectoryIfAbsent(
        path: Path,
        profile: PathPermissionProfile,
        sensitivity: FileSensitivity,
    ) {
        requireContained(path)
        if (!Files.exists(path)) {
            Files.createDirectories(path)
        }
        harden(path, sensitivity)
    }

    override fun hardenFile(path: Path, profile: PathPermissionProfile, sensitivity: FileSensitivity) {
        requireContained(path)
        harden(path, sensitivity)
    }

    private fun requireContained(path: Path) {
        val abs = path.toAbsolutePath().normalize()
        if (normalizedRoots.none { abs.startsWith(it) }) {
            throw FileSecurityException(
                "path $abs is outside the trusted Windows user roots $normalizedRoots; refusing " +
                    "to manage it",
            )
        }
        if (Files.exists(abs) && Files.isSymbolicLink(abs)) {
            throw FileSecurityException("refusing to manage reparse point/symlink at $abs")
        }
    }

    private fun harden(path: Path, sensitivity: FileSensitivity) {
        if (sensitivity != FileSensitivity.SENSITIVE) return
        try {
            aclApplier.applyCurrentUserOnlyAcl(path)
        } catch (e: Exception) {
            throw FileSecurityException(
                "cannot establish security for sensitive path $path",
                e,
            )
        }
    }
}

/**
 * Selection of the process-default [FileSecurityPolicy] — the single place (besides
 * [DesktopPlatformDetector]) that maps a host to platform behavior. Feature code passes
 * [default] as its constructor default and never sniffs `os.name` itself; tests inject fakes.
 */
object FileSecurityPolicies {

    /** The policy for the real JVM host, selected once per call (cheap; no I/O). */
    fun default(): FileSecurityPolicy =
        forHost(System.getProperty("os.name") ?: "", System.getenv())

    /**
     * Explicit Windows construction seam for startup integration (plans/WINDOWS_IMPL.md §4.2):
     * builds the containment roots from a [WindowsKnownFolderResolver] and accepts the
     * Win32-backed [WindowsAclApplier] startup selected.
     *
     * Startup MUST pass the SAME resolver instance it constructed [WindowsAppPaths] with, so
     * the policy's trusted roots are guaranteed to match the roots the app paths resolve under
     * (an environment-based or JNA Known Folder resolver are both acceptable). The roots are
     * resolved lazily on first path operation, so constructing this policy — and the whole
     * startup adapter bundle — never loads the Known Folder API eagerly. Until this seam is used
     * with a real ACL applier, sensitive Windows paths fail closed through the
     * [UnconfiguredWindowsAclApplier] default in [forHost].
     */
    fun forWindows(
        knownFolders: WindowsKnownFolderResolver,
        aclApplier: WindowsAclApplier,
    ): FileSecurityPolicy = WindowsFileSecurityPolicy(knownFolders, aclApplier)

    /**
     * Selects a policy for raw `os.name`/environment values so tests never read the real host.
     * Linux and macOS (development host) use [LinuxFileSecurityPolicy]; Windows uses
     * [WindowsFileSecurityPolicy] rooted at `%APPDATA%`/`%LOCALAPPDATA%` with the
     * [UnconfiguredWindowsAclApplier] until startup integration supplies the Win32-backed one.
     */
    fun forHost(osName: String, env: Map<String, String> = System.getenv()): FileSecurityPolicy =
        when (DesktopPlatformDetector.normalizeOs(osName)) {
            HostOs.WINDOWS ->
                // Fail-closed default: environment-based roots + the refusing ACL applier.
                // Startup integration replaces both via [forWindows].
                forWindows(EnvironmentWindowsKnownFolderResolver(env), UnconfiguredWindowsAclApplier)

            // Linux production host, macOS development host, and unknown hosts (which cannot
            // start a production build anyway) all use the conservative POSIX policy.
            else -> LinuxFileSecurityPolicy()
        }
}

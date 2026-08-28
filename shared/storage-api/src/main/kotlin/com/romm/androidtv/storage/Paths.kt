package com.romm.androidtv.storage

import java.nio.file.Path
import java.nio.file.Paths

/**
 * XDG-style path policy contract for platform-neutral directory resolution.
 *
 * Implementations resolve config, data, state, and cache roots.
 * Pure functions on this interface accept an [AppPaths] to derive sub-paths.
 */
interface AppPaths {
    val configDir: Path
    val dataDir: Path
    val stateDir: Path
    val cacheDir: Path
}

/**
 * Hoisting rules that govern which durable artefacts live under which root.
 */
object StoreHoistingRules {
    /** Saves, firmware selections, databases, journals, and pending operations never live under cache. */
    const val AUTHORITATIVE_NEVER_CACHE = "authoritative_never_cache"

    /** Cache may be deleted and rebuilt without losing authoritative state. */
    const val CACHE_IS_REBUILDABLE = "cache_is_rebuildable"

    /** Every metadata write uses temp file, flush, fsync, atomic rename. */
    const val ATOMIC_WRITE_REQUIRED = "atomic_write_required"

    /** Reject symlinks while staging ROMs, firmware, and save candidates. */
    const val SYMLINKS_REJECTED = "symlinks_rejected"
}

/** Resolve the settings file path under an [AppPaths].configDir. */
fun AppPaths.settingsFile(): Path = configDir.resolve("settings.json")

/** Resolve the database directory under an [AppPaths].dataDir. */
fun AppPaths.databaseDir(): Path = dataDir.resolve("database")

/** Resolve the saves directory under an [AppPaths].dataDir. */
fun AppPaths.savesDir(): Path = dataDir.resolve("saves")

/** Resolve the firmware/BIOS staging directory under an [AppPaths].dataDir (LINUX_X64.md §9 rule 1: firmware never lives under cache). */
fun AppPaths.firmwareDir(): Path = dataDir.resolve("firmware")

/** Resolve the journals directory under an [AppPaths].stateDir. */
fun AppPaths.journalsDir(): Path = stateDir.resolve("journals")

/** Resolve the logs directory under an [AppPaths].stateDir. */
fun AppPaths.logsDir(): Path = stateDir.resolve("logs")

/** Resolve the ROM cache directory under an [AppPaths].cacheDir. */
fun AppPaths.romCacheDir(): Path = cacheDir.resolve("roms")

/** Resolve the artwork cache directory under an [AppPaths].cacheDir. */
fun AppPaths.artworkCacheDir(): Path = cacheDir.resolve("artwork")

/**
 * Verify that a candidate path lives under one of the approved roots and is not a symlink.
 * Returns true if the path is acceptable; false otherwise.
 */
fun AppPaths.isApprovedPath(candidate: Path, followSymlinks: Boolean = false): Boolean {
    val abs = candidate.toAbsolutePath().normalize()
    val roots = listOf(configDir, dataDir, stateDir, cacheDir).map { it.toAbsolutePath().normalize() }
    if (!roots.any { abs.startsWith(it) }) return false
    // If the file exists, also check for symlinks.
    if (java.nio.file.Files.exists(abs)) {
        if (!followSymlinks && java.nio.file.Files.isSymbolicLink(abs)) return false
    }
    return true
}

/**
 * Reject a path if it is a symlink.
 */
fun rejectIfSymlink(candidate: Path): Result<Unit> {
    val abs = candidate.toAbsolutePath()
    if (java.nio.file.Files.isSymbolicLink(abs)) {
        return Result.failure(IllegalArgumentException("Symlink detected: ${candidate}"))
    }
    return Result.success(Unit)
}

/**
 * Factory for a test-friendly [AppPaths] backed by a temporary directory.
 */
fun AppPaths.testRoot(tempDir: Path): TestAppPaths = TestAppPaths(tempDir)

class TestAppPaths(override val configDir: Path,
                    override val dataDir: Path,
                    override val stateDir: Path,
                    override val cacheDir: Path) : AppPaths {
    constructor(root: Path) : this(
        root.resolve("config"),
        root.resolve("data"),
        root.resolve("state"),
        root.resolve("cache")
    )
}

package com.romm.desktop.storage

import com.romm.androidtv.storage.ports.AppInstanceLock
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap

/**
 * File-based single-instance lock for the desktop, implementing the shared
 * [AppInstanceLock] port (see §10.4 of LINUX_X64.md, §4 item 5 of PHASE5.md).
 *
 * Callers resolve the parent directory (`$XDG_RUNTIME_DIR` when available,
 * otherwise the app state dir) and pass the resulting lock file, so the
 * [Path] is constructor-injected rather than read from the environment —
 * what matters for a second *invocation* is the concrete path, and injection
 * keeps this class unit-testable. The [resolveLockFile] helper and the
 * `FileLockAppInstanceLock(runtimeDir, stateDir)` convenience constructor
 * implement the §10.4 preference (runtime dir, else state dir) for callers
 * that want it.
 *
 * Mechanism: a whole-file *advisory* lock via [FileChannel.tryLock] (non-blocking).
 * The OS releases advisory locks when the owning process terminates — including
 * a crash — so a stale `rommulus.lock` *file* is never a wedge; only a live
 * holder matters.
 *
 * Semantics:
 * - [acquire] is NOT reentrant: a call while this instance already holds the
 *   lock returns `false` (matching the in-memory fake's documented behavior).
 * - In-JVM instances sharing the same lock path reject each other via a small
 *   process-wide registry. This is necessary because two `FileChannel` locks
 *   opened inside one JVM do not conflict on all platforms (on Linux,
 *   `fcntl`-based locks are per-process and a second channel silently
 *   re-acquires). Distinct *processes* — the real multi-instance case — are
 *   still distinguished by the OS advisory lock itself.
 * - Fail closed: if the lock file or its directory cannot be created (read-only
 *   filesystem, permission denied, path occupied by a directory) or another
 *   process holds the lock, [acquire] returns `false` and never throws. The
 *   *result* of `acquire() == false` is the caller's job (focus the existing
 *   process if a safe mechanism exists, otherwise exit with an explanatory
 *   message); this class only reports.
 *
 * Permissions (per §9's "user-only" convention): the lock directory is
 * created `0700` and the lock file is set to `0600` wherever the filesystem
 * supports POSIX permission bits (best effort otherwise — advisory locks do
 * not depend on the mode bits).
 */
class FileLockAppInstanceLock(
    val lockFile: Path
) : AppInstanceLock {

    /**
     * Convenience constructor per §10.4: the lock file lives under
     * [runtimeDir] (`$XDG_RUNTIME_DIR`) when it exists, otherwise under
     * [stateDir]. A `null` [runtimeDir] (unset variable) falls back to
     * [stateDir].
     */
    constructor(runtimeDir: Path?, stateDir: Path) :
        this(resolveLockFile(runtimeDir, stateDir))

    @Volatile
    private var channel: FileChannel? = null

    @Volatile
    private var held: Boolean = false

    private val lockKey: Path = lockFile.toAbsolutePath().normalize()

    override fun acquire(): Boolean = synchronized(this) {
        if (held) return false
        if (registry.containsKey(lockKey)) return false

        try {
            lockFile.parent?.let { parent ->
                Files.createDirectories(parent)
                try {
                    Files.setPosixFilePermissions(parent, DIRECTORY_PERMISSIONS)
                } catch (_: UnsupportedOperationException) {
                    // Non-POSIX filesystem: no permission bits to set.
                }
            }

            val opened = FileChannel.open(lockFile, READ, WRITE, CREATE)
            try {
                Files.setPosixFilePermissions(lockFile, FILE_PERMISSIONS)
            } catch (_: UnsupportedOperationException) {
                // Non-POSIX filesystem: mode bits unavailable; advisory lock
                // semantics do not depend on them.
            }

            // Non-blocking: null means another *process* already holds the lock.
            if (opened.tryLock() == null) {
                quietlyClose(opened)
                return false
            }

            registry[lockKey] = opened
            channel = opened
            held = true
            return true
        } catch (_: Exception) {
            // Fail closed: permission denied, read-only fs, path occupied by a
            // directory, … Never proceed without the lock.
            return false
        }
    }

    override fun release() {
        synchronized(this) {
            val opened = channel ?: return
            // Closing the channel releases the advisory lock: POSIX releases
            // fcntl/flock locks when the underlying file descriptor closes, so
            // no explicit unlock call is needed (and FileChannel exposes no
            // getter for the held lock object).
            quietlyClose(opened)
            channel = null
            held = false
            registry.remove(lockKey)
        }
    }

    override fun isHeld(): Boolean = held

    private fun quietlyClose(channel: FileChannel) {
        try {
            channel.close()
        } catch (_: IOException) {
            // Best effort; a closed or dead channel has nothing left to sweep.
        }
    }

    companion object {

        /** Lock file name (§10.4). Callers resolve the parent directory. */
        const val LOCK_FILE_NAME = "rommulus.lock"

        /**
         * Resolve the lock file per §10.4: prefer [runtimeDir]
         * (`$XDG_RUNTIME_DIR`) when it exists, otherwise [stateDir].
         */
        fun resolveLockFile(runtimeDir: Path?, stateDir: Path): Path =
            (runtimeDir?.takeIf { Files.isDirectory(it) } ?: stateDir).resolve(LOCK_FILE_NAME)

        /** 0700: owner-only (user-only write permissions). */
        val DIRECTORY_PERMISSIONS: Set<PosixFilePermission> = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
        )

        /** 0600: owner read/write only. */
        val FILE_PERMISSIONS: Set<PosixFilePermission> = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        )

        /**
         * In-JVM registry of live lock instances per normalized path.
         *
         * Advising [FileChannel] locks created by distinct channels of the
         * *same* process do not reliably conflict on every platform (Linux
         * `fcntl` locks are per-process: a second channel silently re-acquires
         * the lock, dropping the first channel's), so same-JVM "second
         * instances" are rejected here; cross-process exclusion is provided by
         * the OS advisory lock.
         */
        private val registry = ConcurrentHashMap<Path, FileChannel>()
    }
}

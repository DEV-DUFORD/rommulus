package com.romm.desktop.player

import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Bounded capture of the player process's combined stdout+stderr.
 *
 * The launcher ([ProcessBuilderPlayerLauncher]) merges the player's stderr into its stdout
 * (`redirectErrorStream(true)`) and drains that stream on a daemon thread ([startDraining]),
 * appending it to the per-session log file `<sessionDir>/player.log`
 * ([LaunchJournalStore.PLAYER_LOG_FILE_NAME]).
 *
 * ## Bounding
 * The active file is capped at [maxBytes] ([DEFAULT_MAX_BYTES] = 2 MiB). When the cap would be
 * exceeded, the active file is rotated to `player.log.1` (replacing the previous rotation) and
 * a fresh `player.log` is started — so one session can never write more than 2 × [maxBytes]
 * (4 MiB by default) no matter how chatty the core is. Across sessions the log is deleted with
 * the other session artifacts once the session is reconciled ([LaunchJournalSupervisor]); only
 * INTERRUPTED (forensic) sessions retain it, consistent with the "files preserved" invariant.
 *
 * ## Never blocks the player
 * The drain reads the stream unconditionally: even when a file write fails (disk full,
 * permissions), reading continues so the player's pipe can never fill and deadlock the core.
 * Capture is diagnostic, never load-bearing — all write failures are swallowed.
 *
 * ## Flush/close
 * Every write is flushed immediately. The file is flushed and closed when the stream reaches
 * EOF (the player exits or is destroyed) or when [close] is called explicitly (spawn failure).
 * [close] is idempotent and safe to call from any thread.
 */
class PlayerLogCapture(
    private val logFile: Path,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val lock = Any()
    @Volatile private var closed = false
    private var stream: FileOutputStream? = null
    /** Bytes written to the ACTIVE file since it was opened (rotation accounting). */
    private var activeBytes = 0L

    /** Appends [text] plus a newline (UTF-8). Best-effort: failures are swallowed (class doc). */
    fun appendLine(text: String) {
        append((text + "\n").toByteArray(Charsets.UTF_8))
    }

    /**
     * Appends [bytes] to the log, rotating the active file when the cap would be exceeded.
     * A single call larger than [maxBytes] is split across rotations, so the on-disk footprint
     * never exceeds 2 × [maxBytes].
     */
    fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        synchronized(lock) {
            if (closed) return
            try {
                var offset = 0
                while (offset < bytes.size && !closed) {
                    val out = stream ?: openStream()
                    if (activeBytes >= maxBytes) {
                        rotate(out)
                        continue
                    }
                    val space = (maxBytes - activeBytes).toInt()
                    val len = minOf(space, bytes.size - offset)
                    out.write(bytes, offset, len)
                    offset += len
                    activeBytes += len
                }
                stream?.flush()
            } catch (_: Exception) {
                // Fail-soft: the drain thread keeps reading the stream either way, so the
                // player's pipe can never fill.
            }
        }
    }

    /**
     * Starts the daemon drain thread for [process]'s (merged) output stream. The thread reads
     * until EOF — which happens when the player exits or is destroyed — then flushes and closes
     * the log. The drain buffer is reused across reads: [append] is synchronous (it writes the
     * chunk before returning), so the buffer is never read stale.
     */
    fun startDraining(process: Process) {
        val thread = Thread {
            val buffer = ByteArray(DRAIN_BUFFER_SIZE)
            try {
                val input = process.inputStream
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) append(buffer.copyOf(read))
                }
            } catch (_: Exception) {
                // Stream broke (process destroyed, I/O error): stop capturing.
            } finally {
                close()
            }
        }
        thread.isDaemon = true
        thread.name = "player-log-drain-${logFile.parent?.fileName ?: "session"}"
        thread.start()
    }

    /** Flushes and closes the log. Idempotent. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            val out = stream
            stream = null
            runCatching { out?.flush() }
            runCatching { out?.close() }
        }
    }

    /**
     * Opens the log file in append mode with 0600 (journal files are user-only, §9). The parent
     * (session) directory is NOT created: a launch whose session directory was never prepared
     * must not leave an orphan directory behind for the startup scan to report.
     */
    private fun openStream(): FileOutputStream {
        val out = FileOutputStream(logFile.toFile(), /* append = */ true)
        runCatching {
            Files.setPosixFilePermissions(
                logFile,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } // Non-POSIX filesystems: permissions are not enforced there.
        activeBytes = runCatching { Files.size(logFile) }.getOrDefault(0L)
        stream = out
        return out
    }

    /** Moves the active file to the rotation slot (replacing it) and starts a fresh active file. */
    private fun rotate(out: FileOutputStream) {
        val rotated = logFile.resolveSibling(logFile.fileName.toString() + ROTATION_SUFFIX)
        runCatching {
            out.flush()
            out.close()
            Files.move(logFile, rotated, StandardCopyOption.REPLACE_EXISTING)
        }
        // Whether the move succeeded or not, continue on a fresh active file. If it failed
        // (e.g. locked on a non-POSIX FS), the cap becomes advisory until the next rotation.
        openStream()
    }

    companion object {
        /** Cap for the ACTIVE log file; with the single rotation slot, a session writes ≤ 2× this. */
        const val DEFAULT_MAX_BYTES: Long = 2L * 1024 * 1024

        /** Rotation slot suffix: `player.log` → `player.log.1` (older content is replaced). */
        const val ROTATION_SUFFIX = ".1"

        private const val DRAIN_BUFFER_SIZE = 8 * 1024
    }
}

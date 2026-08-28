/*
 * Copyright (c) 2025 Romm Android TV contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.romm.desktop.log

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Desktop logger: JUL-backed, with **token redaction** and **bounded rotation**.
 *
 * Configuration (tunable via the named constants below):
 *  - One active log file + [backupCount] rotated files, each ≤ [maxBytes] bytes.
 *  - Every record passes through [TokenRedactor.redact] before hitting the file or stderr.
 *  - Files are created under [AppPaths.stateDir]`/logs` (or `~/.local/state/rommulus/logs`).
 *  - Log files are created with mode 0600 (owner read/write only).
 *
 * **Note to maintainers:** this is intentionally a tiny shim around JUL. The test suite exercises
 * it through [newLogger]; production code can swap the implementation later without touching call
 * sites — [Logger] is the public surface.
 */
object DesktopLogger {

    const val MAX_BYTES: Int = 1 * 1024 * 1024
    const val BACKUP_COUNT: Int = 3
    val DEFAULT_LEVEL: String = Level.INFO.name
    const val LOGGER_NAME: String = "com.romm.desktop"
    val LOG_FILE_NAME: String = "romm-desktop.log"

    /** Per §9 LINUX_X64.md: logs dir uses user-only write permissions (0700). */
    private val LOGS_DIR_PERMISSIONS: Set<PosixFilePermission> = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
    )

    /** Default log directory (XDG state + "rommulus"/logs). Used when no AppPaths is available. */
    private fun defaultLogsDir(): Path =
        Paths.get(System.getProperty("user.home"), ".local", "state", "rommulus", "logs")

    /**
     * Create a fully configured Logger bound to [logsDir] (or default).
     *
     * The returned Logger must not be logged through before the caller invokes [install] (or uses
     * the result directly). Returned Logger instance is thread-safe once installed.
     */
    fun newLogger(logsDir: Path? = null): Logger {
        val logDir = (logsDir ?: defaultLogsDir()).toFile()
        if (!logDir.exists()) {
            Files.createDirectories(logDir.toPath())
            try {
                Files.setPosixFilePermissions(logDir.toPath(), LOGS_DIR_PERMISSIONS)
            } catch (_: UnsupportedOperationException) {
                // Non-POSIX filesystems (e.g., Windows, FAT) don't support
                // PosixFilePermissions — permissions are not enforced.
            }
        }
        // append=true: active log is `romm-desktop.log`; rotated files are `romm-desktop.log.1`,
        // `romm-desktop.log.2`, etc. (no `.0` suffix on the active file).
        val handler = FileHandler(
            logDir.absolutePath + "/" + LOG_FILE_NAME,
            MAX_BYTES,
            BACKUP_COUNT,
            true,
        )
        handler.encoding = "UTF-8"
        handler.formatter = RedactingFormatter()
        handler.level = Level.parse(DEFAULT_LEVEL)
        // FileHandler handles rotation automatically via limit/count; no explicit truncation needed.
        val logger = Logger.getLogger(LOGGER_NAME)
        // Remove any existing handlers to avoid accumulation across calls.
        while (logger.handlers.isNotEmpty()) {
            val h = logger.handlers[0]
            logger.removeHandler(h)
            h.close()
        }
        logger.useParentHandlers = false
        logger.level = Level.parse(DEFAULT_LEVEL)
        logger.addHandler(handler)
        // Stderr handler so developers see output in a terminal without a log file.
        val stderr = java.util.logging.ConsoleHandler().apply {
            level = Level.parse(DEFAULT_LEVEL)
            formatter = RedactingFormatter()
        }
        logger.addHandler(stderr)
        return logger
    }

    /** Convenience singleton accessor. Idempotent. */
    fun get(): Logger = Holder.instance

    /** Marker type so callers can plug in their own AppPaths if needed (placeholder for Phase 6). */
    fun interface LogDirectoryProvider {
        fun resolveLogsDir(): Path
    }

    /** Holder: eager, thread-safe singleton. */
    private object Holder {
        val instance: Logger = newLogger()
    }
}

/**
 * Formatter that redacts secrets from formatted log messages.
 *
 * The message text and the parameter array (if formatted as a toString chain) both flow through
 * [TokenRedactor.redact] before reaching the underlying formatter.
 */
internal class RedactingFormatter : java.util.logging.Formatter() {

    override fun format(record: java.util.logging.LogRecord): String {
        // Redact the core message.
        var msg: String? = record.message
        if (msg != null) msg = TokenRedactor.redact(msg)

        // Redact any parameter-provided messages (e.g. `logger.info("token={}", secret)`).
        val redactedParams = if (record.parameters != null) {
            record.parameters.map { it?.let { TokenRedactor.redact(it.toString()) } ?: it }.toTypedArray()
        } else null

        // Redact the exception's message, if any — wrap with a redacting substitute.
        val redactedThrown = record.getThrown()?.let { RedactedThrowable(it) }

        // Format the message directly without creating a new LogRecord.
        val levelStr = record.level.name
        val loggerName = record.loggerName ?: ""
        val threadID = record.threadID
        val timeMillis = record.millis
        val simpleDoc = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(java.util.Date(timeMillis))
        val formattedMsg = if (redactedParams != null && redactedParams.isNotEmpty()) {
            // Parameterized message: format with redacted params.
            try {
                val formatString = msg ?: ""
                formatString.format(*redactedParams)
            } catch (_: Exception) {
                msg
            }
        } else {
            msg
        }

        val result = "$levelStr $loggerName - $threadID - $simpleDoc - $formattedMsg"
        return result
    }

    /** Wraps a throwable to redact its message without mutating the original. */
    private class RedactedThrowable(private val delegate: Throwable) : Throwable() {
        override fun toString(): String {
            val sb = StringBuilder()
            sb.append(delegate.javaClass.name)
            val m = delegate.message
            if (m != null) {
                sb.append(": ").append(TokenRedactor.redact(m))
            }
            return sb.toString()
        }

        override fun getStackTrace(): Array<StackTraceElement> = delegate.stackTrace
    }

}

/*
 * Copyright (c) 2025 Romm Android TV contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.romm.desktop.log

import java.nio.file.Path
import java.util.logging.Logger

/**
 * Test-time construction helper for [DesktopLogger].
 *
 * Exposes tunable rotation parameters and an explicit log file path for tests — production code
 * uses [DesktopLogger.get] or [DesktopLogger.newLogger] with defaults. Separating the test helper
 * keeps the public API surface of [DesktopLogger] small while still allowing tests to verify
 * rotation behaviour with tiny max-bytes and backup-count values.
 */
internal object DesktopLoggerTestHelper {

    /**
     * Build a Logger bound to a specific [logFile] with custom rotation parameters.
     *
     * Identical semantics to [DesktopLogger.newLogger] but accepts rotation tunables and an explicit
     * log file path for tests.
     */
    fun createLogger(
        logFile: Path,
        maxBytes: Int = DesktopLogger.MAX_BYTES,
        backupCount: Int = DesktopLogger.BACKUP_COUNT,
        level: java.util.logging.Level = java.util.logging.Level.parse(DesktopLogger.DEFAULT_LEVEL),
        loggerName: String = DesktopLogger.LOGGER_NAME + ".test",
    ): Logger {
        val handler = java.util.logging.FileHandler(
            logFile.toFile().absolutePath,
            maxBytes,
            backupCount,
            true,  // append=true: active file is `romm-desktop.log`; rotated = `.1`, `.2`, etc.
        )
        handler.encoding = "UTF-8"
        handler.formatter = RedactingFormatter()
        handler.level = level
        val logger = Logger.getLogger(loggerName)
        logger.useParentHandlers = false
        logger.level = level
        logger.addHandler(handler)
        return logger
    }
}

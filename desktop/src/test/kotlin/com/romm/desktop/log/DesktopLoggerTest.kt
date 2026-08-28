/*
 * Copyright (c) 2025 Romm Android TV contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.romm.desktop.log

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level

class DesktopLoggerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun activeLog(tempDir: Path): Path? = Files.list(tempDir)
        .filter { it.fileName.toString().matches(Regex("romm-desktop\\.log(\\.\\d+)?\$")) }
        .filter { !it.fileName.toString().endsWith(".lck") }
        .toList()
        .maxByOrNull { Files.size(it) }

    /**
     * Validate: (1) a log file exists under tempDir, (2) a secret-looking message is redacted in the
     * file, (3) rotation stays bounded when many records exceed the configured small max-bytes.
     */
    @Test
    fun `rotation test`() {
        val maxBytes = 2 * 1024  // 2 KB
        val backupCount = 2

        val logFile = tempDir.resolve("romm-desktop.log")
        val logger = DesktopLoggerTestHelper.createLogger(
            logFile = logFile,
            maxBytes = maxBytes,
            backupCount = backupCount,
            level = Level.INFO,
            loggerName = "com.romm.desktop.test.rotation",
        )

        // Use \$ to embed literal dollar signs inside an interpolated string.
        val secret = "Bearer a]b\$c#d\$e%f^g&h*i(j)k+l=m"
        repeat(500) { logger.info("auth log #$it: $secret") }

        closeAndFlush(logger)

        // Find the actual active log file (JDK quirk: with backupCount>1 the active file
        // is `romm-desktop.log.N` where N is the highest index, not `romm-desktop.log`).
        val foundFile = activeLog(tempDir)
        assertThat(foundFile).isNotNull

        val activeContent = Files.readString(foundFile!!)
        assertThat(activeContent).doesNotContain(secret)
        // But it should contain the redacted form.
        assertThat(activeContent).contains("Bearer [REDACTED]")

        // Rotation count must be bounded to at most [backupCount].
        val rotated = Files.list(tempDir)
            .filter { it.fileName.toString().matches(Regex("romm-desktop\\.log\\.\\d+")) }
            .count()
        assertThat(rotated).isLessThanOrEqualTo(backupCount.toLong())
    }

    @Test
    fun `non-secret messages are preserved in the log file`() {
        val logFile = tempDir.resolve("romm-desktop.log")
        val logger = DesktopLoggerTestHelper.createLogger(
            logFile = logFile,
            maxBytes = Int.MAX_VALUE,
            backupCount = 1,
            level = Level.INFO,
            loggerName = "com.romm.desktop.test.preserve",
        )

        val msg = "User alice logged in from 192.168.1.1 at 2025-01-02"
        logger.info(msg)
        closeAndFlush(logger)

        val content = Files.readString(logFile)
        assertThat(content).contains(msg)
    }

    @Test
    fun `password assignment in log message is redacted`() {
        val logFile = tempDir.resolve("romm-desktop.log")
        val logger = DesktopLoggerTestHelper.createLogger(
            logFile = logFile,
            maxBytes = Int.MAX_VALUE,
            backupCount = 1,
            level = Level.INFO,
            loggerName = "com.romm.desktop.test.password",
        )

        val secret = "password=hunter2_real_secret_value"
        logger.info("Login: $secret")
        closeAndFlush(logger)

        val content = Files.readString(logFile)
        assertThat(content).doesNotContain("hunter2_real_secret_value")
        assertThat(content).contains("password=[REDACTED]")
    }

    private fun closeAndFlush(logger: java.util.logging.Logger) {
        logger.handlers.forEach { h -> h.close() }
    }
}

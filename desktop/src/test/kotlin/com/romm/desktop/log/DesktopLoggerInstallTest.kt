/*
 * Copyright (c) 2025 Romm Android TV contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.romm.desktop.log

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.FileHandler
import java.util.logging.Level

/**
 * Covers the explicit install/reconfiguration API (plans/WINDOWS_IMPL.md §4.4): startup will
 * call [DesktopLogger.install] with `AppPaths.logsDir()` before ordinary logging; these tests
 * prove installation, re-installation, and handler hygiene without touching the real XDG dir.
 */
class DesktopLoggerInstallTest {

    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun restoreDefaultInstallation() {
        // Leave the shared JUL logger in its historical default configuration for other tests.
        DesktopLogger.newLogger()
    }

    private fun logAndFlush(logger: java.util.logging.Logger, message: String) {
        logger.log(Level.INFO, "%s", message)
        logger.handlers.filterIsInstance<FileHandler>().forEach { it.flush() }
    }

    @Test
    fun `install writes records under the installed logs dir`() {
        val logsDir = tempDir.resolve("state").resolve("logs")
        val logger = DesktopLogger.install(logsDir)

        logAndFlush(logger, "installed-record-marker")

        assertThat(Files.isDirectory(logsDir)).isTrue()
        val activeLog = Files.list(logsDir).use { stream ->
            stream.filter { it.fileName.toString().startsWith("romm-desktop.log") }
                .filter { !it.fileName.toString().endsWith(".lck") }
                .toList()
        }.maxByOrNull { Files.size(it) }!!
        assertThat(Files.readString(activeLog)).contains("installed-record-marker")
    }

    @Test
    fun `reinstall moves output to the new dir and does not accumulate handlers`() {
        val first = tempDir.resolve("first").resolve("logs")
        val second = tempDir.resolve("second").resolve("logs")
        val logger = DesktopLogger.install(first)
        logAndFlush(logger, "first-dir-marker")

        val reinstalled = DesktopLogger.install(second)
        logAndFlush(reinstalled, "second-dir-marker")

        // Exactly one file handler + the stderr console handler after each install.
        assertThat(reinstalled.handlers.count { it is FileHandler }).isEqualTo(1)

        val firstContent = Files.list(first).use { stream ->
            stream.filter { !it.fileName.toString().endsWith(".lck") }.map { Files.readString(it) }.toList().joinToString()
        }
        val secondContent = Files.list(second).use { stream ->
            stream.filter { !it.fileName.toString().endsWith(".lck") }.map { Files.readString(it) }.toList().joinToString()
        }
        assertThat(firstContent).contains("first-dir-marker").doesNotContain("second-dir-marker")
        assertThat(secondContent).contains("second-dir-marker").doesNotContain("first-dir-marker")
    }

    /**
     * Regression for the real startup order in `Main`: `DesktopLogger.install(appPaths)` runs
     * BEFORE the first `DesktopLogger.get()`. Install-before-first-get must set/replace the
     * Holder instance so the first `get()` serves the installed logger writing under the
     * AppPaths log directory — not a stale default-directory logger.
     */
    @Test
    fun `install before first get serves the installed logger - real startup order`() {
        val logsDir = tempDir.resolve("startup").resolve("logs")
        // Main's order: install first, no get() before it.
        val installed = DesktopLogger.install(logsDir)

        val served = DesktopLogger.get()
        assertThat(served).isSameAs(installed)
        // Exactly one file handler bound to the installed directory (no accumulation).
        assertThat(served.handlers.filterIsInstance<FileHandler>()).hasSize(1)

        logAndFlush(served, "startup-order-marker")

        val content = Files.list(logsDir).use { stream ->
            stream.filter { !it.fileName.toString().endsWith(".lck") }.map { Files.readString(it) }.toList().joinToString()
        }
        assertThat(content).contains("startup-order-marker")
    }

    @Test
    fun `get still serves the shared logger after installation`() {
        // Force singleton initialization first so [install] reconfigures the same global JUL
        // logger that [DesktopLogger.get] will hand out.
        val shared = DesktopLogger.get()
        val logsDir = tempDir.resolve("shared").resolve("logs")
        DesktopLogger.install(logsDir)

        assertThat(DesktopLogger.get()).isSameAs(shared)
        logAndFlush(shared, "shared-logger-marker")

        val content = Files.list(logsDir).use { stream ->
            stream.filter { !it.fileName.toString().endsWith(".lck") }.map { Files.readString(it) }.toList().joinToString()
        }
        assertThat(content).contains("shared-logger-marker")
    }
}

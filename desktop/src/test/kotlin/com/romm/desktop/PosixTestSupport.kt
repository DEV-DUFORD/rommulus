package com.romm.desktop

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path

/**
 * Host/filesystem guards for POSIX-specific desktop tests (plans/WINDOWS_IMPL.md §4.7):
 * POSIX permission assertions and execute-bit mechanics are conditioned on filesystem/host
 * support so the same suite is NTFS-safe when it runs as the Windows CI gate, while still
 * executing fully on Linux and macOS development hosts.
 */
object PosixTestSupport {

    /** True when the filesystem hosting [path] supports POSIX file attribute views. */
    fun isPosixFilesystem(path: Path): Boolean = try {
        Files.getFileStore(path).supportsFileAttributeView("posix")
    } catch (_: Exception) {
        false
    }

    /** Skips the current test when [path]'s filesystem has no POSIX permission bits (e.g. NTFS). */
    fun assumePosixFilesystem(path: Path) {
        assumeTrue(
            isPosixFilesystem(path),
            "POSIX file attributes are not supported on this filesystem",
        )
    }

    /** True when the JVM runs on a Unix-like host (Linux/macOS). */
    fun isUnixLikeHost(): Boolean {
        val os = (System.getProperty("os.name") ?: "").lowercase()
        return os.contains("linux") || os.contains("mac") || os.contains("darwin") || os.contains("os x")
    }

    /** Skips the current test when it requires Unix-only mechanics (shell scripts, execute bits). */
    fun assumeUnixLikeHost() {
        assumeTrue(isUnixLikeHost(), "test requires a Unix-like host (shell scripts / execute bits)")
    }
}

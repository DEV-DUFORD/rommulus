package com.romm.desktop.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.TimeUnit

/**
 * Two-process `FileChannel.tryLock` harness (plans/WINDOWS_IMPL.md §4.5,
 * .slim/deepwork/windows-phase-0.md Phase 1 lane 3). Proves, with a real second JVM process:
 *
 * 1. **rejection** — while the child holds the advisory lock, both a raw `FileChannel.tryLock`
 *    and the production [FileLockAppInstanceLock] are rejected in this process;
 * 2. **crash release** — after the child is KILLED (no graceful close), the OS releases the
 *    advisory lock and a fresh [FileLockAppInstanceLock] acquires it.
 *
 * This is the host-native confirmation for NTFS on `windows-2022`; it also runs on macOS/Linux
 * development hosts, where the same JDK mechanism (fcntl/flock) is equally real. Stability gates:
 * the test skips when no `java` executable exists under `java.home`, uses generous timeouts for
 * JVM startup and lock release, and always destroys the child in `finally`.
 */
class FileLockCrossProcessTest {

    @TempDir
    lateinit var tempDir: Path

    private fun javaExecutable(): Path? {
        val javaHome = System.getProperty("java.home") ?: return null
        // `java` on Unix, `java.exe` on Windows — without the `.exe` candidate the host-native
        // NTFS confirmation would silently skip on windows-2022.
        return listOf("java", "java.exe")
            .map { Path.of(javaHome, "bin", it) }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
    }

    @Test
    fun `second process is rejected and a crashed holder releases the lock`() {
        val javaExe = javaExecutable()
        assumeTrue(
            javaExe != null,
            "no java executable under java.home; skipping two-process lock test",
        )
        val lockFile = tempDir.resolve(FileLockAppInstanceLock.LOCK_FILE_NAME)
        val readyFile = tempDir.resolve("holder-ready")

        val child = ProcessBuilder(
            javaExe.toString(),
            "-cp",
            System.getProperty("java.class.path"),
            CrossProcessLockHolder::class.java.name,
            lockFile.toString(),
            readyFile.toString(),
        ).redirectErrorStream(true).start()

        try {
            awaitReady(child, readyFile)

            // 1. OS-level cross-process rejection: a fresh channel in this JVM cannot lock.
            FileChannel.open(lockFile, READ, WRITE).use { channel ->
                assertThat(channel.tryLock()).isNull()
            }

            // 2. The production class observes the same rejection (cross-process path).
            assertThat(FileLockAppInstanceLock(lockFile).acquire()).isFalse()

            // 3. Crash: kill the holder without letting it close its channel.
            child.destroyForcibly()
            assumeTrue(
                child.waitFor(CRASH_SETTLE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "holder process did not terminate after destroyForcibly",
            )

            // 4. The OS releases advisory locks when the owning process dies (crash release).
            assertThat(awaitLockReleased(lockFile)).isTrue()

            // 5. A fresh production instance can now acquire (the stale lock FILE is no wedge).
            val afterCrash = FileLockAppInstanceLock(lockFile)
            assertThat(afterCrash.acquire()).isTrue()
            afterCrash.release()
        } finally {
            if (child.isAlive) child.destroyForcibly()
        }
    }

    /** Waits for the child to signal it holds the lock; fails with its output if it dies early. */
    private fun awaitReady(child: Process, readyFile: Path) {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(readyFile)) return
            if (!child.isAlive) {
                val output = child.inputStream.bufferedReader().readText()
                throw AssertionError("holder exited early (code ${child.exitValue()}): $output")
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("holder did not signal readiness within ${READY_TIMEOUT_MS}ms")
    }

    /** Polls until a raw channel can take the lock; returns whether release was observed. */
    private fun awaitLockReleased(lockFile: Path): Boolean {
        val deadline = System.currentTimeMillis() + RELEASE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            FileChannel.open(lockFile, READ, WRITE).use { channel ->
                channel.tryLock()?.let { lock ->
                    lock.release()
                    return true
                }
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    private companion object {
        const val READY_TIMEOUT_MS = 30_000L
        const val RELEASE_TIMEOUT_MS = 30_000L
        const val CRASH_SETTLE_TIMEOUT_SECONDS = 15L
        const val POLL_INTERVAL_MS = 100L
    }
}

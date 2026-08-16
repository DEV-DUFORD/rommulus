package com.romm.desktop.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

class FileLockAppInstanceLockTest {

    @TempDir
    lateinit var tempDir: Path

    /** Unique lock path per test method so the in-JVM registry never collides. */
    private fun lockFile(dir: String): Path =
        tempDir.resolve(dir).resolve(FileLockAppInstanceLock.LOCK_FILE_NAME)

    private fun posixSupported(): Boolean = try {
        val probe = Files.createTempDirectory("posix_probe")
        Files.setPosixFilePermissions(probe, setOf(PosixFilePermission.OWNER_READ))
        Files.delete(probe)
        true
    } catch (_: Exception) {
        false
    }

    @Test
    fun `second acquire on the same instance returns false`() {
        val lock = FileLockAppInstanceLock(lockFile("t1"))

        assertThat(lock.isHeld()).isFalse()
        assertThat(lock.acquire()).isTrue()
        assertThat(lock.isHeld()).isTrue()

        // Non-reentrant per the port's documented semantics.
        assertThat(lock.acquire()).isFalse()
        assertThat(lock.isHeld()).isTrue()

        lock.release()
    }

    @Test
    fun `a second instance cannot acquire while the first holds the lock`() {
        val path = lockFile("t2")
        val first = FileLockAppInstanceLock(path)
        val second = FileLockAppInstanceLock(path)

        assertThat(first.acquire()).isTrue()

        assertThat(second.acquire()).isFalse()
        assertThat(second.isHeld()).isFalse()
        // Rejected acquisition must not disturb the current holder.
        assertThat(first.isHeld()).isTrue()

        first.release()
        assertThat(second.isHeld()).isFalse()
    }

    @Test
    fun `release then re-acquire works, and another instance can take over`() {
        val path = lockFile("t3")
        val first = FileLockAppInstanceLock(path)
        val second = FileLockAppInstanceLock(path)

        assertThat(first.acquire()).isTrue()
        assertThat(second.acquire()).isFalse()

        first.release()
        assertThat(first.isHeld()).isFalse()

        assertThat(first.acquire()).isTrue()
        first.release()

        assertThat(second.acquire()).isTrue()
        assertThat(second.isHeld()).isTrue()
    }

    @Test
    fun `isHeld tracks acquire and release`() {
        val lock = FileLockAppInstanceLock(lockFile("t4"))

        assertThat(lock.isHeld()).isFalse()
        assertThat(lock.acquire()).isTrue()
        assertThat(lock.isHeld()).isTrue()

        lock.release()
        assertThat(lock.isHeld()).isFalse()

        // Releasing an unheld lock is a no-op, not an error.
        lock.release()
        assertThat(lock.isHeld()).isFalse()
    }

    @Test
    fun `lock file and directory are created with user-only permissions`() {
        if (!posixSupported()) return

        val dir = tempDir.resolve("t5")
        val path = dir.resolve(FileLockAppInstanceLock.LOCK_FILE_NAME)
        val lock = FileLockAppInstanceLock(path)

        assertThat(lock.acquire()).isTrue()

        assertThat(Files.exists(path)).isTrue()
        // 0600: owner read/write only.
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(path)))
            .isEqualTo("rw-------")
        // 0700: owner-only directory.
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
            .isEqualTo("rwx------")
    }

    @Test
    fun `acquire fails closed when the lock file cannot be created`() {
        // Simulate an unusable target (read-only fs / permission denied) in a
        // platform-independent way: occupy the lock path with a *directory*,
        // so creating the lock file fails deterministically (EISDIR) and the
        // same fail-closed branch as a real permission error is exercised.
        val path = lockFile("t6")
        Files.createDirectories(path)

        val lock = FileLockAppInstanceLock(path)

        assertThat(lock.acquire()).isFalse()
        assertThat(lock.isHeld()).isFalse()
        // A second invocation observes the same fail-closed behavior.
        assertThat(FileLockAppInstanceLock(path).acquire()).isFalse()
    }

    @Test
    fun `secondary constructor prefers an existing runtime directory, else the state directory`() {
        val runtime = tempDir.resolve("runtime")
        val state = tempDir.resolve("state")

        // Runtime dir does not exist yet → fall back to the state dir.
        assertThat(FileLockAppInstanceLock(runtime, state).lockFile)
            .isEqualTo(state.resolve(FileLockAppInstanceLock.LOCK_FILE_NAME))

        Files.createDirectories(runtime)
        assertThat(FileLockAppInstanceLock(runtime, state).lockFile)
            .isEqualTo(runtime.resolve(FileLockAppInstanceLock.LOCK_FILE_NAME))

        // Null runtime dir (unset $XDG_RUNTIME_DIR) → state dir.
        assertThat(FileLockAppInstanceLock(null, state).lockFile)
            .isEqualTo(state.resolve(FileLockAppInstanceLock.LOCK_FILE_NAME))
    }
}

package com.romm.androidtv.storage.android

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FileLockAppInstanceLockTest {

    @TempDir
    lateinit var tempDir: Path

    private fun lockFile(): java.io.File = tempDir.resolve("instance.lock").toFile()

    @Test
    fun `acquire succeeds once and isHeld reflects it`() {
        val lock = FileLockAppInstanceLock(lockFile())
        assertThat(lock.acquire()).isTrue()
        assertThat(lock.isHeld()).isTrue()
    }

    @Test
    fun `second instance on the same file fails to acquire`() {
        val first = FileLockAppInstanceLock(lockFile())
        val second = FileLockAppInstanceLock(lockFile())
        assertThat(first.acquire()).isTrue()
        assertThat(second.acquire()).isFalse()
        assertThat(second.isHeld()).isFalse()
    }

    @Test
    fun `release then re-acquire works`() {
        val lock = FileLockAppInstanceLock(lockFile())
        assertThat(lock.acquire()).isTrue()
        lock.release()
        assertThat(lock.isHeld()).isFalse()
        assertThat(lock.acquire()).isTrue()
        assertThat(lock.isHeld()).isTrue()
    }

    @Test
    fun `acquire is idempotent while held`() {
        val lock = FileLockAppInstanceLock(lockFile())
        assertThat(lock.acquire()).isTrue()
        assertThat(lock.acquire()).isTrue()
        assertThat(lock.isHeld()).isTrue()
    }
}

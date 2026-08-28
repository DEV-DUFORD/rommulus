package com.romm.androidtv.storage.android

import com.romm.androidtv.storage.ports.AppInstanceLock
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

/**
 * Thin adapter: implements [AppInstanceLock] with an OS-level advisory file lock.
 *
 * Uses a [FileChannel] + [FileLock] on [lockFile] (created if absent).
 * [acquire] calls `tryLock()` which succeeds only if no other JVM/process holds
 * the lock, returning false on `null` or [OverlappingFileLockException]
 * (a lock already held in this process).
 *
 * `java.nio` file locks are advisory but OS-enforced, so they work both intra-
 * process (via the OverlappingFileLockException) and inter-process (via the OS),
 * which is exactly the single-instance guarantee required.
 *
 * JVM-testable because it takes a plain [File]; the caller will later supply
 * `File(context.filesDir, "instance.lock")`.
 */
class FileLockAppInstanceLock(private val lockFile: File) : AppInstanceLock {

    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    override fun acquire(): Boolean {
        if (isHeld()) return true
        return runCatching {
            val ch = FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            val acquired = try {
                ch.tryLock()
            } catch (_: OverlappingFileLockException) {
                // Another instance in this JVM already holds the lock.
                null
            }
            if (acquired != null) {
                channel = ch
                lock = acquired
                true
            } else {
                runCatching { ch.close() }
                false
            }
        }.getOrDefault(false)
    }

    override fun release() {
        runCatching { lock?.release() }
        lock = null
        runCatching { channel?.close() }
        channel = null
    }

    override fun isHeld(): Boolean = lock?.isValid() == true
}

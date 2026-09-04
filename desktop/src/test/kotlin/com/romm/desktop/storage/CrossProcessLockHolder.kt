package com.romm.desktop.storage

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

/**
 * Child-process side of [FileLockCrossProcessTest] (plans/WINDOWS_IMPL.md §4.5).
 *
 * Invoked by the test as a separate JVM: `java -cp <test classpath>
 * com.romm.desktop.storage.CrossProcessLockHolder <lockFile> <readyFile>`. It opens the lock
 * file, takes the advisory lock, signals readiness by writing [readyFile], and then parks until
 * the parent KILLS it — a kill (not a graceful close) is what makes this a crash-release proof.
 * Exit codes: 0 = never expected (killed first); 3 = the lock was already contested at start.
 *
 * Deliberately depends on nothing but the JDK so the child JVM needs no project dependencies.
 */
object CrossProcessLockHolder {

    @JvmStatic
    fun main(args: Array<String>) {
        val lockFile = Path.of(args[0])
        val readyFile = Path.of(args[1])
        val channel = FileChannel.open(lockFile, READ, WRITE, CREATE)
        if (channel.tryLock() == null) {
            println("LOCK-CONTESTED")
            kotlin.system.exitProcess(3)
        }
        Files.writeString(readyFile, "HELD")
        // Hold until the parent destroys this process; never close [channel] voluntarily.
        while (true) {
            Thread.sleep(1_000)
        }
    }
}

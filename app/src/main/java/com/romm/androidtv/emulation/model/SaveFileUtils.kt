package com.romm.androidtv.emulation.model

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Pure filesystem utility for durable, atomic save-file operations.
 * Shared between [com.romm.androidtv.romm.save.FileSaveContentStore] (main process)
 * and [EmulationSaveBackupStore] (emulation process) to eliminate duplicated
 * write-temp / fsync / atomic-rename logic.
 *
 * All methods are synchronous, IO-bound, and should be called from Dispatchers.IO.
 * No Android or Room dependencies — usable in JVM tests with a temp directory.
 */
object SaveFileUtils {

    /**
     * Atomically writes [bytes] to [target] using write-temp / fsync / rename.
     * Never leaves a partially written file at the final path.
     */
    fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")

        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }

        if (!temp.renameTo(target)) {
            // Fallback: copy + delete if atomic rename isn't available.
            target.parentFile?.mkdirs()
            RandomAccessFile(target, "rw").use { raf ->
                raf.setLength(0)
                raf.write(temp.readBytes())
                raf.fd.sync()
            }
            temp.delete()
        }
    }

    /**
     * Durably copies [source] to a new file at [destination].
     * Verifies content integrity by reading back and comparing.
     */
    fun copyWithVerification(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        source.copyTo(destination, overwrite = false)

        // Verify durability: read back and compare.
        val backupBytes = destination.readBytes()
        val originalBytes = source.readBytes()
        if (!backupBytes.contentEquals(originalBytes)) {
            destination.delete()
            throw IOException("Backup verification failed: content mismatch")
        }
    }

    /**
     * Writes [bytes] to a quarantine file under the given directory.
     * Returns the absolute path of the written file.
     */
    fun writeQuarantineFile(
        quarantineDir: File,
        bytes: ByteArray,
        fileName: String,
    ): String {
        quarantineDir.mkdirs()
        val quarantineFile = File(quarantineDir, fileName)

        FileOutputStream(quarantineFile).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }

        return quarantineFile.absolutePath
    }

    /**
     * Sanitizes a string for use in file names: replaces non-alphanumeric characters with underscores.
     */
    fun sanitizeFileName(input: String): String =
        input.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
}

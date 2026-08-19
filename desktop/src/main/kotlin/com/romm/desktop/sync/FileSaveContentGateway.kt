package com.romm.desktop.sync

import com.romm.androidtv.emulation.model.SavePathPolicy
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.WRITE

/**
 * Real [SaveContentGateway] rooted at the desktop app's data directory, using
 * [SavePathPolicy] for the autosave path (plans/LINUX_X64.md — Phase 9 drain executor).
 *
 * Mirrors Android's `FileSaveContentStore` semantics and reuses the desktop atomic-write pattern
 * from LaunchJournalSupervisor/SecureFiles: temp file in the target directory + fsync
 * (`FileChannel.force(true)`) + `ATOMIC_MOVE` rename, so a crash never leaves a half-written
 * save at the canonical path.
 */
class FileSaveContentGateway(private val filesDir: File) : SaveContentGateway {

    override fun readLocal(serverKey: String, userKey: String, romId: Long, romHash: String, slot: String): ByteArray? {
        val file = Path.of(autosavePath(serverKey, userKey, romId, romHash, slot))
        return if (Files.isRegularFile(file)) Files.readAllBytes(file) else null
    }

    override fun writeLocalAtomically(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
        bytes: ByteArray,
    ) {
        val target = Path.of(autosavePath(serverKey, userKey, romId, romHash, slot))
        val dir = checkNotNull(target.parent) { "target has no parent directory: $target" }
        Files.createDirectories(dir)
        // Same pattern as LaunchJournalSupervisor.adoptFile: temp in the TARGET directory (so the
        // rename is on one filesystem), fsync, then atomic replace.
        val temp = Files.createTempFile(dir, ".save-", "tmp")
        try {
            FileChannel.open(temp, WRITE).use { out ->
                out.write(ByteBuffer.wrap(bytes))
                out.force(true)
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(temp) }
            throw e
        }
    }

    override fun quarantine(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
        bytes: ByteArray,
        reason: String,
        nowEpochMs: Long,
    ): String {
        val sanitizedReason = reason.map { c -> if (c.isLetterOrDigit()) c else '_' }.joinToString("")
        // Same layout as Android's FileSaveContentStore.quarantine: a sibling "quarantine" dir
        // next to the slot directory, never inside the canonical save path.
        val quarantineDir = Path.of(autosavePath(serverKey, userKey, romId, romHash, slot))
            .parent?.parent
            ?.resolve("quarantine")
            ?: error("cannot derive quarantine directory for $serverKey/$userKey/$romId/$romHash")
        Files.createDirectories(quarantineDir)
        val quarantineFile = Files.createTempFile(quarantineDir, "$nowEpochMs-$sanitizedReason-$slot-", ".srm")
        FileChannel.open(quarantineFile, WRITE).use { out ->
            out.write(ByteBuffer.wrap(bytes))
            out.force(true)
        }
        return quarantineFile.toAbsolutePath().toString()
    }

    /**
     * [SavePathPolicy] only defines a path for [SavePathPolicy.AUTOSAVE_SLOT] — the only slot this
     * app's first release supports. Fail loudly rather than silently deriving an ad-hoc path for
     * any other slot name (same rule as Android's FileSaveContentStore).
     */
    private fun autosavePath(serverKey: String, userKey: String, romId: Long, romHash: String, slot: String): String {
        require(slot == SavePathPolicy.AUTOSAVE_SLOT) {
            "Unsupported slot '$slot' — only '${SavePathPolicy.AUTOSAVE_SLOT}' is supported today"
        }
        return SavePathPolicy.autosaveSramPath(filesDir, serverKey, userKey, romId, romHash)
    }
}

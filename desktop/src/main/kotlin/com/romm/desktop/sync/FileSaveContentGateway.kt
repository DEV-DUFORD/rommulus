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
        // Same pattern as LaunchJournalSupervisor.adoptFile: temp in the TARGET directory (so the
        // rename is on one filesystem), fsync, then atomic replace.
        writeAtomically(Path.of(autosavePath(serverKey, userKey, romId, romHash, slot)), bytes)
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

    override fun conflictBackup(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
        bytes: ByteArray,
        choice: String,
        contentHash: String,
    ): String {
        val sanitizedChoice = choice.map { c -> if (c.isLetterOrDigit()) c else '_' }.joinToString("")
        val hashPrefix = contentHash.take(16)
        // Same layout as Android's FileSaveContentStore.conflictBackup: a sibling "conflict-backups"
        // dir next to the slot directory, never inside the canonical save path. Deterministic
        // (choice + content-hash) so a retried resolution converges on one file; written atomically.
        val backupDir = Path.of(autosavePath(serverKey, userKey, romId, romHash, slot))
            .parent?.parent
            ?.resolve("conflict-backups")
            ?: error("cannot derive conflict-backup directory for $serverKey/$userKey/$romId/$romHash")
        Files.createDirectories(backupDir)
        val target = backupDir.resolve("conflict-$sanitizedChoice-$hashPrefix.srm")
        writeAtomically(target, bytes)
        return target.toAbsolutePath().toString()
    }

    /** Temp file in the target's directory + fsync + atomic rename (shared with [writeLocalAtomically]). */
    private fun writeAtomically(target: Path, bytes: ByteArray) {
        val dir = checkNotNull(target.parent) { "target has no parent directory: $target" }
        Files.createDirectories(dir)
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

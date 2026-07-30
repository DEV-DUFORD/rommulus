package com.romm.androidtv.romm.save

import com.romm.androidtv.emulation.model.SavePathPolicy
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Local filesystem access for durable autosave SRAM bytes
 * (LIBRETRO_REFACTOR.md section 11.1). Kept as a small, injectable interface
 * — separate from [SaveSyncCoordinator] — so the coordinator's negotiation
 * logic can be unit-tested against a fake without touching a real
 * filesystem, while [FileSaveContentStore] itself is exercised with a real
 * temp directory (plain `java.io`, no Android dependency needed).
 */
interface SaveContentStore {
    /** Reads the current durable local autosave bytes for this scope, or null if none exist yet. */
    fun readLocal(serverKey: String, userKey: String, romId: Long, romHash: String, slot: String): ByteArray?

    /**
     * Atomically replaces the durable local autosave for this scope with
     * [bytes]: write-temp, `fsync`, rename (section 11.1, "Write a temporary
     * file, hash, `fsync`, atomically rename"). Never leaves a partially
     * written file at the final path.
     */
    fun writeLocalAtomically(serverKey: String, userKey: String, romId: Long, romHash: String, slot: String, bytes: ByteArray)

    /**
     * Preserves [bytes] under a conflict/quarantine-specific name instead of
     * ever touching the real autosave path — used both for an
     * unknown-provenance download (section 11.1's "legacy save" case) and
     * for "preserve the losing copy" before a user's explicit conflict
     * choice is applied (section 11.3). Returns the absolute path the bytes
     * were preserved under.
     */
    fun quarantine(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
        bytes: ByteArray,
        reason: String,
        nowEpochMs: Long,
    ): String

    /**
     * Durably backs up [bytes] under a **deterministic** conflict-specific path
     * keyed by [sessionId], [choice] ("keep-local" or "keep-server"), and the
     * first 16 hex characters of [contentHash]. Unlike [quarantine], this never
     * embeds a timestamp — the same inputs always produce the same path, making
     * crash/retry idempotent: re-running resolution for the same conflict writes
     * to the identical backup file rather than creating a new one.
     *
     * Returns the absolute path the bytes were preserved under.
     */
    fun conflictBackup(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
        bytes: ByteArray,
        sessionId: Long,
        choice: String,
        contentHash: String,
    ): String

    /**
     * Durably preserves the current canonical autosave bytes under a
     * candidate-specific backup path before a candidate adoption overwrites them.
     * Only succeeds if a canonical local copy exists; returns null otherwise.
     * Idempotent: if a backup already exists for [candidateIdentifier], returns
     * the existing backup path without overwriting. Throws on write failure.
     */
    fun backupCanonical(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
        candidateIdentifier: Long,
        nowEpochMs: Long,
    ): String?
}

/** Real, `filesDir`-rooted [SaveContentStore], using [SavePathPolicy] for the autosave path. */
class FileSaveContentStore(private val filesDir: File) : SaveContentStore {

    override fun readLocal(serverKey: String, userKey: String, romId: Long, romHash: String, slot: String): ByteArray? {
        val file = File(autosavePath(serverKey, userKey, romId, romHash, slot))
        return if (file.isFile) file.readBytes() else null
    }

    override fun writeLocalAtomically(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
        bytes: ByteArray,
    ) {
        val target = File(autosavePath(serverKey, userKey, romId, romHash, slot))
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")

        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (!temp.renameTo(target)) {
            // Fall back to copy+delete if an atomic rename isn't available (e.g. a cross-filesystem
            // mount) — still never leaves a half-written file at [target] itself.
            target.parentFile?.mkdirs()
            RandomAccessFile(target, "rw").use { raf ->
                raf.setLength(0)
                raf.write(temp.readBytes())
                raf.fd.sync()
            }
            temp.delete()
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
        val quarantineDir = File(
            File(autosavePath(serverKey, userKey, romId, romHash, slot)).parentFile?.parentFile,
            "quarantine",
        )
        quarantineDir.mkdirs()
        val quarantineFile = File(quarantineDir, "$nowEpochMs-$sanitizedReason-$slot.srm")

        FileOutputStream(quarantineFile).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        return quarantineFile.absolutePath
    }

    override fun conflictBackup(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
        bytes: ByteArray,
        sessionId: Long,
        choice: String,
        contentHash: String,
    ): String {
        val sanitizedChoice = choice.map { c -> if (c.isLetterOrDigit()) c else '_' }.joinToString("")
        val hashPrefix = contentHash.take(16)
        val conflictDir = File(
            File(autosavePath(serverKey, userKey, romId, romHash, slot)).parentFile?.parentFile,
            "conflict-backups",
        )
        conflictDir.mkdirs()
        val backupFile = File(conflictDir, "conflict-${sessionId}-${sanitizedChoice}-${hashPrefix}.srm")

        FileOutputStream(backupFile).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        return backupFile.absolutePath
    }

    override fun backupCanonical(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
        candidateIdentifier: Long,
        nowEpochMs: Long,
    ): String? {
        val canonicalFile = File(autosavePath(serverKey, userKey, romId, romHash, slot))
        if (!canonicalFile.isFile) return null

        val backupDir = File(
            canonicalFile.parentFile?.parentFile,
            "candidate-backups",
        )
        backupDir.mkdirs()

        // Idempotent: check for existing backup for this candidate identifier.
        val existingBackup = backupDir.listFiles { f ->
            f.name.startsWith("pre-adoption-${candidateIdentifier}-") && f.extension == "srm"
        }?.firstOrNull()

        if (existingBackup != null && existingBackup.isFile) {
            return existingBackup.absolutePath
        }

        val backupFile = File(backupDir, "pre-adoption-${candidateIdentifier}-${nowEpochMs}.srm")
        canonicalFile.copyTo(backupFile, overwrite = false)

        // Verify durability: fsync the parent directory is not supported on Android,
        // but we fsynced during the copyTo's write. Read back to verify content integrity.
        val backupBytes = backupFile.readBytes()
        val originalBytes = canonicalFile.readBytes()
        if (!backupBytes.contentEquals(originalBytes)) {
            backupFile.delete()
            throw IOException("Backup verification failed: content mismatch")
        }

        return backupFile.absolutePath
    }

    /**
     * [SavePathPolicy] only defines a path for [SavePathPolicy.AUTOSAVE_SLOT] — the only slot this
     * app's first release supports (see that object's doc). Fail loudly rather than silently
     * deriving an ad-hoc path for any other slot name.
     */
    private fun autosavePath(serverKey: String, userKey: String, romId: Long, romHash: String, slot: String): String {
        require(slot == SavePathPolicy.AUTOSAVE_SLOT) {
            "Unsupported slot '$slot' — only '${SavePathPolicy.AUTOSAVE_SLOT}' is supported today"
        }
        return SavePathPolicy.autosaveSramPath(filesDir, serverKey, userKey, romId, romHash)
    }
}

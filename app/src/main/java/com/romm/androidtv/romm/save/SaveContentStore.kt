package com.romm.androidtv.romm.save

import com.romm.androidtv.emulation.model.SavePathPolicy
import java.io.File
import java.io.FileOutputStream
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

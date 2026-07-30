package com.romm.androidtv.romm.save

/**
 * In-memory [SaveContentStore] fake for unit-testing [SaveSyncCoordinatorImpl]
 * without touching a real filesystem. [quarantinedFiles] records every
 * [quarantine] call so tests can assert a bad download was preserved rather
 * than silently dropped.
 */
class FakeSaveContentStore : SaveContentStore {
    private val files = mutableMapOf<String, ByteArray>()
    val quarantinedFiles = mutableListOf<Pair<String, ByteArray>>()
    val conflictBackups = mutableListOf<Pair<String, ByteArray>>()

    private fun key(serverKey: String, userKey: String, romId: Long, romHash: String, slot: String) =
        "$serverKey|$userKey|$romId|$romHash|$slot"

    fun seedLocal(serverKey: String, userKey: String, romId: Long, romHash: String, slot: String, bytes: ByteArray) {
        files[key(serverKey, userKey, romId, romHash, slot)] = bytes
    }

    override fun readLocal(serverKey: String, userKey: String, romId: Long, romHash: String, slot: String): ByteArray? =
        files[key(serverKey, userKey, romId, romHash, slot)]

    override fun writeLocalAtomically(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
        bytes: ByteArray,
    ) {
        files[key(serverKey, userKey, romId, romHash, slot)] = bytes
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
        val path = "quarantine/${key(serverKey, userKey, romId, romHash, slot)}/$nowEpochMs-$reason"
        quarantinedFiles.add(path to bytes)
        return path
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
        val hashPrefix = contentHash.take(16)
        val path = "conflict-backups/conflict-${sessionId}-${choice}-${hashPrefix}.srm"
        conflictBackups.add(path to bytes)
        return path
    }

    /** Read quarantined bytes by the path returned from [quarantine]. Returns null if not found. */
    fun readQuarantined(path: String): ByteArray? =
        quarantinedFiles.find { it.first == path }?.second

    /** Read conflict-backup bytes by the path returned from [conflictBackup]. Returns null if not found. */
    fun readConflictBackup(path: String): ByteArray? =
        conflictBackups.find { it.first == path }?.second

    private val canonicalBackups = mutableMapOf<Long, Pair<String, ByteArray>>()

    override fun backupCanonical(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
        candidateIdentifier: Long,
        nowEpochMs: Long,
    ): String? {
        val canonicalBytes = files[key(serverKey, userKey, romId, romHash, slot)] ?: return null

        // Idempotent: reuse existing backup for this candidate identifier.
        val existing = canonicalBackups[candidateIdentifier]
        if (existing != null) return existing.first

        val path = "candidate-backup/${key(serverKey, userKey, romId, romHash, slot)}/pre-adoption-${candidateIdentifier}-$nowEpochMs"
        canonicalBackups[candidateIdentifier] = path to canonicalBytes.copyOf()
        return path
    }

    /** Read canonical backup bytes by candidate identifier. Returns null if not backed up. */
    fun readCanonicalBackup(candidateIdentifier: Long): ByteArray? =
        canonicalBackups[candidateIdentifier]?.second
}

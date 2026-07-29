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
}

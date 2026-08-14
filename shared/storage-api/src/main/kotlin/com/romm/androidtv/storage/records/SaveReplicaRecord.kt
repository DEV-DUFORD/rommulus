package com.romm.androidtv.storage.records

/**
 * One durable local save-replica record, persistence-neutral mirror of the
 * Android Room schema (SaveReplicaEntity). Keyed by serverKey/userKey/romId/romHash/slot.
 */
data class SaveReplicaRecord(
    val id: Long? = null,
    val serverKey: String,
    val userKey: String,
    val romId: Long,
    val romHash: String,
    val slot: String,
    val coreId: String,
    val coreBuildRevision: String,
    val expectedSramSizeBytes: Long? = null,
    val localHash: String? = null,
    val localSizeBytes: Long? = null,
    val localWrittenAtEpochMs: Long? = null,
    val rommSaveId: Long? = null,
    val serverHash: String? = null,
    val serverSizeBytes: Long? = null,
    val serverUpdatedAtEpochMs: Long? = null,
    val syncStatus: SaveSyncStatus = SaveSyncStatus.UNSYNCED,
    val lastError: String? = null,
)

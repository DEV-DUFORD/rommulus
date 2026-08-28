package com.romm.androidtv.storage.ports

import com.romm.androidtv.storage.records.SaveReplicaRecord
import com.romm.androidtv.storage.records.SaveSyncStatus

/** Composite key identifying a single save replica. */
data class SaveReplicaScope(
    val serverKey: String,
    val userKey: String,
    val romId: Long,
    val romHash: String,
    val slot: String,
)

/** Persistence-neutral store for save replicas. */
interface SaveReplicaStore {
    fun upsert(replica: SaveReplicaRecord): Result<Long>
    fun findByScope(scope: SaveReplicaScope): SaveReplicaRecord?
    fun findByStatus(serverKey: String, userKey: String, status: SaveSyncStatus): List<SaveReplicaRecord>
    fun markSyncedIfGenerationMatches(
        scope: SaveReplicaScope,
        localGenerationEpochMs: Long,
        rommSaveId: Long?,
        serverHash: String?,
        serverSizeBytes: Long?,
        serverUpdatedAtEpochMs: Long?,
    ): Boolean
    fun deleteByScope(scope: SaveReplicaScope): Result<Unit>
}

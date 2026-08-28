/*
 * Thin Android adapter: implements [SaveReplicaStore] over [SaveReplicaDao].
 * Delegates every call; never reimplements persistence logic.
 */
package com.romm.androidtv.storage.android

import com.romm.androidtv.romm.save.SaveReplicaDao
import com.romm.androidtv.storage.ports.SaveReplicaScope
import com.romm.androidtv.storage.ports.SaveReplicaStore
import com.romm.androidtv.storage.records.SaveSyncStatus
import kotlinx.coroutines.runBlocking

/**
 * Android adapter that bridges the synchronous [SaveReplicaStore] port to the
 * suspend-based Room [SaveReplicaDao].
 *
 * All DAO calls are wrapped in [runBlocking] because the port API is synchronous
 * while Room DAOs are suspend.
 */
class RoomSaveReplicaStore(
    private val dao: SaveReplicaDao,
) : SaveReplicaStore {

    override fun upsert(replica: com.romm.androidtv.storage.records.SaveReplicaRecord): Result<Long> =
        runCatching {
            // ports are synchronous; Room DAO is suspend
            runBlocking { dao.upsert(replica.toEntity()) }
        }

    override fun findByScope(scope: SaveReplicaScope): com.romm.androidtv.storage.records.SaveReplicaRecord? =
        // ports are synchronous; Room DAO is suspend
        runBlocking { dao.findByScope(scope.serverKey, scope.userKey, scope.romId, scope.romHash, scope.slot) }
            ?.toRecord()

    override fun findByStatus(
        serverKey: String,
        userKey: String,
        status: SaveSyncStatus,
    ): List<com.romm.androidtv.storage.records.SaveReplicaRecord> =
        // ports are synchronous; Room DAO is suspend
        runBlocking { dao.findByStatus(serverKey, userKey, status.androidValue) }
            .map { it.toRecord() }

    override fun markSyncedIfGenerationMatches(
        scope: SaveReplicaScope,
        localGenerationEpochMs: Long,
        rommSaveId: Long?,
        serverHash: String?,
        serverSizeBytes: Long?,
        serverUpdatedAtEpochMs: Long?,
    ): Boolean =
        // ports are synchronous; Room DAO is suspend
        // Preserves the DAO's exact generation-match SQL semantics:
        // WHERE localWrittenAtEpochMs = :localGenerationEpochMs
        runBlocking {
            dao.markSyncedIfGenerationMatches(
                serverKey = scope.serverKey,
                userKey = scope.userKey,
                romId = scope.romId,
                romHash = scope.romHash,
                slot = scope.slot,
                localGenerationEpochMs = localGenerationEpochMs,
                rommSaveId = rommSaveId,
                serverHash = serverHash,
                serverSizeBytes = serverSizeBytes,
                serverUpdatedAtEpochMs = serverUpdatedAtEpochMs,
            ) > 0
        }

    override fun deleteByScope(scope: SaveReplicaScope): Result<Unit> =
        runCatching {
            // ports are synchronous; Room DAO is suspend
            runBlocking {
                dao.deleteByScope(
                    serverKey = scope.serverKey,
                    userKey = scope.userKey,
                    romId = scope.romId,
                    romHash = scope.romHash,
                    slot = scope.slot,
                )
            }
        }
}

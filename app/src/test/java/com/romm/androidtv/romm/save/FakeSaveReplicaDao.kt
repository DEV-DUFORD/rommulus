package com.romm.androidtv.romm.save

/**
 * In-memory [SaveReplicaDao] fake for unit-testing [SaveSyncCoordinatorImpl]
 * without a real SQLite database — mirrors [SaveReplicaDao]'s SQL semantics
 * (unique per-scope row, replace-on-upsert) closely enough for coordinator
 * logic tests. [SaveReplicaDaoInstrumentedTest] (`app/src/androidTest`) is
 * still what verifies the real Room-backed behavior.
 */
class FakeSaveReplicaDao : SaveReplicaDao {
    private val rows = mutableListOf<SaveReplicaEntity>()
    private var nextId = 1L

    private fun scopeMatches(row: SaveReplicaEntity, serverKey: String, userKey: String, romId: Long, romHash: String, slot: String) =
        row.serverKey == serverKey && row.userKey == userKey && row.romId == romId && row.romHash == romHash && row.slot == slot

    override suspend fun upsert(entity: SaveReplicaEntity): Long {
        val existingIndex = rows.indexOfFirst {
            scopeMatches(it, entity.serverKey, entity.userKey, entity.romId, entity.romHash, entity.slot)
        }
        val id = if (existingIndex >= 0) rows[existingIndex].id else nextId++
        val stored = entity.copy(id = id)
        if (existingIndex >= 0) rows[existingIndex] = stored else rows.add(stored)
        return id
    }

    override suspend fun findByScope(serverKey: String, userKey: String, romId: Long, romHash: String, slot: String): SaveReplicaEntity? =
        rows.find { scopeMatches(it, serverKey, userKey, romId, romHash, slot) }

    override suspend fun findByStatus(serverKey: String, userKey: String, status: SaveSyncStatus): List<SaveReplicaEntity> =
        rows.filter { it.serverKey == serverKey && it.userKey == userKey && it.syncStatus == status }

    override suspend fun deleteByScope(serverKey: String, userKey: String, romId: Long, romHash: String, slot: String) {
        rows.removeAll { scopeMatches(it, serverKey, userKey, romId, romHash, slot) }
    }
}

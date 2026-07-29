package com.romm.androidtv.romm.save

class FakeSaveReplicaDao : SaveReplicaDao {
    private val rows = mutableListOf<SaveReplicaEntity>()
    private var nextId = 1L

    fun seed(entity: SaveReplicaEntity) {
        rows.add(entity.copy(id = nextId++))
    }

    override suspend fun upsert(entity: SaveReplicaEntity): Long {
        val existing = findByScope(entity.serverKey, entity.userKey, entity.romId, entity.romHash, entity.slot)
        if (existing != null) {
            val idx = rows.indexOfFirst { it.id == existing.id }
            if (idx >= 0) rows[idx] = entity.copy(id = existing.id)
        } else {
            rows.add(entity.copy(id = nextId++))
        }
        return entity.id.takeIf { it > 0 } ?: (nextId++)
    }

    override suspend fun findByScope(
        serverKey: String, userKey: String, romId: Long, romHash: String, slot: String,
    ): SaveReplicaEntity? = rows.find {
        it.serverKey == serverKey && it.userKey == userKey &&
            it.romId == romId && it.romHash == romHash && it.slot == slot
    }

    override suspend fun findByStatus(
        serverKey: String, userKey: String, status: SaveSyncStatus,
    ): List<SaveReplicaEntity> = rows.filter {
        it.serverKey == serverKey && it.userKey == userKey && it.syncStatus == status
    }

    override suspend fun deleteByScope(
        serverKey: String, userKey: String, romId: Long, romHash: String, slot: String,
    ) {
        rows.removeAll {
            it.serverKey == serverKey && it.userKey == userKey &&
                it.romId == romId && it.romHash == romHash && it.slot == slot
        }
    }
}

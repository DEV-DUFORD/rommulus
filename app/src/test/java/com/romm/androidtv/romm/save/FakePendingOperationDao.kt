package com.romm.androidtv.romm.save

/**
 * In-memory [PendingOperationDao] fake for unit-testing [SaveSyncCoordinatorImpl]
 * — mirrors the real DAO's terminal-status filtering and generation-based
 * dedupe closely enough for coordinator logic tests.
 * [PendingOperationDaoInstrumentedTest] (`app/src/androidTest`) verifies the
 * real Room-backed behavior.
 */
class FakePendingOperationDao : PendingOperationDao {
    private val rows = mutableListOf<PendingOperationEntity>()
    private var nextId = 1L

    private fun scopeMatches(
        row: PendingOperationEntity,
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
        operationType: PendingOperationType,
    ) = row.serverKey == serverKey && row.userKey == userKey && row.romId == romId &&
        row.romHash == romHash && row.slot == slot && row.operationType == operationType

    private fun isTerminal(status: PendingOperationStatus) = PendingOperationTransitions.isTerminal(status)

    override suspend fun insert(entity: PendingOperationEntity): Long {
        val id = nextId++
        rows.add(entity.copy(id = id))
        return id
    }

    override suspend fun findById(id: Long): PendingOperationEntity? = rows.find { it.id == id }

    override suspend fun findByStatus(status: PendingOperationStatus): List<PendingOperationEntity> =
        rows.filter { it.status == status }

    override suspend fun findActiveByScope(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
        operationType: PendingOperationType,
    ): List<PendingOperationEntity> =
        rows.filter { scopeMatches(it, serverKey, userKey, romId, romHash, slot, operationType) && !isTerminal(it.status) }

    override suspend fun updateStatus(
        id: Long,
        status: PendingOperationStatus,
        attemptCount: Int,
        lastError: String?,
        lastHttpCode: Int?,
        updatedAtEpochMs: Long,
    ) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) {
            rows[index] = rows[index].copy(
                status = status,
                attemptCount = attemptCount,
                lastError = lastError,
                lastHttpCode = lastHttpCode,
                updatedAtEpochMs = updatedAtEpochMs,
            )
        }
    }

    override suspend fun deleteStaleForScope(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
        operationType: PendingOperationType,
        olderThanLocalGenerationEpochMs: Long,
    ): Int {
        val before = rows.size
        rows.removeAll {
            scopeMatches(it, serverKey, userKey, romId, romHash, slot, operationType) &&
                !isTerminal(it.status) &&
                it.localGenerationEpochMs < olderThanLocalGenerationEpochMs
        }
        return before - rows.size
    }

    fun allRows(): List<PendingOperationEntity> = rows.toList()
}

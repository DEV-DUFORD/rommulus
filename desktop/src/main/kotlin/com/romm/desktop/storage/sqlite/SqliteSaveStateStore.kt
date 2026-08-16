package com.romm.desktop.storage.sqlite

import com.romm.androidtv.storage.ports.SaveReplicaScope
import com.romm.androidtv.storage.ports.SaveStateStore
import com.romm.androidtv.storage.records.PendingOperationRecord
import com.romm.androidtv.storage.records.PendingOperationStatus
import com.romm.androidtv.storage.records.PendingOperationType
import com.romm.androidtv.storage.records.SaveReplicaRecord
import com.romm.androidtv.storage.records.SaveSyncStatus
import java.sql.ResultSet

/**
 * SQLite-backed [SaveStateStore] (desktop schema v1; plans/LINUX_X64.md §10.2).
 *
 * Mirrors [com.romm.androidtv.storage.fakes.InMemorySaveStateStore] semantics:
 * - replicas are upsert-replace keyed by scope (unique index `uq_save_replicas_scope`);
 * - operations are auto-id rows; "active" means non-terminal status
 *   (SUCCEEDED / AUTH_REQUIRED / CONFLICT / PERMANENT_FAILURE are terminal);
 * - [inTransaction] commits replica + pending-op updates forming one state transition
 *   atomically: a single JDBC transaction, rolled back and returned as `Result.failure` on
 *   any exception. Nested calls join the outermost transaction (no inner commit).
 *
 * Deviations from the InMemory fake (contract-compatible): an explicit `replica.id` is
 * ignored — SQLite rowid is the identity and stays stable across replaces of the same scope.
 */
class SqliteSaveStateStore(private val db: SqliteDatabase) : SaveStateStore {

    private val lock = Any()
    private var txDepth = 0

    // ---- SaveReplicaStore ----

    override fun upsert(replica: SaveReplicaRecord): Result<Long> = runCatching {
        synchronized(lock) {
            db.insertReturningId(
                """
                INSERT INTO save_replicas (
                    server_key, user_key, rom_id, rom_hash, slot, core_id, core_build_revision,
                    expected_sram_size_bytes, local_hash, local_size_bytes, local_written_at_epoch_ms,
                    romm_save_id, server_hash, server_size_bytes, server_updated_at_epoch_ms,
                    sync_status, last_error
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (server_key, user_key, rom_id, rom_hash, slot) DO UPDATE SET
                    core_id = excluded.core_id,
                    core_build_revision = excluded.core_build_revision,
                    expected_sram_size_bytes = excluded.expected_sram_size_bytes,
                    local_hash = excluded.local_hash,
                    local_size_bytes = excluded.local_size_bytes,
                    local_written_at_epoch_ms = excluded.local_written_at_epoch_ms,
                    romm_save_id = excluded.romm_save_id,
                    server_hash = excluded.server_hash,
                    server_size_bytes = excluded.server_size_bytes,
                    server_updated_at_epoch_ms = excluded.server_updated_at_epoch_ms,
                    sync_status = excluded.sync_status,
                    last_error = excluded.last_error
                RETURNING id
                """.trimIndent(),
                replica.serverKey, replica.userKey, replica.romId, replica.romHash, replica.slot,
                replica.coreId, replica.coreBuildRevision,
                replica.expectedSramSizeBytes, replica.localHash, replica.localSizeBytes,
                replica.localWrittenAtEpochMs, replica.rommSaveId, replica.serverHash,
                replica.serverSizeBytes, replica.serverUpdatedAtEpochMs,
                replica.syncStatus.name, replica.lastError,
            )
        }
    }

    override fun findByScope(scope: SaveReplicaScope): SaveReplicaRecord? =
        db.queryOne(
            "$REPLICA_SELECT WHERE server_key = ? AND user_key = ? AND rom_id = ? AND rom_hash = ? AND slot = ?",
            ::mapReplica,
            scope.serverKey, scope.userKey, scope.romId, scope.romHash, scope.slot,
        )

    override fun findByStatus(serverKey: String, userKey: String, status: SaveSyncStatus): List<SaveReplicaRecord> =
        db.query(
            "$REPLICA_SELECT WHERE server_key = ? AND user_key = ? AND sync_status = ? ORDER BY id",
            ::mapReplica,
            serverKey, userKey, status.name,
        )

    override fun markSyncedIfGenerationMatches(
        scope: SaveReplicaScope,
        localGenerationEpochMs: Long,
        rommSaveId: Long?,
        serverHash: String?,
        serverSizeBytes: Long?,
        serverUpdatedAtEpochMs: Long?,
    ): Boolean {
        val updated = db.executeUpdate(
            """
            UPDATE save_replicas
            SET sync_status = 'SYNCED',
                last_error = NULL,
                romm_save_id = ?,
                server_hash = ?,
                server_size_bytes = ?,
                server_updated_at_epoch_ms = ?
            WHERE server_key = ? AND user_key = ? AND rom_id = ? AND rom_hash = ? AND slot = ?
              AND local_written_at_epoch_ms = ?
            """.trimIndent(),
            rommSaveId, serverHash, serverSizeBytes, serverUpdatedAtEpochMs,
            scope.serverKey, scope.userKey, scope.romId, scope.romHash, scope.slot,
            localGenerationEpochMs,
        )
        return updated > 0
    }

    override fun deleteByScope(scope: SaveReplicaScope): Result<Unit> = runCatching {
        db.executeUpdate(
            "DELETE FROM save_replicas WHERE server_key = ? AND user_key = ? AND rom_id = ? AND rom_hash = ? AND slot = ?",
            scope.serverKey, scope.userKey, scope.romId, scope.romHash, scope.slot,
        )
    }

    // ---- PendingOperationStore ----

    override fun enqueue(operation: PendingOperationRecord): Result<Long> = runCatching {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            db.insertReturningId(
                """
                INSERT INTO pending_operations (
                    server_key, user_key, rom_id, rom_hash, slot, operation_type,
                    local_generation_epoch_ms, status, attempt_count, last_error, last_http_code,
                    origin, upload_file_name, session_id, negotiate_file_name,
                    negotiate_core_id, negotiate_core_build_revision,
                    created_at_epoch_ms, updated_at_epoch_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """.trimIndent(),
                operation.serverKey, operation.userKey, operation.romId, operation.romHash, operation.slot,
                operation.operationType.name, operation.localGenerationEpochMs, operation.status.name,
                operation.attemptCount, operation.lastError, operation.lastHttpCode, operation.origin,
                operation.uploadFileName, operation.sessionId, operation.negotiateFileName,
                operation.negotiateCoreId, operation.negotiateCoreBuildRevision,
                if (operation.createdAtEpochMs > 0) operation.createdAtEpochMs else now,
                if (operation.updatedAtEpochMs > 0) operation.updatedAtEpochMs else now,
            )
        }
    }

    override fun findById(id: Long): PendingOperationRecord? =
        db.queryOne("$OP_SELECT WHERE id = ?", ::mapOp, id)

    override fun findByStatus(status: PendingOperationStatus): List<PendingOperationRecord> =
        db.query("$OP_SELECT WHERE status = ? ORDER BY id", ::mapOp, status.name)

    override fun findActiveByScope(scope: SaveReplicaScope, operationType: PendingOperationType): List<PendingOperationRecord> =
        db.query(
            "$OP_SELECT WHERE $SCOPE_FILTER AND operation_type = ? AND $ACTIVE_STATUS_FILTER ORDER BY id",
            ::mapOp,
            scope.serverKey, scope.userKey, scope.romId, scope.romHash, scope.slot,
            operationType.name,
        )

    override fun updateStatus(
        id: Long,
        status: PendingOperationStatus,
        attemptCount: Int,
        lastError: String?,
        lastHttpCode: Int?,
        updatedAtEpochMs: Long,
    ): Result<Unit> = runCatching {
        // Mirrors InMemorySaveStateStore: updating an absent id is a silent no-op success.
        db.executeUpdate(
            "UPDATE pending_operations SET status = ?, attempt_count = ?, last_error = ?, last_http_code = ?, updated_at_epoch_ms = ? WHERE id = ?",
            status.name, attemptCount, lastError, lastHttpCode, updatedAtEpochMs, id,
        )
    }

    override fun deleteStaleForScope(
        scope: SaveReplicaScope,
        operationType: PendingOperationType,
        olderThanLocalGenerationEpochMs: Long,
    ): Int = db.executeUpdate(
        "DELETE FROM pending_operations WHERE $SCOPE_FILTER AND operation_type = ? AND $ACTIVE_STATUS_FILTER AND local_generation_epoch_ms < ?",
        scope.serverKey, scope.userKey, scope.romId, scope.romHash, scope.slot,
        operationType.name, olderThanLocalGenerationEpochMs,
    )

    // ---- SaveStateStore transaction ----

    override fun <T> inTransaction(block: (SaveStateStore) -> T): Result<T> = runCatching {
        synchronized(lock) {
            val outermost = ++txDepth == 1
            if (outermost) db.connection.autoCommit = false
            try {
                val value = block(this)
                if (outermost) {
                    try {
                        db.connection.commit()
                    } catch (e: Exception) {
                        runCatching { db.connection.rollback() }
                        throw e
                    }
                }
                value
            } catch (e: Throwable) {
                if (outermost) runCatching { db.connection.rollback() }
                throw e
            } finally {
                if (outermost) db.connection.autoCommit = true
                txDepth--
            }
        }
    }

    // ---- row mapping ----

    private fun mapReplica(rs: ResultSet) = SaveReplicaRecord(
        id = rs.getLong(1),
        serverKey = rs.getString(2),
        userKey = rs.getString(3),
        romId = rs.getLong(4),
        romHash = rs.getString(5),
        slot = rs.getString(6),
        coreId = rs.getString(7),
        coreBuildRevision = rs.getString(8),
        expectedSramSizeBytes = nullableLong(rs, 9),
        localHash = rs.getString(10),
        localSizeBytes = nullableLong(rs, 11),
        localWrittenAtEpochMs = nullableLong(rs, 12),
        rommSaveId = nullableLong(rs, 13),
        serverHash = rs.getString(14),
        serverSizeBytes = nullableLong(rs, 15),
        serverUpdatedAtEpochMs = nullableLong(rs, 16),
        syncStatus = SaveSyncStatus.valueOf(rs.getString(17)!!),
        lastError = rs.getString(18),
    )

    private fun mapOp(rs: ResultSet) = PendingOperationRecord(
        id = rs.getLong(1),
        serverKey = rs.getString(2),
        userKey = rs.getString(3),
        romId = rs.getLong(4),
        romHash = rs.getString(5),
        slot = rs.getString(6),
        operationType = PendingOperationType.valueOf(rs.getString(7)!!),
        localGenerationEpochMs = rs.getLong(8),
        status = PendingOperationStatus.valueOf(rs.getString(9)!!),
        attemptCount = rs.getInt(10),
        lastError = rs.getString(11),
        lastHttpCode = nullableInt(rs, 12),
        origin = rs.getString(13),
        uploadFileName = rs.getString(14),
        sessionId = nullableLong(rs, 15),
        negotiateFileName = rs.getString(16),
        negotiateCoreId = rs.getString(17),
        negotiateCoreBuildRevision = rs.getString(18),
        createdAtEpochMs = rs.getLong(19),
        updatedAtEpochMs = rs.getLong(20),
    )

    private fun nullableLong(rs: ResultSet, column: Int): Long? {
        val value = rs.getLong(column)
        return if (rs.wasNull()) null else value
    }

    private fun nullableInt(rs: ResultSet, column: Int): Int? {
        val value = rs.getInt(column)
        return if (rs.wasNull()) null else value
    }

    companion object {
        private const val REPLICA_COLUMNS = """
            id, server_key, user_key, rom_id, rom_hash, slot, core_id, core_build_revision,
            expected_sram_size_bytes, local_hash, local_size_bytes, local_written_at_epoch_ms,
            romm_save_id, server_hash, server_size_bytes, server_updated_at_epoch_ms,
            sync_status, last_error
        """

        private const val REPLICA_SELECT = "SELECT $REPLICA_COLUMNS FROM save_replicas"

        private const val OP_COLUMNS = """
            id, server_key, user_key, rom_id, rom_hash, slot, operation_type,
            local_generation_epoch_ms, status, attempt_count, last_error, last_http_code,
            origin, upload_file_name, session_id, negotiate_file_name,
            negotiate_core_id, negotiate_core_build_revision,
            created_at_epoch_ms, updated_at_epoch_ms
        """

        private const val OP_SELECT = "SELECT $OP_COLUMNS FROM pending_operations"

        private const val SCOPE_FILTER =
            "server_key = ? AND user_key = ? AND rom_id = ? AND rom_hash = ? AND slot = ?"

        /** Terminal statuses per Android PendingOperationTransitions.isTerminal. */
        private const val ACTIVE_STATUS_FILTER =
            "status NOT IN ('SUCCEEDED', 'AUTH_REQUIRED', 'CONFLICT', 'PERMANENT_FAILURE')"
    }
}

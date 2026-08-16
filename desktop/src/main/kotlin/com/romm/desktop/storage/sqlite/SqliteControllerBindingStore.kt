package com.romm.desktop.storage.sqlite

import com.romm.androidtv.storage.ports.ControllerBindingStore
import com.romm.androidtv.storage.records.ControllerBindingRecord
import java.sql.ResultSet

/**
 * SQLite-backed [ControllerBindingStore] (desktop schema v1; plans/LINUX_X64.md §10.2).
 *
 * Mirrors [com.romm.androidtv.storage.fakes.InMemoryControllerBindingStore] semantics:
 * upsert-replace keyed by coreId/playerIndex/controlId/bindingSlot (composite primary key),
 * deletes of absent rows are silent no-op successes, and [upsertAll] commits all-or-nothing
 * in a single transaction.
 */
class SqliteControllerBindingStore(private val db: SqliteDatabase) : ControllerBindingStore {

    override fun loadForCore(coreId: String): List<ControllerBindingRecord> =
        db.query(
            "$SELECT_ALL WHERE core_id = ? ORDER BY player_index, control_id, binding_slot",
            ::mapRow,
            coreId,
        )

    override fun loadForPlayer(coreId: String, playerIndex: Int): List<ControllerBindingRecord> =
        db.query(
            "$SELECT_ALL WHERE core_id = ? AND player_index = ? ORDER BY control_id, binding_slot",
            ::mapRow,
            coreId, playerIndex,
        )

    override fun upsert(binding: ControllerBindingRecord): Result<Unit> = runCatching {
        db.executeUpdate(
            """
            INSERT INTO controller_bindings (
                core_id, player_index, control_id, binding_slot,
                binding_type, input_code, polarity, schema_version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (core_id, player_index, control_id, binding_slot) DO UPDATE SET
                binding_type = excluded.binding_type,
                input_code = excluded.input_code,
                polarity = excluded.polarity,
                schema_version = excluded.schema_version
            """.trimIndent(),
            binding.coreId, binding.playerIndex, binding.controlId, binding.bindingSlot,
            binding.bindingType, binding.inputCode, binding.polarity, binding.schemaVersion,
        )
    }

    override fun upsertAll(bindings: List<ControllerBindingRecord>): Result<Unit> = runCatching {
        db.inSqlTransaction { bindings.forEach { upsert(it).getOrThrow() } }
    }

    override fun delete(coreId: String, playerIndex: Int, controlId: String, bindingSlot: Int): Result<Unit> = runCatching {
        db.executeUpdate(
            "DELETE FROM controller_bindings WHERE core_id = ? AND player_index = ? AND control_id = ? AND binding_slot = ?",
            coreId, playerIndex, controlId, bindingSlot,
        )
    }

    override fun deletePlayer(coreId: String, playerIndex: Int): Result<Unit> = runCatching {
        db.executeUpdate(
            "DELETE FROM controller_bindings WHERE core_id = ? AND player_index = ?",
            coreId, playerIndex,
        )
    }

    override fun deleteCore(coreId: String): Result<Unit> = runCatching {
        db.executeUpdate("DELETE FROM controller_bindings WHERE core_id = ?", coreId)
    }

    private fun mapRow(rs: ResultSet) = ControllerBindingRecord(
        coreId = rs.getString(1),
        playerIndex = rs.getInt(2),
        controlId = rs.getString(3),
        bindingSlot = rs.getInt(4),
        bindingType = rs.getString(5),
        inputCode = rs.getInt(6),
        polarity = nullableInt(rs, 7),
        schemaVersion = rs.getInt(8),
    )

    private fun nullableInt(rs: ResultSet, column: Int): Int? {
        val value = rs.getInt(column)
        return if (rs.wasNull()) null else value
    }

    companion object {
        private const val SELECT_ALL = """
            SELECT core_id, player_index, control_id, binding_slot,
                   binding_type, input_code, polarity, schema_version
            FROM controller_bindings
        """
    }
}

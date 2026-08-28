package com.romm.desktop.storage.sqlite

import com.romm.androidtv.storage.ports.SessionRecordStore
import com.romm.androidtv.storage.records.SessionRecord
import java.sql.ResultSet

/**
 * SQLite-backed [SessionRecordStore] (desktop schema v3; plans/PHASE6.md §5 decision 2).
 *
 * Mirrors [com.romm.androidtv.storage.fakes.InMemorySessionRecordStore] semantics: the store
 * holds exactly ONE session record (the last verified session), so [save] replaces whatever
 * was stored — including a record for a different origin ("last write wins", per the shared
 * contract suite). [save]/[clear] return false only when the write could not be committed;
 * a JDBC exception is caught and mapped to `false` so seam callers see the same
 * fail-closed durability signal as Android's `SharedPreferences.Editor.commit`.
 */
class SqliteSessionRecordStore(private val db: SqliteDatabase) : SessionRecordStore {

    override fun save(record: SessionRecord): Boolean = runCatching {
        // Single-row store: delete-then-insert inside one transaction so a concurrent or
        // partial write can never leave two records (origin is the PK, but a prior record
        // for a DIFFERENT origin must also be displaced).
        db.inSqlTransaction {
            db.executeUpdate("DELETE FROM session_records")
            db.executeUpdate(
                """
                INSERT INTO session_records (origin, username, verified_at_epoch_millis, kiosk_mode)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                record.origin, record.username, record.verifiedAtEpochMillis,
                if (record.kioskMode) 1 else 0,
            )
        }
    }.isSuccess

    override fun current(): SessionRecord? = db.queryOne(
        "SELECT origin, username, verified_at_epoch_millis, kiosk_mode FROM session_records",
        ::mapRow,
    )

    override fun clear(): Boolean = runCatching {
        // Clearing an already-empty store is still a successful clear (mirrors the InMemory fake).
        db.executeUpdate("DELETE FROM session_records")
    }.isSuccess

    private fun mapRow(rs: ResultSet) = SessionRecord(
        origin = rs.getString(1) ?: "",
        username = rs.getString(2),
        verifiedAtEpochMillis = rs.getLong(3),
        kioskMode = rs.getInt(4) != 0,
    )
}

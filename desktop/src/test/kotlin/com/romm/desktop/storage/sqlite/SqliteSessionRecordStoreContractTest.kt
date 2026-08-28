package com.romm.desktop.storage.sqlite

import com.romm.androidtv.storage.contract.SessionRecordStoreContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Wires the shared main-source [SessionRecordStoreContract] against
 * [SqliteSessionRecordStore] (V3 `session_records` table on a temp-dir DB). All 3 cases
 * run unmodified — this contract has no InMemory-fake-specific helpers, so nothing is
 * excluded or weakened.
 */
class SqliteSessionRecordStoreContractTest {

    @TempDir
    lateinit var tempDir: Path

    private fun newStore(): SqliteSessionRecordStore =
        SqliteSessionRecordStore(
            SqliteDatabase.open(tempDir.resolve("rommulus.db")).getOrThrow(),
        )

    @Test
    fun save_and_read() = SessionRecordStoreContract(::newStore).save_and_read()

    @Test
    fun clear_session() = SessionRecordStoreContract(::newStore).clear_session()

    @Test
    fun last_write_wins() = SessionRecordStoreContract(::newStore).last_write_wins()
}

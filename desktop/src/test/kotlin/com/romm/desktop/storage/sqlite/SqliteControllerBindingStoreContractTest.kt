package com.romm.desktop.storage.sqlite

import com.romm.androidtv.storage.contract.ControllerBindingStoreContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Wires the shared main-source [ControllerBindingStoreContract] against
 * [SqliteControllerBindingStore]. All 7 cases run unmodified — this contract has no
 * InMemory-fake-specific helpers, so nothing is excluded or weakened.
 */
class SqliteControllerBindingStoreContractTest {

    @TempDir
    lateinit var tempDir: Path

    private fun newStore(): SqliteControllerBindingStore =
        SqliteControllerBindingStore(
            SqliteDatabase.open(tempDir.resolve("rommulus.db")).getOrThrow(),
        )

    @Test
    fun loadForCore_returns_all_bindings_for_core() =
        ControllerBindingStoreContract(::newStore).loadForCore_returns_all_bindings_for_core()

    @Test
    fun loadForPlayer_filters_by_player_index() =
        ControllerBindingStoreContract(::newStore).loadForPlayer_filters_by_player_index()

    @Test
    fun upsert_replaces_existing_binding() =
        ControllerBindingStoreContract(::newStore).upsert_replaces_existing_binding()

    @Test
    fun delete_removes_single_binding() =
        ControllerBindingStoreContract(::newStore).delete_removes_single_binding()

    @Test
    fun deletePlayer_removes_all_bindings_for_player() =
        ControllerBindingStoreContract(::newStore).deletePlayer_removes_all_bindings_for_player()

    @Test
    fun deleteCore_removes_all_bindings_for_core() =
        ControllerBindingStoreContract(::newStore).deleteCore_removes_all_bindings_for_core()

    @Test
    fun upsertAll_inserts_multiple_bindings() =
        ControllerBindingStoreContract(::newStore).upsertAll_inserts_multiple_bindings()
}

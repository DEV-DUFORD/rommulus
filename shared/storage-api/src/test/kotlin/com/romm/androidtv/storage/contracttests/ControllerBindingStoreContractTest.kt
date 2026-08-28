package com.romm.androidtv.storage.contracttests

import com.romm.androidtv.storage.contract.ControllerBindingStoreContract
import com.romm.androidtv.storage.fakes.InMemoryControllerBindingStore
import org.junit.jupiter.api.Test

class ControllerBindingStoreContractTest {

    private val contract = ControllerBindingStoreContract { InMemoryControllerBindingStore() }

    @Test
    fun `loadForCore returns all bindings for core`() = contract.loadForCore_returns_all_bindings_for_core()

    @Test
    fun `loadForPlayer filters by player index`() = contract.loadForPlayer_filters_by_player_index()

    @Test
    fun `upsert replaces existing binding`() = contract.upsert_replaces_existing_binding()

    @Test
    fun `delete removes single binding`() = contract.delete_removes_single_binding()

    @Test
    fun `deletePlayer removes all bindings for player`() = contract.deletePlayer_removes_all_bindings_for_player()

    @Test
    fun `deleteCore removes all bindings for core`() = contract.deleteCore_removes_all_bindings_for_core()

    @Test
    fun `upsertAll inserts multiple bindings`() = contract.upsertAll_inserts_multiple_bindings()
}

package com.romm.androidtv.storage.contracttests

import com.romm.androidtv.storage.contract.ControllerBindingStoreContract
import com.romm.androidtv.storage.fakes.InMemoryControllerBindingStore
import org.junit.jupiter.api.Test

class ControllerBindingStoreContractTest {

    private val contract = ControllerBindingStoreContract { InMemoryControllerBindingStore() }

    @Test
    fun `loadForCore returns all bindings for core`() = contract.`loadForCore returns all bindings for core`()

    @Test
    fun `loadForPlayer filters by player index`() = contract.`loadForPlayer filters by player index`()

    @Test
    fun `upsert replaces existing binding`() = contract.`upsert replaces existing binding`()

    @Test
    fun `delete removes single binding`() = contract.`delete removes single binding`()

    @Test
    fun `deletePlayer removes all bindings for player`() = contract.`deletePlayer removes all bindings for player`()

    @Test
    fun `deleteCore removes all bindings for core`() = contract.`deleteCore removes all bindings for core`()

    @Test
    fun `upsertAll inserts multiple bindings`() = contract.`upsertAll inserts multiple bindings`()
}

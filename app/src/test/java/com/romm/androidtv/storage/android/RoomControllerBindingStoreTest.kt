package com.romm.androidtv.storage.android

import com.romm.androidtv.controller.config.ControllerBindingDao
import com.romm.androidtv.controller.config.FakeControllerBindingDao
import com.romm.androidtv.storage.contract.ControllerBindingStoreContract
import com.romm.androidtv.storage.records.BindingSlots
import com.romm.androidtv.storage.records.ControllerBindingRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("RoomControllerBindingStore — adapter over ControllerBindingDao")
class RoomControllerBindingStoreTest {

    private fun createStore(): RoomControllerBindingStore {
        val dao = FakeControllerBindingDao()
        return RoomControllerBindingStore(dao)
    }

    // ---- mirror contract tests ----

    @Test
    fun `loadForCore returns all bindings for core`() {
        val store = createStore()
        store.upsertAll(listOf(
            ControllerBindingRecord("snes9x", 0, "btn_a", BindingSlots.PRIMARY, "key", 1),
            ControllerBindingRecord("snes9x", 0, "btn_b", BindingSlots.PRIMARY, "key", 2),
            ControllerBindingRecord("snes9x", 1, "btn_a", BindingSlots.PRIMARY, "key", 3),
        ))
        val all = store.loadForCore("snes9x")
        assertThat(all).hasSize(3)
        assertThat(all.map { it.controlId }).containsExactlyInAnyOrder("btn_a", "btn_a", "btn_b")
    }

    @Test
    fun `loadForPlayer filters by player index`() {
        val store = createStore()
        store.upsertAll(listOf(
            ControllerBindingRecord("snes9x", 0, "btn_a", BindingSlots.PRIMARY, "key", 1),
            ControllerBindingRecord("snes9x", 1, "btn_a", BindingSlots.PRIMARY, "key", 2),
        ))
        val p0 = store.loadForPlayer("snes9x", 0)
        assertThat(p0).hasSize(1)
        assertThat(p0[0].inputCode).isEqualTo(1)
        val p1 = store.loadForPlayer("snes9x", 1)
        assertThat(p1).hasSize(1)
        assertThat(p1[0].inputCode).isEqualTo(2)
    }

    @Test
    fun `upsert replaces existing binding`() {
        val store = createStore()
        store.upsert(ControllerBindingRecord("snes9x", 0, "btn_a", BindingSlots.PRIMARY, "key", 1))
        store.upsert(ControllerBindingRecord("snes9x", 0, "btn_a", BindingSlots.PRIMARY, "axis", 5))
        val found = store.loadForPlayer("snes9x", 0)
        assertThat(found).hasSize(1)
        assertThat(found[0].bindingType).isEqualTo("axis")
        assertThat(found[0].inputCode).isEqualTo(5)
    }

    @Test
    fun `delete removes single binding`() {
        val store = createStore()
        store.upsert(ControllerBindingRecord("snes9x", 0, "btn_a", BindingSlots.PRIMARY, "key", 1))
        store.delete("snes9x", 0, "btn_a", BindingSlots.PRIMARY)
        assertThat(store.loadForPlayer("snes9x", 0)).isEmpty()
    }

    @Test
    fun `deletePlayer removes all bindings for player`() {
        val store = createStore()
        store.upsertAll(listOf(
            ControllerBindingRecord("snes9x", 0, "btn_a", BindingSlots.PRIMARY, "key", 1),
            ControllerBindingRecord("snes9x", 0, "btn_b", BindingSlots.PRIMARY, "key", 2),
            ControllerBindingRecord("snes9x", 1, "btn_a", BindingSlots.PRIMARY, "key", 3),
        ))
        store.deletePlayer("snes9x", 0)
        assertThat(store.loadForPlayer("snes9x", 0)).isEmpty()
        assertThat(store.loadForPlayer("snes9x", 1)).hasSize(1)
    }

    @Test
    fun `deleteCore removes all bindings for core`() {
        val store = createStore()
        store.upsertAll(listOf(
            ControllerBindingRecord("snes9x", 0, "btn_a", BindingSlots.PRIMARY, "key", 1),
            ControllerBindingRecord("snes9x", 1, "btn_a", BindingSlots.PRIMARY, "key", 2),
        ))
        store.deleteCore("snes9x")
        assertThat(store.loadForCore("snes9x")).isEmpty()
    }

    @Test
    fun `upsertAll inserts multiple bindings`() {
        val store = createStore()
        val bindings = listOf(
            ControllerBindingRecord("gb", 0, "btn_a", BindingSlots.PRIMARY, "key", 1),
            ControllerBindingRecord("gb", 0, "btn_b", BindingSlots.PRIMARY, "key", 2),
            ControllerBindingRecord("gb", 0, "dpad_up", BindingSlots.PRIMARY, "key", 3),
        )
        val result = store.upsertAll(bindings)
        assertThat(result.isSuccess).isTrue()
        assertThat(store.loadForCore("gb")).hasSize(3)
    }

    @Test
    fun `upsert returns success`() {
        val store = createStore()
        val result = store.upsert(
            ControllerBindingRecord("snes9x", 0, "btn_a", BindingSlots.PRIMARY, "key", 1)
        )
        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `delete returns success`() {
        val store = createStore()
        val result = store.delete("snes9x", 0, "btn_a", BindingSlots.PRIMARY)
        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `deletePlayer returns success`() {
        val store = createStore()
        val result = store.deletePlayer("snes9x", 0)
        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `deleteCore returns success`() {
        val store = createStore()
        val result = store.deleteCore("snes9x")
        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `all 8 record fields round-trip through entity`() {
        val store = createStore()
        val record = ControllerBindingRecord(
            coreId = "retroarch",
            playerIndex = 2,
            controlId = "dpad_down",
            bindingSlot = BindingSlots.SECONDARY,
            bindingType = "axis",
            inputCode = 5,
            polarity = -1,
            schemaVersion = 3,
        )
        store.upsert(record)
        val found = store.loadForCore("retroarch")
        assertThat(found).hasSize(1)
        val result = found[0]
        assertThat(result.coreId).isEqualTo("retroarch")
        assertThat(result.playerIndex).isEqualTo(2)
        assertThat(result.controlId).isEqualTo("dpad_down")
        assertThat(result.bindingSlot).isEqualTo(BindingSlots.SECONDARY)
        assertThat(result.bindingType).isEqualTo("axis")
        assertThat(result.inputCode).isEqualTo(5)
        assertThat(result.polarity).isEqualTo(-1)
        assertThat(result.schemaVersion).isEqualTo(3)
    }

    // ---- run contract tests via the contract harness ----

    @Test
    fun `contract suite — loadForCore returns all bindings for core`() {
        ControllerBindingStoreContract(::createStore).loadForCore_returns_all_bindings_for_core()
    }

    @Test
    fun `contract suite — loadForPlayer filters by player index`() {
        ControllerBindingStoreContract(::createStore).loadForPlayer_filters_by_player_index()
    }

    @Test
    fun `contract suite — upsert replaces existing binding`() {
        ControllerBindingStoreContract(::createStore).upsert_replaces_existing_binding()
    }

    @Test
    fun `contract suite — delete removes single binding`() {
        ControllerBindingStoreContract(::createStore).delete_removes_single_binding()
    }

    @Test
    fun `contract suite — deletePlayer removes all bindings for player`() {
        ControllerBindingStoreContract(::createStore).deletePlayer_removes_all_bindings_for_player()
    }

    @Test
    fun `contract suite — deleteCore removes all bindings for core`() {
        ControllerBindingStoreContract(::createStore).deleteCore_removes_all_bindings_for_core()
    }

    @Test
    fun `contract suite — upsertAll inserts multiple bindings`() {
        ControllerBindingStoreContract(::createStore).upsertAll_inserts_multiple_bindings()
    }
}

@file:Suppress("unused")

package com.romm.androidtv.storage.contract

import com.romm.androidtv.storage.ports.ControllerBindingStore
import com.romm.androidtv.storage.records.BindingSlots
import com.romm.androidtv.storage.records.ControllerBindingRecord

/** Contract-test suite for [ControllerBindingStore] implementations. */
class ControllerBindingStoreContract(private val createStore: () -> ControllerBindingStore) {

    fun loadForCore_returns_all_bindings_for_core() {
        val store = createStore()
        store.upsertAll(listOf(
            ControllerBindingRecord("snes9x", 0, "btn_a", BindingSlots.PRIMARY, "key", 1),
            ControllerBindingRecord("snes9x", 0, "btn_b", BindingSlots.PRIMARY, "key", 2),
            ControllerBindingRecord("snes9x", 1, "btn_a", BindingSlots.PRIMARY, "key", 3),
        ))

        val all = store.loadForCore("snes9x")
        require(all.size == 3) { "Expected 3 bindings for core, got ${all.size}" }
    }

    fun loadForPlayer_filters_by_player_index() {
        val store = createStore()
        store.upsertAll(listOf(
            ControllerBindingRecord("snes9x", 0, "btn_a", BindingSlots.PRIMARY, "key", 1),
            ControllerBindingRecord("snes9x", 1, "btn_a", BindingSlots.PRIMARY, "key", 2),
        ))

        val p0 = store.loadForPlayer("snes9x", 0)
        require(p0.size == 1) { "Expected 1 binding for player 0" }
        require(p0[0].inputCode == 1)

        val p1 = store.loadForPlayer("snes9x", 1)
        require(p1.size == 1) { "Expected 1 binding for player 1" }
        require(p1[0].inputCode == 2)
    }

    fun upsert_replaces_existing_binding() {
        val store = createStore()
        store.upsert(ControllerBindingRecord("snes9x", 0, "btn_a", BindingSlots.PRIMARY, "key", 1))
        store.upsert(ControllerBindingRecord("snes9x", 0, "btn_a", BindingSlots.PRIMARY, "axis", 5))

        val found = store.loadForPlayer("snes9x", 0)
        require(found.size == 1) { "Should still be 1 binding after replace" }
        require(found[0].bindingType == "axis")
        require(found[0].inputCode == 5)
    }

    fun delete_removes_single_binding() {
        val store = createStore()
        store.upsert(ControllerBindingRecord("snes9x", 0, "btn_a", BindingSlots.PRIMARY, "key", 1))
        store.delete("snes9x", 0, "btn_a", BindingSlots.PRIMARY)

        require(store.loadForPlayer("snes9x", 0).isEmpty())
    }

    fun deletePlayer_removes_all_bindings_for_player() {
        val store = createStore()
        store.upsertAll(listOf(
            ControllerBindingRecord("snes9x", 0, "btn_a", BindingSlots.PRIMARY, "key", 1),
            ControllerBindingRecord("snes9x", 0, "btn_b", BindingSlots.PRIMARY, "key", 2),
            ControllerBindingRecord("snes9x", 1, "btn_a", BindingSlots.PRIMARY, "key", 3),
        ))

        store.deletePlayer("snes9x", 0)
        require(store.loadForPlayer("snes9x", 0).isEmpty())
        require(store.loadForPlayer("snes9x", 1).size == 1)
    }

    fun deleteCore_removes_all_bindings_for_core() {
        val store = createStore()
        store.upsertAll(listOf(
            ControllerBindingRecord("snes9x", 0, "btn_a", BindingSlots.PRIMARY, "key", 1),
            ControllerBindingRecord("snes9x", 1, "btn_a", BindingSlots.PRIMARY, "key", 2),
        ))

        store.deleteCore("snes9x")
        require(store.loadForCore("snes9x").isEmpty())
    }

    fun upsertAll_inserts_multiple_bindings() {
        val store = createStore()
        val bindings = listOf(
            ControllerBindingRecord("gb", 0, "btn_a", BindingSlots.PRIMARY, "key", 1),
            ControllerBindingRecord("gb", 0, "btn_b", BindingSlots.PRIMARY, "key", 2),
            ControllerBindingRecord("gb", 0, "dpad_up", BindingSlots.PRIMARY, "key", 3),
        )

        val result = store.upsertAll(bindings)
        require(result.isSuccess) { "upsertAll should succeed" }
        require(store.loadForCore("gb").size == 3)
    }
}

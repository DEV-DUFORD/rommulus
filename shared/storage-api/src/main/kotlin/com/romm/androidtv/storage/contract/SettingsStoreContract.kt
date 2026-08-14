@file:Suppress("unused")

package com.romm.androidtv.storage.contract

import com.romm.androidtv.storage.ports.SettingsSnapshot
import com.romm.androidtv.storage.ports.SettingsStore

/** Contract-test suite for [SettingsStore] implementations. */
class SettingsStoreContract(private val createStore: () -> SettingsStore) {

    fun `write default snapshot`() {
        val store = createStore()
        val result = store.write(mapOf("key1" to "val1", "key2" to "val2"))
        require(result.isSuccess) { "write should succeed" }

        val snap = result.getOrNull()!!
        require(snap.get("key1") == "val1")
        require(snap.get("key2") == "val2")
    }

    fun `write merge`() {
        val store = createStore()
        store.write(mapOf("a" to "1", "b" to "2"))
        val result = store.write(mapOf("b" to "updated", "c" to "3"))
        require(result.isSuccess)

        val snap = result.getOrNull()!!
        require(snap.get("a") == "1") { "Key 'a' should be preserved" }
        require(snap.get("b") == "updated") { "Key 'b' should be updated" }
        require(snap.get("c") == "3") { "Key 'c' should be added" }
    }

    fun `clear`() {
        val store = createStore()
        store.write(mapOf("a" to "1", "b" to "2"))
        val result = store.clear("a")
        require(result.isSuccess)

        val snap = result.getOrNull()!!
        require(snap.get("a") == null) { "Key 'a' should be removed" }
        require(snap.get("b") == "2") { "Key 'b' should remain" }
    }

    fun `snapshot defensive copy`() {
        val store = createStore()
        store.write(mapOf("x" to "y"))
        val snap1 = store.snapshot()

        // Subsequent write must not mutate the previously-returned snapshot.
        store.write(mapOf("injected" to "bad"))
        require(snap1.get("injected") == null) { "Previously returned snapshot must be a defensive copy, unaffected by later writes" }

        // Current snapshot should reflect latest state.
        val snap2 = store.snapshot()
        require(snap2.get("injected") == "bad") { "New snapshot should see latest writes" }
    }

    fun `SettingsSnapshot boolean parsing`() {
        val snap = SettingsSnapshot(mapOf(
            "true_str" to "true",
            "one" to "1",
            "false_str" to "false",
            "zero" to "0",
            "other" to "yes",
        ))

        require(snap.boolean("true_str", false) == true)
        require(snap.boolean("one", false) == true)
        require(snap.boolean("false_str", true) == false)
        require(snap.boolean("zero", true) == false)
        require(snap.boolean("other", false) == false)
        require(snap.boolean("missing", true) == true) { "Missing key should return default" }
    }
}

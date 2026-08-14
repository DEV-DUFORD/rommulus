@file:Suppress("unused")

package com.romm.androidtv.storage.contract

import com.romm.androidtv.storage.ports.SessionRecordStore
import com.romm.androidtv.storage.records.SessionRecord

/** Contract-test suite for [SessionRecordStore] implementations. */
class SessionRecordStoreContract(private val createStore: () -> SessionRecordStore) {

    fun `save and read`() {
        val store = createStore()
        require(store.current() == null) { "Fresh store should have no session" }

        val record = SessionRecord("https://romm.example.com", "player1", System.currentTimeMillis())
        val saved = store.save(record)
        require(saved) { "save should return true" }

        val current = store.current()
        require(current != null) { "current should return the saved session" }
        require(current.origin == record.origin)
        require(current.username == record.username)
    }

    fun `clear`() {
        val store = createStore()
        store.save(SessionRecord("https://romm.example.com", "player1", System.currentTimeMillis()))
        require(store.current() != null)

        val cleared = store.clear()
        require(cleared) { "clear should return true" }
        require(store.current() == null) { "Store should be empty after clear" }
    }

    fun `last write wins`() {
        val store = createStore()
        store.save(SessionRecord("https://a.com", "user1", 100L))
        store.save(SessionRecord("https://b.com", "user2", 200L))

        val current = store.current()!!
        require(current.origin == "https://b.com") { "Should see last-written session" }
        require(current.username == "user2")
    }
}

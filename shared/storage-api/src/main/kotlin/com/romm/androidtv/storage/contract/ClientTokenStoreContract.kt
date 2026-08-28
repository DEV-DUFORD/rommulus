@file:Suppress("unused")

package com.romm.androidtv.storage.contract

import com.romm.androidtv.storage.ports.ClientTokenStore
import com.romm.androidtv.storage.ports.TokenPersistOutcome
import com.romm.androidtv.storage.records.ClientTokenRecord

/**
 * Contract-test suite for [ClientTokenStore] implementations.
 *
 * This contract asserts the strict, fail-closed semantics that every production
 * implementation must satisfy (per plans/PHASE5.md section 3, decision 4, and the
 * Android Keystore adapter it mirrors):
 * - write returns [TokenPersistOutcome.Failure] for blank/whitespace payloads;
 * - scope key is normalized: `origin.trim().lowercase()|username.trim().lowercase()`.
 *
 * Run against the InMemory fake in :shared:storage-api and against the production
 * desktop SecretServiceClientTokenStore in :desktop.
 */
class ClientTokenStoreContract(private val createStore: () -> ClientTokenStore) {

    fun read_returns_null_when_nothing_stored() {
        val store = createStore()
        val result = store.read("https://example.com", "alice")
        require(result == null) { "Fresh store should return null for unknown scope" }
    }

    fun write_and_read_roundtrip() {
        val store = createStore()
        val record = ClientTokenRecord(payload = "secret-payload-xyz", scopeVersion = 2)
        val outcome = store.write("https://example.com", "alice", record)
        require(outcome is TokenPersistOutcome.Success) { "write should succeed, got $outcome" }

        val readBack = store.read("https://example.com", "alice")
        require(readBack != null) { "read should return the stored record" }
        require(readBack.payload == "secret-payload-xyz") { "payload mismatch" }
        require(readBack.scopeVersion == 2) { "scopeVersion mismatch" }
    }

    fun write_replaces_existing_token() {
        val store = createStore()
        store.write("https://example.com", "alice", ClientTokenRecord("old-token"))
        store.write("https://example.com", "alice", ClientTokenRecord("new-token"))

        val readBack = store.read("https://example.com", "alice")!!
        require(readBack.payload == "new-token") { "write should replace existing token for same scope" }
    }

    fun tokens_scoped_by_origin_and_username() {
        val store = createStore()
        store.write("https://a.com", "user1", ClientTokenRecord("token-a1"))
        store.write("https://a.com", "user2", ClientTokenRecord("token-a2"))
        store.write("https://b.com", "user1", ClientTokenRecord("token-b1"))

        require(store.read("https://a.com", "user1")!!.payload == "token-a1")
        require(store.read("https://a.com", "user2")!!.payload == "token-a2")
        require(store.read("https://b.com", "user1")!!.payload == "token-b1")
        require(store.read("https://a.com", "user3") == null) { "different username should not collide" }
        require(store.read("https://c.com", "user1") == null) { "different origin should not collide" }
    }

    fun delete_removes_only_matching_scope() {
        val store = createStore()
        store.write("https://example.com", "alice", ClientTokenRecord("keep-this"))
        store.write("https://example.com", "bob", ClientTokenRecord("delete-this"))

        store.delete("https://example.com", "bob")

        require(store.read("https://example.com", "alice")!!.payload == "keep-this") {
            "alice's token should survive"
        }
        require(store.read("https://example.com", "bob") == null) {
            "bob's token should be deleted"
        }
    }

    fun clear_all_removes_everything() {
        val store = createStore()
        store.write("https://a.com", "u1", ClientTokenRecord("t1"))
        store.write("https://b.com", "u2", ClientTokenRecord("t2"))
        store.write("https://c.com", "u3", ClientTokenRecord("t3"))

        store.clearAll()

        require(store.read("https://a.com", "u1") == null)
        require(store.read("https://b.com", "u2") == null)
        require(store.read("https://c.com", "u3") == null)
    }

    fun write_blank_payload_returns_failure() {
        val store = createStore()
        val blank = store.write("https://example.com", "alice", ClientTokenRecord(payload = "", scopeVersion = 2))
        require(blank is TokenPersistOutcome.Failure) { "blank payload should fail closed, got $blank" }
        require(blank.reason == "blank token payload") { "unexpected blank-payload reason: ${blank.reason}" }

        val whitespace = store.write(
            "https://example.com",
            "alice",
            ClientTokenRecord(payload = "   \t\n", scopeVersion = 2),
        )
        require(whitespace is TokenPersistOutcome.Failure) { "whitespace-only payload should fail closed, got $whitespace" }
        require(whitespace.reason == "blank token payload") { "unexpected whitespace-payload reason: ${whitespace.reason}" }

        require(store.read("https://example.com", "alice") == null) {
            "failed writes must not store a token"
        }
    }

    fun scope_key_normalizes_origin_and_username() {
        val store = createStore()
        store.write("  HTTPS://EXAMPLE.COM  ", "User ", ClientTokenRecord("normalized-token"))

        val readBack = store.read("https://example.com", "user")
        require(readBack != null) {
            "trimmed+lowercased origin/username should resolve to the same scope"
        }
        require(readBack.payload == "normalized-token") { "normalized-scope payload mismatch" }

        require(store.read("https://example.com", "other") == null) {
            "genuinely different username should not collide"
        }
        require(store.read("https://other.com", "user") == null) {
            "genuinely different origin should not collide"
        }
    }
}

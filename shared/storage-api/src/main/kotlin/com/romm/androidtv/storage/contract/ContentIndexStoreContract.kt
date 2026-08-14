@file:Suppress("unused")

package com.romm.androidtv.storage.contract

import com.romm.androidtv.storage.ports.ContentIndexStore
import com.romm.androidtv.storage.records.ContentIndexRecord

/** Contract-test suite for [ContentIndexStore] implementations. */
class ContentIndexStoreContract(private val createStore: () -> ContentIndexStore) {

    fun `upsert and get`() {
        val store = createStore()
        val record = ContentIndexRecord("key1", "/path/to/file", 1024L, "abc123", 100L)
        val result = store.upsert(record)
        require(result.isSuccess)

        val found = store.get("key1")
        require(found != null) { "get should return the upserted record" }
        require(found.sizeBytes == 1024L)
        require(found.contentHash == "abc123")
    }

    fun `remove`() {
        val store = createStore()
        store.upsert(ContentIndexRecord("key1", "/path/1", 512L, null, 100L))
        require(store.get("key1") != null)

        val result = store.remove("key1")
        require(result.isSuccess)
        require(store.get("key1") == null) { "Record should be removed" }
    }

    fun `evictionCandidates LRU order and limit`() {
        val store = createStore()
        store.upsert(ContentIndexRecord("c", "/c", 100L, null, 300L)) // newest
        store.upsert(ContentIndexRecord("a", "/a", 200L, null, 100L)) // oldest
        store.upsert(ContentIndexRecord("b", "/b", 300L, null, 200L)) // middle

        val candidates = store.evictionCandidates(2)
        require(candidates.size == 2) { "Should return at most limit entries" }
        require(candidates[0].cacheKey == "a") { "Oldest should be first" }
        require(candidates[1].cacheKey == "b") { "Second oldest should be second" }
    }

    fun `totalSizeBytes sum`() {
        val store = createStore()
        store.upsert(ContentIndexRecord("x", "/x", 100L, null, 100L))
        store.upsert(ContentIndexRecord("y", "/y", 200L, null, 200L))

        require(store.totalSizeBytes() == 300L) { "Total should be sum of sizes" }
    }

    fun `replace updates size sum`() {
        val store = createStore()
        store.upsert(ContentIndexRecord("k", "/old", 500L, null, 100L))
        require(store.totalSizeBytes() == 500L)

        store.upsert(ContentIndexRecord("k", "/new", 100L, null, 200L))
        require(store.totalSizeBytes() == 100L) { "Replace should update total size" }
    }
}

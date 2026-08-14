@file:Suppress("unused")

package com.romm.androidtv.storage.contract

import com.romm.androidtv.storage.ports.ContentIndexStore
import com.romm.androidtv.storage.records.ContentIndexKind
import com.romm.androidtv.storage.records.ContentIndexRecord

/** Contract-test suite for [ContentIndexStore] implementations. */
class ContentIndexStoreContract(private val createStore: () -> ContentIndexStore) {

    private fun record(
        key: String,
        absolutePath: String,
        sizeBytes: Long,
        contentHash: String,
        lastAccessedEpochMs: Long,
        kind: ContentIndexKind = ContentIndexKind.ROM,
        serverKey: String = "s",
        userKey: String = "u",
        remoteId: Long = 1L,
        fileIdsKey: String = "",
    ) = ContentIndexRecord(
        key = key,
        kind = kind,
        serverKey = serverKey,
        userKey = userKey,
        remoteId = remoteId,
        fileIdsKey = fileIdsKey,
        contentHash = contentHash,
        absolutePath = absolutePath,
        sizeBytes = sizeBytes,
        lastAccessedEpochMs = lastAccessedEpochMs,
    )

    fun upsert_and_get() {
        val store = createStore()
        val record = record("key1", "/path/to/file", 1024L, "abc123", 100L)
        val result = store.upsert(record)
        require(result.isSuccess)

        val found = store.get("key1")
        require(found != null) { "get should return the upserted record" }
        require(found.sizeBytes == 1024L)
        require(found.contentHash == "abc123")
        require(found.kind == ContentIndexKind.ROM)
    }

    fun remove() {
        val store = createStore()
        store.upsert(record("key1", "/path/1", 512L, "h1", 100L))
        require(store.get("key1") != null)

        val result = store.remove("key1")
        require(result.isSuccess)
        require(store.get("key1") == null) { "Record should be removed" }
    }

    fun evictionCandidates_LRU_order_and_limit() {
        val store = createStore()
        store.upsert(record("c", "/c", 100L, "hc", 300L)) // newest
        store.upsert(record("a", "/a", 200L, "ha", 100L)) // oldest
        store.upsert(record("b", "/b", 300L, "hb", 200L)) // middle

        val candidates = store.evictionCandidates(2)
        require(candidates.size == 2) { "Should return at most limit entries" }
        require(candidates[0].key == "a") { "Oldest should be first" }
        require(candidates[1].key == "b") { "Second oldest should be second" }
    }

    fun totalSizeBytes_sum() {
        val store = createStore()
        store.upsert(record("x", "/x", 100L, "hx", 100L))
        store.upsert(record("y", "/y", 200L, "hy", 200L))

        require(store.totalSizeBytes() == 300L) { "Total should be sum of sizes" }
    }

    fun replace_updates_size_sum() {
        val store = createStore()
        store.upsert(record("k", "/old", 500L, "h1", 100L))
        require(store.totalSizeBytes() == 500L)

        store.upsert(record("k", "/new", 100L, "h2", 200L))
        require(store.totalSizeBytes() == 100L) { "Replace should update total size" }
    }
}

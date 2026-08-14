package com.romm.androidtv.storage.android

import com.romm.androidtv.cache.CacheDatabase
import com.romm.androidtv.cache.CacheEntry
import com.romm.androidtv.cache.CacheEntryKind
import com.romm.androidtv.storage.contract.ContentIndexStoreContract
import com.romm.androidtv.storage.records.ContentIndexKind
import com.romm.androidtv.storage.records.ContentIndexRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

@DisplayName("CacheDatabaseContentIndexStore — adapter over CacheDatabase")
class CacheDatabaseContentIndexStoreTest {

    private lateinit var root: File
    private lateinit var indexFile: File

    @BeforeEach
    fun setUp() {
        root = Files.createTempDirectory("ci-store-test").toFile()
        indexFile = File(root, "index.json")
    }

    @AfterEach
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun createStore(): CacheDatabaseContentIndexStore {
        val cacheDatabase = CacheDatabase(indexFile)
        return CacheDatabaseContentIndexStore(cacheDatabase)
    }

    private fun entry(
        key: String = "k1",
        kind: CacheEntryKind = CacheEntryKind.ROM,
        lastAccessed: Long = 1000L,
    ) = CacheEntry(
        key = key,
        kind = kind,
        serverKey = "romm.example.com",
        userKey = "alice",
        remoteId = 42,
        fileIdsKey = "7",
        contentHash = "abc123",
        absolutePath = File(root, "$key.bin").absolutePath,
        sizeBytes = 1024,
        lastAccessedEpochMs = lastAccessed,
    )

    // ---- mirror contract tests ----

    @Test
    fun `upsert and get`() {
        val store = createStore()
        val record = ContentIndexRecord(
            key = "key1",
            kind = ContentIndexKind.ROM,
            serverKey = "s",
            userKey = "u",
            remoteId = 1L,
            fileIdsKey = "",
            contentHash = "abc123",
            absolutePath = "/path/to/file",
            sizeBytes = 1024L,
            lastAccessedEpochMs = 100L,
        )
        val result = store.upsert(record)
        assertThat(result.isSuccess).isTrue()
        val found = store.get("key1")
        assertThat(found).isNotNull()
        assertThat(found!!.sizeBytes).isEqualTo(1024L)
        assertThat(found.contentHash).isEqualTo("abc123")
        assertThat(found.kind).isEqualTo(ContentIndexKind.ROM)
    }

    @Test
    fun `all 10 fields round-trip through CacheEntry`() {
        val store = createStore()
        val record = ContentIndexRecord(
            key = "full",
            kind = ContentIndexKind.FIRMWARE,
            serverKey = "srv",
            userKey = "usr",
            remoteId = 99L,
            fileIdsKey = "1,2,3",
            contentHash = "sha256deadbeef",
            absolutePath = "/fw/file.bin",
            sizeBytes = 4096L,
            lastAccessedEpochMs = 5000L,
        )
        store.upsert(record)
        val found = store.get("full")
        assertThat(found).isNotNull()
        assertThat(found!!.key).isEqualTo("full")
        assertThat(found.kind).isEqualTo(ContentIndexKind.FIRMWARE)
        assertThat(found.serverKey).isEqualTo("srv")
        assertThat(found.userKey).isEqualTo("usr")
        assertThat(found.remoteId).isEqualTo(99L)
        assertThat(found.fileIdsKey).isEqualTo("1,2,3")
        assertThat(found.contentHash).isEqualTo("sha256deadbeef")
        assertThat(found.absolutePath).isEqualTo("/fw/file.bin")
        assertThat(found.sizeBytes).isEqualTo(4096L)
        assertThat(found.lastAccessedEpochMs).isEqualTo(5000L)
    }

    @Test
    fun `remove`() {
        val store = createStore()
        store.upsert(ContentIndexRecord(
            key = "k1", kind = ContentIndexKind.ROM, serverKey = "s", userKey = "u",
            remoteId = 1L, fileIdsKey = "", contentHash = "h1", absolutePath = "/p/1",
            sizeBytes = 512L, lastAccessedEpochMs = 100L,
        ))
        assertThat(store.get("k1")).isNotNull()
        val result = store.remove("k1")
        assertThat(result.isSuccess).isTrue()
        assertThat(store.get("k1")).isNull()
    }

    @Test
    fun `evictionCandidates LRU order and limit`() {
        val store = createStore()
        store.upsert(ContentIndexRecord(
            key = "c", kind = ContentIndexKind.ROM, serverKey = "s", userKey = "u",
            remoteId = 1L, fileIdsKey = "", contentHash = "hc", absolutePath = "/c",
            sizeBytes = 100L, lastAccessedEpochMs = 300L,
        ))
        store.upsert(ContentIndexRecord(
            key = "a", kind = ContentIndexKind.ROM, serverKey = "s", userKey = "u",
            remoteId = 1L, fileIdsKey = "", contentHash = "ha", absolutePath = "/a",
            sizeBytes = 200L, lastAccessedEpochMs = 100L,
        ))
        store.upsert(ContentIndexRecord(
            key = "b", kind = ContentIndexKind.ROM, serverKey = "s", userKey = "u",
            remoteId = 1L, fileIdsKey = "", contentHash = "hb", absolutePath = "/b",
            sizeBytes = 300L, lastAccessedEpochMs = 200L,
        ))
        val candidates = store.evictionCandidates(2)
        assertThat(candidates).hasSize(2)
        assertThat(candidates[0].key).isEqualTo("a")
        assertThat(candidates[1].key).isEqualTo("b")
    }

    @Test
    fun `totalSizeBytes sum`() {
        val store = createStore()
        store.upsert(ContentIndexRecord(
            key = "x", kind = ContentIndexKind.ROM, serverKey = "s", userKey = "u",
            remoteId = 1L, fileIdsKey = "", contentHash = "hx", absolutePath = "/x",
            sizeBytes = 100L, lastAccessedEpochMs = 100L,
        ))
        store.upsert(ContentIndexRecord(
            key = "y", kind = ContentIndexKind.ROM, serverKey = "s", userKey = "u",
            remoteId = 1L, fileIdsKey = "", contentHash = "hy", absolutePath = "/y",
            sizeBytes = 200L, lastAccessedEpochMs = 200L,
        ))
        assertThat(store.totalSizeBytes()).isEqualTo(300L)
    }

    @Test
    fun `replace updates size sum`() {
        val store = createStore()
        store.upsert(ContentIndexRecord(
            key = "k", kind = ContentIndexKind.ROM, serverKey = "s", userKey = "u",
            remoteId = 1L, fileIdsKey = "", contentHash = "h1", absolutePath = "/old",
            sizeBytes = 500L, lastAccessedEpochMs = 100L,
        ))
        assertThat(store.totalSizeBytes()).isEqualTo(500L)
        store.upsert(ContentIndexRecord(
            key = "k", kind = ContentIndexKind.ROM, serverKey = "s", userKey = "u",
            remoteId = 1L, fileIdsKey = "", contentHash = "h2", absolutePath = "/new",
            sizeBytes = 100L, lastAccessedEpochMs = 200L,
        ))
        assertThat(store.totalSizeBytes()).isEqualTo(100L)
    }

    @Test
    fun `get returns null for missing key`() {
        val store = createStore()
        assertThat(store.get("nonexistent")).isNull()
    }

    @Test
    fun `evictionCandidates empty when store is empty`() {
        val store = createStore()
        assertThat(store.evictionCandidates(5)).isEmpty()
    }

    @Test
    fun `totalSizeBytes zero when store is empty`() {
        val store = createStore()
        assertThat(store.totalSizeBytes()).isEqualTo(0L)
    }

    @Test
    fun `upsert returns success`() {
        val store = createStore()
        val result = store.upsert(ContentIndexRecord(
            key = "k1", kind = ContentIndexKind.ROM, serverKey = "s", userKey = "u",
            remoteId = 1L, fileIdsKey = "", contentHash = "h1", absolutePath = "/p",
            sizeBytes = 100L, lastAccessedEpochMs = 100L,
        ))
        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `remove returns success`() {
        val store = createStore()
        val result = store.remove("k1")
        assertThat(result.isSuccess).isTrue()
    }

    // ---- run contract tests via the contract harness ----

    @Test
    fun `contract suite upsert and get`() {
        ContentIndexStoreContract(::createStore).upsert_and_get()
    }

    @Test
    fun `contract suite remove`() {
        ContentIndexStoreContract(::createStore).remove()
    }

    @Test
    fun `contract suite evictionCandidates LRU order and limit`() {
        ContentIndexStoreContract(::createStore).evictionCandidates_LRU_order_and_limit()
    }

    @Test
    fun `contract suite totalSizeBytes sum`() {
        ContentIndexStoreContract(::createStore).totalSizeBytes_sum()
    }

    @Test
    fun `contract suite replace updates size sum`() {
        ContentIndexStoreContract(::createStore).replace_updates_size_sum()
    }
}

package com.romm.desktop.storage.contentindex

import com.romm.androidtv.storage.records.ContentIndexKind
import com.romm.androidtv.storage.records.ContentIndexRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** JSON-persistence specifics of [JsonContentIndexStore]: reload, malformed files, atomicity. */
class JsonContentIndexStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private val indexPath: Path get() = tempDir.resolve("content-index.json")

    private fun store(path: Path = indexPath): JsonContentIndexStore = JsonContentIndexStore(path)

    private fun record(
        key: String,
        sizeBytes: Long = 1024L,
        lastAccessedEpochMs: Long = 100L,
        kind: ContentIndexKind = ContentIndexKind.ROM,
    ) = ContentIndexRecord(
        key = key,
        kind = kind,
        serverKey = "server",
        userKey = "user",
        remoteId = 42L,
        fileIdsKey = "7,9",
        contentHash = "hash-$key",
        absolutePath = "/cache/roms/$key",
        sizeBytes = sizeBytes,
        lastAccessedEpochMs = lastAccessedEpochMs,
    )

    @Test
    fun `absent file starts empty`() {
        val store = store()
        assertThat(store.get("k1")).isNull()
        assertThat(store.evictionCandidates(10)).isEmpty()
        assertThat(store.totalSizeBytes()).isZero()
        assertThat(Files.exists(indexPath)).isFalse()
    }

    @Test
    fun `records survive a reload from disk`() {
        store().upsert(record("k1", sizeBytes = 512L, lastAccessedEpochMs = 7L)).getOrThrow()
        store().upsert(record("k2", sizeBytes = 256L, kind = ContentIndexKind.FIRMWARE)).getOrThrow()

        val reloaded = store()
        val k1 = reloaded.get("k1")
        assertThat(k1).isNotNull
        assertThat(k1!!.sizeBytes).isEqualTo(512L)
        assertThat(k1.contentHash).isEqualTo("hash-k1")
        assertThat(k1.remoteId).isEqualTo(42L)
        assertThat(k1.fileIdsKey).isEqualTo("7,9")
        assertThat(k1.kind).isEqualTo(ContentIndexKind.ROM)
        assertThat(reloaded.get("k2")?.kind).isEqualTo(ContentIndexKind.FIRMWARE)
        assertThat(reloaded.totalSizeBytes()).isEqualTo(768L)
    }

    @Test
    fun `remove survives a reload from disk`() {
        val s = store()
        s.upsert(record("k1")).getOrThrow()
        s.upsert(record("k2")).getOrThrow()
        s.remove("k1").getOrThrow()

        val reloaded = store()
        assertThat(reloaded.get("k1")).isNull()
        assertThat(reloaded.get("k2")).isNotNull
        assertThat(reloaded.totalSizeBytes()).isEqualTo(1024L)
    }

    @Test
    fun `eviction order survives a reload from disk`() {
        val s = store()
        s.upsert(record("newest", lastAccessedEpochMs = 300L)).getOrThrow()
        s.upsert(record("oldest", lastAccessedEpochMs = 100L)).getOrThrow()
        s.upsert(record("middle", lastAccessedEpochMs = 200L)).getOrThrow()

        val candidates = store().evictionCandidates(2)
        assertThat(candidates.map { it.key }).containsExactly("oldest", "middle")
    }

    @Test
    fun `malformed file is treated as empty and the store stays usable`() {
        Files.writeString(indexPath, "{ this is not valid json")
        val store = store()
        assertThat(store.get("k1")).isNull()
        assertThat(store.totalSizeBytes()).isZero()

        // A fresh upsert rewrites the file atomically; a reload sees only the new entry.
        store.upsert(record("k1", sizeBytes = 9L)).getOrThrow()
        val reloaded = store()
        assertThat(reloaded.get("k1")?.sizeBytes).isEqualTo(9L)
        assertThat(reloaded.totalSizeBytes()).isEqualTo(9L)
    }

    @Test
    fun `blank file is treated as empty`() {
        Files.writeString(indexPath, "   \n")
        val store = store()
        assertThat(store.totalSizeBytes()).isZero()
        store.upsert(record("k1")).getOrThrow()
        assertThat(store().get("k1")).isNotNull
    }

    @Test
    fun `JSON null file is treated as empty`() {
        Files.writeString(indexPath, "null")
        assertThat(store().totalSizeBytes()).isZero()
    }

    @Test
    fun `file on disk is the entries-array JSON shape`() {
        store().upsert(record("a", sizeBytes = 10L, lastAccessedEpochMs = 5L)).getOrThrow()
        val text = Files.readString(indexPath)
        assertThat(text).contains("\"entries\"")
        assertThat(text).contains("\"key\":\"a\"")
        assertThat(text).contains("\"kind\":\"ROM\"")
        assertThat(text).contains("\"sizeBytes\":10")
    }

    @Test
    fun `remove of an unknown key succeeds and does not create the file`() {
        val store = store()
        assertThat(store.remove("missing").isSuccess).isTrue()
        assertThat(Files.exists(indexPath)).isFalse()
    }

    @Test
    fun `upsert of the same key replaces the record on disk`() {
        val s = store()
        s.upsert(record("k", sizeBytes = 500L, lastAccessedEpochMs = 100L)).getOrThrow()
        s.upsert(record("k", sizeBytes = 100L, lastAccessedEpochMs = 200L)).getOrThrow()

        val reloaded = store()
        assertThat(reloaded.totalSizeBytes()).isEqualTo(100L)
        assertThat(reloaded.get("k")?.lastAccessedEpochMs).isEqualTo(200L)
    }

    @Test
    fun `no temp files are left behind after writes`() {
        val s = store()
        s.upsert(record("a")).getOrThrow()
        s.upsert(record("b")).getOrThrow()
        s.remove("a").getOrThrow()
        val leftovers = Files.list(tempDir).use { stream ->
            stream.filter { it.fileName.toString().startsWith(".tmp-") }.count()
        }
        assertThat(leftovers).isZero()
    }
}

package com.romm.androidtv.cache

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

@DisplayName("CacheDatabase — persistent, crash-safe cache-entry index")
class CacheDatabaseTest {

    private lateinit var root: File
    private lateinit var indexFile: File

    @BeforeEach
    fun setUp() {
        root = Files.createTempDirectory("cache-db-test").toFile()
        indexFile = File(root, "index.json")
    }

    @AfterEach
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun entry(key: String = "k1", lastAccessed: Long = 1000L) = CacheEntry(
        key = key,
        kind = CacheEntryKind.ROM,
        serverKey = "romm.example.com",
        userKey = "alice",
        remoteId = 42,
        fileIdsKey = "7",
        contentHash = "abc123",
        absolutePath = File(root, "$key.bin").absolutePath,
        sizeBytes = 1024,
        lastAccessedEpochMs = lastAccessed,
    )

    @Test
    fun `starts empty when no index file exists`() {
        val db = CacheDatabase(indexFile)

        assertThat(db.all()).isEmpty()
    }

    @Test
    fun `upsert then find round-trips the exact entry`() {
        val db = CacheDatabase(indexFile)
        val e = entry()

        db.upsert(e)

        assertThat(db.find("k1")).isEqualTo(e)
    }

    @Test
    fun `persists across a fresh instance reading the same file`() {
        val db = CacheDatabase(indexFile)
        db.upsert(entry())

        val reloaded = CacheDatabase(indexFile)

        assertThat(reloaded.find("k1")).isEqualTo(entry())
    }

    @Test
    fun `touch updates lastAccessedEpochMs and persists it`() {
        val db = CacheDatabase(indexFile)
        db.upsert(entry(lastAccessed = 1000L))

        db.touch("k1", nowEpochMs = 9999L)

        assertThat(db.find("k1")!!.lastAccessedEpochMs).isEqualTo(9999L)
        assertThat(CacheDatabase(indexFile).find("k1")!!.lastAccessedEpochMs).isEqualTo(9999L)
    }

    @Test
    fun `touch on an unknown key is a harmless no-op`() {
        val db = CacheDatabase(indexFile)

        db.touch("does-not-exist", 123L)

        assertThat(db.all()).isEmpty()
    }

    @Test
    fun `remove deletes the record and persists the removal`() {
        val db = CacheDatabase(indexFile)
        db.upsert(entry())

        db.remove("k1")

        assertThat(db.find("k1")).isNull()
        assertThat(CacheDatabase(indexFile).find("k1")).isNull()
    }

    @Test
    fun `a corrupted index file is treated as empty rather than crashing startup`() {
        indexFile.writeText("{ this is not valid json ]")

        val db = CacheDatabase(indexFile)

        assertThat(db.all()).isEmpty()
    }

    @Test
    fun `holds multiple independent entries`() {
        val db = CacheDatabase(indexFile)
        db.upsert(entry(key = "k1"))
        db.upsert(entry(key = "k2"))

        assertThat(db.all()).hasSize(2)
        assertThat(db.all().map { it.key }).containsExactlyInAnyOrder("k1", "k2")
    }

    @Test
    fun `a persist failure is non-fatal and in-memory state stays authoritative`() {
        // Block the index file's parent directory so the temp-write/rename can't succeed,
        // exactly like a flaky/unwritable filesystem (issue: "Save" picker failing with
        // "failed to atomically persist cache index" during a ROM download).
        val blocker = File(root, "index.json")
        blocker.mkdirs()
        val indexFile = File(blocker, "nested.json")

        val db = CacheDatabase(indexFile)

        // Must not throw, and must keep serving the entry this process, even though
        // the disk index could not be written.
        db.upsert(entry(key = "k1"))

        assertThat(db.find("k1")).isEqualTo(entry(key = "k1"))
    }
}

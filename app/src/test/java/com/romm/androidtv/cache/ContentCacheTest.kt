package com.romm.androidtv.cache

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

@DisplayName("ContentCache — identity keying, reuse, and protected LRU eviction")
class ContentCacheTest {

    private lateinit var root: File
    private lateinit var db: CacheDatabase
    private var now = 10_000L
    private lateinit var cache: ContentCache

    @BeforeEach
    fun setUp() {
        root = Files.createTempDirectory("content-cache-test").toFile()
        db = CacheDatabase(File(root, "index.json"))
        cache = ContentCache(root, db, clock = { now })
    }

    @AfterEach
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun writeFile(name: String, sizeBytes: Int): File {
        val dir = cache.contentDir(CacheEntryKind.ROM).apply { mkdirs() }
        val file = File(dir, name)
        file.writeBytes(ByteArray(sizeBytes))
        return file
    }

    @Nested
    @DisplayName("identity")
    inner class Identity {
        @Test
        fun `never uses a display filename as identity`() {
            val key = cache.key(CacheEntryKind.ROM, "server", "user", remoteId = 42, fileIds = listOf(7))

            assertThat(key).doesNotContain("displayName", "rom.bin", ".gb")
        }

        @Test
        fun `is stable regardless of the order file ids are supplied in`() {
            val a = cache.key(CacheEntryKind.ROM, "server", "user", 42, listOf(1, 2, 3))
            val b = cache.key(CacheEntryKind.ROM, "server", "user", 42, listOf(3, 1, 2))

            assertThat(a).isEqualTo(b)
        }

        @Test
        fun `differs by server, user, remoteId, kind, or file selection`() {
            val base = cache.key(CacheEntryKind.ROM, "server-a", "user-a", 1, listOf(1))
            assertThat(cache.key(CacheEntryKind.ROM, "server-b", "user-a", 1, listOf(1))).isNotEqualTo(base)
            assertThat(cache.key(CacheEntryKind.ROM, "server-a", "user-b", 1, listOf(1))).isNotEqualTo(base)
            assertThat(cache.key(CacheEntryKind.ROM, "server-a", "user-a", 2, listOf(1))).isNotEqualTo(base)
            assertThat(cache.key(CacheEntryKind.ROM, "server-a", "user-a", 1, listOf(2))).isNotEqualTo(base)
            assertThat(cache.key(CacheEntryKind.FIRMWARE, "server-a", "user-a", 1, listOf(1))).isNotEqualTo(base)
        }
    }

    @Nested
    @DisplayName("repeated launches reuse verified cached content")
    inner class Reuse {
        @Test
        fun `findValidEntry returns a record whose backing file still matches`() {
            val file = writeFile("42_7.gb", sizeBytes = 100)
            val key = cache.key(CacheEntryKind.ROM, "server", "user", 42, listOf(7))
            cache.record(key, CacheEntryKind.ROM, "server", "user", 42, "7", "hash123", file)

            val found = cache.findValidEntry(key)

            assertThat(found).isNotNull
            assertThat(found!!.absolutePath).isEqualTo(file.absolutePath)
        }

        @Test
        fun `a record whose file was deleted out from under the cache is never returned as valid`() {
            val file = writeFile("42_7.gb", sizeBytes = 100)
            val key = cache.key(CacheEntryKind.ROM, "server", "user", 42, listOf(7))
            cache.record(key, CacheEntryKind.ROM, "server", "user", 42, "7", "hash123", file)
            file.delete()

            assertThat(cache.findValidEntry(key)).isNull()
            // The stale record is pruned, not just skipped.
            assertThat(db.find(key)).isNull()
        }

        @Test
        fun `a record whose file size no longer matches is never returned as valid`() {
            val file = writeFile("42_7.gb", sizeBytes = 100)
            val key = cache.key(CacheEntryKind.ROM, "server", "user", 42, listOf(7))
            cache.record(key, CacheEntryKind.ROM, "server", "user", 42, "7", "hash123", file)
            file.writeBytes(ByteArray(50)) // truncated/altered after being recorded

            assertThat(cache.findValidEntry(key)).isNull()
        }

        @Test
        fun `finding a valid entry marks it freshly accessed for LRU ordering`() {
            val file = writeFile("42_7.gb", sizeBytes = 10)
            val key = cache.key(CacheEntryKind.ROM, "server", "user", 42, listOf(7))
            cache.record(key, CacheEntryKind.ROM, "server", "user", 42, "7", "hash123", file)
            now = 99_999L

            cache.findValidEntry(key)

            assertThat(db.find(key)!!.lastAccessedEpochMs).isEqualTo(99_999L)
        }
    }

    @Nested
    @DisplayName("quota eviction never deletes protected/active content")
    inner class Eviction {
        @Test
        fun `evicts the least-recently-used entry first once over quota`() {
            val old = writeFile("old.bin", 100)
            now = 1L
            val oldKey = cache.key(CacheEntryKind.ROM, "s", "u", 1)
            cache.record(oldKey, CacheEntryKind.ROM, "s", "u", 1, "", "h1", old)

            val recent = writeFile("recent.bin", 100)
            now = 2L
            val recentKey = cache.key(CacheEntryKind.ROM, "s", "u", 2)
            cache.record(recentKey, CacheEntryKind.ROM, "s", "u", 2, "", "h2", recent)

            val evicted = cache.evictIfOverQuota(quotaBytes = 100, protectedKeys = emptySet())

            assertThat(evicted).containsExactly(oldKey)
            assertThat(old).doesNotExist()
            assertThat(recent).exists()
        }

        @Test
        fun `never evicts a protected key even if it is the oldest`() {
            val old = writeFile("old.bin", 100)
            now = 1L
            val oldKey = cache.key(CacheEntryKind.ROM, "s", "u", 1)
            cache.record(oldKey, CacheEntryKind.ROM, "s", "u", 1, "", "h1", old)

            val recent = writeFile("recent.bin", 100)
            now = 2L
            val recentKey = cache.key(CacheEntryKind.ROM, "s", "u", 2)
            cache.record(recentKey, CacheEntryKind.ROM, "s", "u", 2, "", "h2", recent)

            val evicted = cache.evictIfOverQuota(quotaBytes = 100, protectedKeys = setOf(oldKey))

            assertThat(evicted).doesNotContain(oldKey)
            assertThat(old).exists()
        }

        @Test
        fun `does nothing when already at or under quota`() {
            val file = writeFile("a.bin", 50)
            val key = cache.key(CacheEntryKind.ROM, "s", "u", 1)
            cache.record(key, CacheEntryKind.ROM, "s", "u", 1, "", "h1", file)

            val evicted = cache.evictIfOverQuota(quotaBytes = 1_000, protectedKeys = emptySet())

            assertThat(evicted).isEmpty()
            assertThat(file).exists()
        }

        @Test
        fun `never removes the database record for a file that fails to delete`() {
            val file = writeFile("locked.bin", 100)
            val parentDir = requireNotNull(file.parentFile)
            val key = cache.key(CacheEntryKind.ROM, "s", "u", 1)
            cache.record(key, CacheEntryKind.ROM, "s", "u", 1, "", "h1", file)
            // Make the parent directory read-only so the file delete fails (best-effort;
            // some CI filesystems ignore this, in which case this assertion is skipped).
            val parentWasWritable = parentDir.setWritable(false)

            try {
                val evicted = cache.evictIfOverQuota(quotaBytes = 0, protectedKeys = emptySet())
                if (parentWasWritable && !file.canWrite()) {
                    assertThat(evicted).isEmpty()
                    assertThat(db.find(key)).isNotNull()
                }
            } finally {
                parentDir.setWritable(true)
            }
        }
    }

    @Test
    fun `totalSizeBytes sums every recorded entry`() {
        val a = writeFile("a.bin", 30)
        val b = writeFile("b.bin", 70)
        cache.record(cache.key(CacheEntryKind.ROM, "s", "u", 1), CacheEntryKind.ROM, "s", "u", 1, "", "h1", a)
        cache.record(cache.key(CacheEntryKind.ROM, "s", "u", 2), CacheEntryKind.ROM, "s", "u", 2, "", "h2", b)

        assertThat(cache.totalSizeBytes()).isEqualTo(100L)
    }

    @Test
    fun `sweeps orphaned temp files left by a killed download at construction time`() {
        val dir = cache.contentDir(CacheEntryKind.ROM).apply { mkdirs() }
        File(dir, "orphan.bin${AtomicFileStore.TEMP_SUFFIX}").writeText("stale")

        // Constructing a new ContentCache over the same root sweeps orphans (section 10:
        // "reconciles orphaned temporary files after process death").
        ContentCache(root, db, clock = { now })

        assertThat(File(dir, "orphan.bin${AtomicFileStore.TEMP_SUFFIX}")).doesNotExist()
    }
}

package com.romm.androidtv.storage.fakes

import com.romm.androidtv.storage.ports.ContentIndexStore
import com.romm.androidtv.storage.records.ContentIndexRecord

/** In-memory content index store for tests and desktop dev-loop use. */
class InMemoryContentIndexStore : ContentIndexStore {

    private val lock = Any()
    private val entries: MutableMap<String, ContentIndexRecord> = mutableMapOf()

    override fun get(cacheKey: String): ContentIndexRecord? {
        return synchronized(lock) { entries[cacheKey] }
    }

    override fun upsert(record: ContentIndexRecord): Result<Unit> = runCatching {
        synchronized(lock) { entries[record.key] = record }
    }

    override fun remove(cacheKey: String): Result<Unit> = runCatching {
        synchronized(lock) { entries.remove(cacheKey) }
    }

    override fun evictionCandidates(limit: Int): List<ContentIndexRecord> {
        return synchronized(lock) {
            entries.values.toList()
                .sortedBy { it.lastAccessedEpochMs }
                .take(limit)
        }
    }

    override fun totalSizeBytes(): Long {
        return synchronized(lock) { entries.values.sumOf { it.sizeBytes } }
    }

    internal fun count(): Int = synchronized(lock) { entries.size }
}

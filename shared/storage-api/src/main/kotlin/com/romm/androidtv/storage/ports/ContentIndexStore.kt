package com.romm.androidtv.storage.ports

import com.romm.androidtv.storage.records.ContentIndexRecord

/** Persistence-neutral store for content cache index (LRU eviction). */
interface ContentIndexStore {
    fun get(cacheKey: String): ContentIndexRecord?
    fun upsert(record: ContentIndexRecord): Result<Unit>
    fun remove(cacheKey: String): Result<Unit>
    /** Return entries sorted by oldest lastAccess first, limited to [limit]. */
    fun evictionCandidates(limit: Int): List<ContentIndexRecord>
    /** Sum of sizeBytes across all records. */
    fun totalSizeBytes(): Long
}

package com.romm.androidtv.storage.records

/**
 * One entry in the content cache index, persistence-neutral mirror of the
 * Android Room schema (CacheEntry). Tracks cached ROM/firmware files for LRU eviction.
 */
data class ContentIndexRecord(
    val cacheKey: String,
    val pathOnDisk: String,
    val sizeBytes: Long,
    val contentHash: String?,
    val lastAccessEpochMs: Long,
)

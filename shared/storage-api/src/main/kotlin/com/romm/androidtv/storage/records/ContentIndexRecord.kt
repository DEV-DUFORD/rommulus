package com.romm.androidtv.storage.records

/** Distinguishes the two kinds of evictable content the cache holds. Mirrors Android `CacheEntryKind`. */
enum class ContentIndexKind { ROM, FIRMWARE }

/**
 * One entry in the content cache index, persistence-neutral mirror of the
 * Android Room schema (CacheEntry). Tracks cached ROM/firmware files for LRU eviction.
 */
data class ContentIndexRecord(
    val key: String,
    val kind: ContentIndexKind,
    val serverKey: String,
    val userKey: String,
    /** RomM's canonical ROM or firmware ID. */
    val remoteId: Long,
    /** Sorted, comma-joined selected file IDs for multi-file ROMs; empty for firmware or single-file ROMs. */
    val fileIdsKey: String,
    /** The verified SHA-256 of the actual downloaded bytes — the cache's true identity anchor. */
    val contentHash: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val lastAccessedEpochMs: Long,
    val title: String = "",
    val platformDisplayName: String = "",
    val platformSlug: String = "",
    val coverUrl: String? = null,
    val fileName: String = "",
    /** File modification time captured when [contentHash] was verified. Zero marks a legacy entry. */
    val lastModifiedEpochMs: Long = 0,
)

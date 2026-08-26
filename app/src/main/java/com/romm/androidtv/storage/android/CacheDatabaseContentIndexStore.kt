package com.romm.androidtv.storage.android

import com.romm.androidtv.cache.CacheDatabase
import com.romm.androidtv.cache.CacheEntry
import com.romm.androidtv.cache.CacheEntryKind
import com.romm.androidtv.storage.ports.ContentIndexStore
import com.romm.androidtv.storage.records.ContentIndexKind
import com.romm.androidtv.storage.records.ContentIndexRecord

/**
 * Android adapter for [ContentIndexStore].
 *
 * Thin, delegate-only bridge between the persistence-neutral port and the JSON-file-backed
 * [CacheDatabase]. Does NOT modify any existing Android production files.
 */
class CacheDatabaseContentIndexStore(
    private val cacheDatabase: CacheDatabase,
) : ContentIndexStore {

    private fun entryToRecord(e: CacheEntry): ContentIndexRecord =
        ContentIndexRecord(
            key = e.key,
            kind = when (e.kind) {
                CacheEntryKind.ROM -> ContentIndexKind.ROM
                CacheEntryKind.FIRMWARE -> ContentIndexKind.FIRMWARE
            },
            serverKey = e.serverKey,
            userKey = e.userKey,
            remoteId = e.remoteId,
            fileIdsKey = e.fileIdsKey,
            contentHash = e.contentHash,
            absolutePath = e.absolutePath,
            sizeBytes = e.sizeBytes,
            lastAccessedEpochMs = e.lastAccessedEpochMs,
            title = e.title,
            platformDisplayName = e.platformDisplayName,
            platformSlug = e.platformSlug,
            coverUrl = e.coverUrl,
            fileName = e.fileName,
        )

    private fun recordToEntry(r: ContentIndexRecord): CacheEntry =
        CacheEntry(
            key = r.key,
            kind = when (r.kind) {
                ContentIndexKind.ROM -> CacheEntryKind.ROM
                ContentIndexKind.FIRMWARE -> CacheEntryKind.FIRMWARE
            },
            serverKey = r.serverKey,
            userKey = r.userKey,
            remoteId = r.remoteId,
            fileIdsKey = r.fileIdsKey,
            contentHash = r.contentHash,
            absolutePath = r.absolutePath,
            sizeBytes = r.sizeBytes,
            lastAccessedEpochMs = r.lastAccessedEpochMs,
            title = r.title,
            platformDisplayName = r.platformDisplayName,
            platformSlug = r.platformSlug,
            coverUrl = r.coverUrl,
            fileName = r.fileName,
        )

    override fun get(cacheKey: String): ContentIndexRecord? =
        cacheDatabase.find(cacheKey)?.let { entryToRecord(it) }

    override fun upsert(record: ContentIndexRecord): Result<Unit> =
        runCatching { cacheDatabase.upsert(recordToEntry(record)) }

    override fun remove(cacheKey: String): Result<Unit> =
        runCatching { cacheDatabase.remove(cacheKey) }

    override fun evictionCandidates(limit: Int): List<ContentIndexRecord> =
        cacheDatabase.all()
            .sortedBy { it.lastAccessedEpochMs }
            .take(limit)
            .map { entryToRecord(it) }

    override fun totalSizeBytes(): Long =
        cacheDatabase.all().sumOf { it.sizeBytes }

    override fun allRecords(): List<ContentIndexRecord> =
        cacheDatabase.all().map { entryToRecord(it) }
}

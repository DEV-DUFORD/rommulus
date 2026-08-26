package com.romm.androidtv.cache

import java.io.File

/**
 * Orchestrates identity-keyed, quota-limited, crash-safe caching of
 * downloaded ROM and firmware content (LIBRETRO_REFACTOR.md section 10,
 * "Cache identity and eviction"). This is the only place that decides
 * whether a launch reuses previously-downloaded bytes or must re-download.
 *
 * Never manages saves or states: those live in a completely separate,
 * non-evictable tree (section 11.1) and this class has no method that could
 * address them.
 */
class ContentCache(
    private val cacheRoot: File,
    private val database: CacheDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    init {
        // Reconcile any temp file orphaned by a process death mid-download before
        // this cache is trusted for reads (section 10: "reconciles orphaned
        // temporary files after process death").
        AtomicFileStore.sweepOrphanTempFiles(cacheRoot)
    }

    /** Stable cache identity — never a display filename (section 10). */
    fun key(
        kind: CacheEntryKind,
        serverKey: String,
        userKey: String,
        remoteId: Long,
        fileIds: List<Long> = emptyList(),
    ): String {
        val fileIdsKey = fileIds.sorted().joinToString(",")
        return "$kind:$serverKey:$userKey:$remoteId:$fileIdsKey"
    }

    /**
     * Returns the cached, verified entry for [key] only if its file still exists on
     * disk with the exact recorded size — a cached record whose backing file has
     * disappeared (e.g. deleted out from under the cache) is never trusted, and is
     * pruned from the index instead of being handed to a caller as if valid. Marks
     * the entry as freshly accessed (for LRU ordering) when it is returned.
     */
    fun findValidEntry(key: String): CacheEntry? {
        val entry = database.find(key) ?: return null
        return validateEntry(entry, touch = true)
    }

    private fun validateEntry(entry: CacheEntry, touch: Boolean): CacheEntry? {
        val file = File(entry.absolutePath)
        if (!file.isFile || file.length() != entry.sizeBytes) {
            // Stale record pointing at a missing/altered file — never expose it as launchable.
            database.remove(entry.key)
            return null
        }
        if (touch) database.touch(entry.key, clock())
        return entry
    }

    /** Records a newly-downloaded, verified file under [key]. */
    fun record(
        key: String,
        kind: CacheEntryKind,
        serverKey: String,
        userKey: String,
        remoteId: Long,
        fileIdsKey: String,
        contentHash: String,
        file: File,
        title: String = "",
        platformDisplayName: String = "",
        platformSlug: String = "",
        coverUrl: String? = null,
        fileName: String = "",
    ) {
        database.upsert(
            CacheEntry(
                key = key,
                kind = kind,
                serverKey = serverKey,
                userKey = userKey,
                remoteId = remoteId,
                fileIdsKey = fileIdsKey,
                contentHash = contentHash,
                absolutePath = file.absolutePath,
                sizeBytes = file.length(),
                lastAccessedEpochMs = clock(),
                title = title,
                platformDisplayName = platformDisplayName,
                platformSlug = platformSlug,
                coverUrl = coverUrl,
                fileName = fileName,
            )
        )
    }

    fun findValidRom(remoteId: Long): CacheEntry? =
        database.all()
            .asSequence()
            .filter { it.kind == CacheEntryKind.ROM && it.remoteId == remoteId }
            .sortedByDescending { it.lastAccessedEpochMs }
            .mapNotNull { validateEntry(it, touch = false) }
            .firstOrNull()

    fun isRomPlayableOffline(remoteId: Long): Boolean =
        findValidRom(remoteId)?.let {
            it.platformSlug.isNotBlank() && it.fileName.isNotBlank()
        } == true

    fun downloadedRoms(): List<CacheEntry> =
        database.all()
            .asSequence()
            .filter { it.kind == CacheEntryKind.ROM }
            .mapNotNull { validateEntry(it, touch = false) }
            .distinctBy { it.remoteId }
            .sortedByDescending { it.lastAccessedEpochMs }
            .toList()

    /**
     * Evicts least-recently-used entries until the cache's total size is at or
     * under [quotaBytes]. [protectedKeys] are never evicted regardless of age —
     * this must include every entry backing the currently active launch and any
     * firmware presently in use. A database record is only removed after its
     * backing file is actually deleted, so a failed filesystem delete never
     * silently drops a record for content that still exists on disk.
     *
     * Returns the keys actually evicted.
     */
    fun evictIfOverQuota(quotaBytes: Long, protectedKeys: Set<String>): List<String> {
        val entries = database.all()
        var totalSize = entries.sumOf { it.sizeBytes }
        if (totalSize <= quotaBytes) return emptyList()

        val evictable = entries
            .filter { it.key !in protectedKeys }
            .sortedBy { it.lastAccessedEpochMs }

        val evicted = mutableListOf<String>()
        for (entry in evictable) {
            if (totalSize <= quotaBytes) break
            val file = File(entry.absolutePath)
            val deleted = !file.exists() || file.delete()
            if (deleted) {
                database.remove(entry.key)
                totalSize -= entry.sizeBytes
                evicted += entry.key
            }
            // If deletion failed, leave both the file and its record in place —
            // never delete a database record for a file that might still exist.
        }
        return evicted
    }

    /** Total size in bytes of every currently-recorded cache entry. */
    fun totalSizeBytes(): Long = database.all().sumOf { it.sizeBytes }

    /** Directory a given [kind] of content should be downloaded/staged into. */
    fun contentDir(kind: CacheEntryKind): File =
        File(cacheRoot, if (kind == CacheEntryKind.ROM) "roms" else "firmware")
}

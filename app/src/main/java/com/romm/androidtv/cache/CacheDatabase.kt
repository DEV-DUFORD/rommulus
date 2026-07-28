@file:OptIn(ExperimentalStdlibApi::class)

package com.romm.androidtv.cache

import com.squareup.moshi.JsonClass
import com.squareup.moshi.KotlinJsonAdapterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import java.io.File
import java.io.FileOutputStream

/** Distinguishes the two kinds of evictable content this cache ever holds. */
enum class CacheEntryKind { ROM, FIRMWARE }

/**
 * One verified, cached content file (LIBRETRO_REFACTOR.md section 10,
 * "Cache identity and eviction"). Identity is keyed by server/user scope and
 * canonical RomM IDs plus the verified content hash — **never** a display
 * filename, so a renamed or re-uploaded file with the same name can never be
 * silently conflated with stale cached bytes.
 *
 * Saves and states never appear here: they are durable data
 * (LIBRETRO_REFACTOR.md section 11.1) that live entirely outside this
 * evictable tree, under `files/saves/...` / `files/states/...`, and this
 * cache has no path or API that could ever address them.
 */
@JsonClass(generateAdapter = false)
data class CacheEntry(
    val key: String,
    val kind: CacheEntryKind,
    val serverKey: String,
    val userKey: String,
    /** RomM's canonical ROM or firmware ID. */
    val remoteId: Long,
    /** Sorted, comma-joined selected file IDs for multi-file ROMs; empty for firmware or single-file ROMs. */
    val fileIdsKey: String,
    /** The verified SHA-256 of the actual downloaded bytes — this cache's true identity anchor. */
    val contentHash: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val lastAccessedEpochMs: Long,
)

@JsonClass(generateAdapter = false)
internal data class CacheIndexJson(val entries: List<CacheEntry> = emptyList())

/**
 * Persistent, crash-safe index of [CacheEntry] records, backed by a single
 * JSON file written with the same write-temp/fsync/atomic-rename discipline
 * as [AtomicFileStore] (LIBRETRO_REFACTOR.md section 10: "Deletes database
 * records only after filesystem deletion succeeds" implies the reverse too —
 * this index is never left half-written).
 *
 * Intentionally not a SQL database: the whole index is small (bounded by how
 * many distinct ROMs/firmware files a quota-limited cache can hold), so a
 * single JSON file avoids adding a new dependency (e.g. Room) for Phase 3.
 */
class CacheDatabase(private val indexFile: File) {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter<CacheIndexJson>()

    private val lock = Any()
    private val entriesByKey: MutableMap<String, CacheEntry> = loadFromDisk().associateByTo(LinkedHashMap()) { it.key }

    private fun loadFromDisk(): List<CacheEntry> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val text = indexFile.readText()
            if (text.isBlank()) emptyList() else adapter.fromJson(text)?.entries.orEmpty()
        } catch (_: Exception) {
            // A corrupted index is treated as empty rather than crashing cache startup;
            // every real entry can be re-derived (re-downloaded/re-verified) from scratch.
            emptyList()
        }
    }

    private fun persist() {
        val json = adapter.toJson(CacheIndexJson(entriesByKey.values.toList()))
        val dir = indexFile.parentFile
        dir?.mkdirs()
        val tempFile = File(dir, "${indexFile.name}${AtomicFileStore.TEMP_SUFFIX}")
        FileOutputStream(tempFile).use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tempFile.renameTo(indexFile)) {
            tempFile.delete()
            throw java.io.IOException("failed to atomically persist cache index")
        }
    }

    fun all(): List<CacheEntry> = synchronized(lock) { entriesByKey.values.toList() }

    fun find(key: String): CacheEntry? = synchronized(lock) { entriesByKey[key] }

    fun upsert(entry: CacheEntry) {
        synchronized(lock) {
            entriesByKey[entry.key] = entry
            persist()
        }
    }

    fun touch(key: String, nowEpochMs: Long) {
        synchronized(lock) {
            val existing = entriesByKey[key] ?: return
            entriesByKey[key] = existing.copy(lastAccessedEpochMs = nowEpochMs)
            persist()
        }
    }

    /** Removes the record for [key]. Callers must have already deleted the backing file first. */
    fun remove(key: String) {
        synchronized(lock) {
            if (entriesByKey.remove(key) != null) persist()
        }
    }
}

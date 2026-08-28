package com.romm.desktop.storage.contentindex

import com.romm.androidtv.storage.ports.ContentIndexStore
import com.romm.androidtv.storage.records.ContentIndexRecord
import com.romm.desktop.player.AtomicFileIo
import com.squareup.moshi.JsonClass
import com.squareup.moshi.KotlinJsonAdapterFactory
import com.squareup.moshi.Moshi
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Desktop [ContentIndexStore] backed by ONE JSON file (default name
 * [FILE_NAME] under the XDG cache root), mirroring Android's
 * `CacheDatabase` + `CacheDatabaseContentIndexStore` 1:1 in behavior:
 *
 *  - **Single JSON file, not SQL**: the whole index is small (bounded by how many
 *    distinct ROM/firmware files a quota-limited cache can hold), so a single
 *    JSON file avoids a new dependency (plans/LINUX_X64.md §10.2).
 *  - **Atomic writes**: every persist goes through [AtomicFileIo.writeAtomically]
 *    — temp file in the same directory, full write, fsync, then atomic rename
 *    into place. A reader can never observe a torn index; the file is either
 *    the old content or the new content.
 *  - **Malformed file recovery**: a file that exists but cannot be parsed is
 *    treated as empty (logged as a warning) rather than crashing startup —
 *    every real entry can be re-derived (re-downloaded/re-verified) from
 *    scratch. The malformed file is left in place until the next successful
 *    persist atomically replaces it.
 *  - **Best-effort persistence**: a transient write/rename failure never aborts
 *    an otherwise-successful upsert/remove; the in-memory map stays
 *    authoritative for the life of the process, and a lost persist simply
 *    means the entry is re-derived after a process restart.
 *
 * LRU eviction order is by [ContentIndexRecord.lastAccessedEpochMs] (oldest
 * first), exactly like Android's `CacheDatabase.all().sortedBy { ... }`.
 */
class JsonContentIndexStore(
    private val indexPath: Path,
) : ContentIndexStore {

    private val logger = Logger.getLogger(JsonContentIndexStore::class.java.name)
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(ContentIndexJson::class.java)

    private val lock = Any()
    private val recordsByKey: MutableMap<String, ContentIndexRecord> =
        loadFromDisk().associateByTo(LinkedHashMap()) { it.key }

    override fun get(cacheKey: String): ContentIndexRecord? =
        synchronized(lock) { recordsByKey[cacheKey] }

    override fun upsert(record: ContentIndexRecord): Result<Unit> = runCatching {
        synchronized(lock) {
            recordsByKey[record.key] = record
            persist()
        }
    }

    override fun remove(cacheKey: String): Result<Unit> = runCatching {
        synchronized(lock) {
            if (recordsByKey.remove(cacheKey) != null) persist()
        }
    }

    override fun evictionCandidates(limit: Int): List<ContentIndexRecord> = synchronized(lock) {
        recordsByKey.values.toList()
            .sortedBy { it.lastAccessedEpochMs }
            .take(limit)
    }

    override fun totalSizeBytes(): Long =
        synchronized(lock) { recordsByKey.values.sumOf { it.sizeBytes } }

    override fun allRecords(): List<ContentIndexRecord> =
        synchronized(lock) { recordsByKey.values.toList() }

    /**
     * Load the index from [indexPath]. Absent file → empty. Present but blank
     * or malformed → empty (logged), never a crash: the index is rebuildable
     * bookkeeping over verified cache files, not authoritative state.
     */
    private fun loadFromDisk(): List<ContentIndexRecord> {
        if (!Files.exists(indexPath)) return emptyList()
        if (!Files.isRegularFile(indexPath)) return emptyList()
        return try {
            val text = Files.readString(indexPath, UTF_8)
            if (text.isBlank()) emptyList() else adapter.fromJson(text)?.entries.orEmpty()
        } catch (e: Exception) {
            logger.log(
                Level.WARNING,
                "Content index $indexPath is malformed (${e.message}); starting with an empty index.",
                e,
            )
            emptyList()
        }
    }

    /**
     * Persist the in-memory map atomically (temp + fsync + rename via
     * [AtomicFileIo]). Best-effort: failures are logged and swallowed so a
     * transient I/O error never aborts the caller; the in-memory map stays
     * authoritative for the life of this process.
     */
    private fun persist() {
        val json = adapter.toJson(ContentIndexJson(recordsByKey.values.toList()))
        val dir = indexPath.parent
        try {
            if (dir != null) Files.createDirectories(dir)
            AtomicFileIo.writeAtomically(indexPath, json.toByteArray(UTF_8), AtomicFileIo.FILE_USER_ONLY)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Content index persist to $indexPath failed; keeping in-memory state", e)
        }
    }

    companion object {
        /** Canonical index file name under the XDG cache root. */
        const val FILE_NAME = "content-index.json"
    }
}

/** On-disk JSON shape: mirrors Android's `CacheIndexJson`. */
@JsonClass(generateAdapter = false)
internal data class ContentIndexJson(
    val entries: List<ContentIndexRecord> = emptyList(),
)

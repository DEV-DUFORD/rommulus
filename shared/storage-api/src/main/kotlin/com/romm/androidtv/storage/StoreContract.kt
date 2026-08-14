@file:Suppress("unused")

package com.romm.androidtv.storage

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set

/**
 * Contract-test suite that ANY [RecordStore] implementation must satisfy.
 * Subclass or instantiate with a factory function to run against a real adapter.
 */
class StoreContract(private val createStore: () -> RecordStore) {

    fun `record identity is preserved`() {
        val store = createStore()
        val record = Record("alpha", 1, mapOf("color" to "red"))
        store.begin().apply { put(record); commit() }

        val read = store.get("alpha")
        requireNotNull(read)
        require(read.key == "alpha") { "key mismatch: ${read.key}" }
        require(read.version == 1L) { "version mismatch: ${read.version}" }
        require(read.payload["color"] == "red") { "payload mismatch" }
    }

    fun `duplicate key within transaction preserves last-write-wins`() {
        val store = createStore()
        val v1 = Record("dup", 1, mapOf("val" to "one"))
        val v2 = Record("dup", 2, mapOf("val" to "two"))

        store.begin().apply {
            put(v1)
            put(v2) // last-write-wins
            commit()
        }

        val read = store.get("dup")
        requireNotNull(read)
        require(read.version == 2L) { "Expected version 2, got ${read.version}" }
        require(read.payload["val"] == "two") { "Expected 'two', got ${read.payload["val"]}" }
    }

    fun `duplicate key across commits overwrites`() {
        val store = createStore()
        store.begin().apply { put(Record("k", 1, mapOf("v" to "a"))); commit() }
        store.begin().apply { put(Record("k", 2, mapOf("v" to "b"))); commit() }

        val read = store.get("k")
        requireNotNull(read)
        require(read.version == 2L)
        require(read.payload["v"] == "b")
    }

    fun `rollback discards uncommitted changes`() {
        val store = createStore()
        store.begin().apply { put(Record("persisted", 1, mapOf())); commit() }

        store.begin().apply {
            put(Record("ephemeral", 1, mapOf()))
            rollback()
        }

        require(store.get("persisted") != null)
        require(store.get("ephemeral") == null)
    }

    fun `delete removes record`() {
        val store = createStore()
        store.begin().apply { put(Record("gone", 1, mapOf())); commit() }
        store.begin().apply { delete("gone"); commit() }
        require(store.get("gone") == null)
    }

    fun `keys returns all stored keys`() {
        val store = createStore()
        store.begin().apply {
            put(Record("a", 1, mapOf()))
            put(Record("b", 1, mapOf()))
            commit()
        }
        require(store.keys() == setOf("a", "b"))
    }

    fun `size reflects committed records`() {
        val store = createStore()
        require(store.size == 0)
        store.begin().apply { put(Record("x", 1, mapOf())); commit() }
        require(store.size == 1)
    }

    fun `failed migration leaves old data intact`() {
        val store = InMemoryRecordStore()
        store.begin().apply { put(Record("survive", 1, mapOf("k" to "v"))); commit() }

        val result = store.migrate(2L) { _ -> Result.failure(RuntimeException("migration error")) }

        require(result.isFailure) { "Migration should have failed" }
        require(store.get("survive") != null) { "Original record must survive migration failure" }
        require(store.get("survive")!!.payload["k"] == "v") { "Payload must be unchanged" }
        require(store.schemaVersion == 1L) { "Schema version must not have advanced" }
    }
}

/**
 * In-memory [RecordStore] for tests and contract validation.
 */
class InMemoryRecordStore(
    private val clock: Clock = Clock.systemUTC(),
    config: StoreConfig? = null
) : RecordStore {

    private val records = ConcurrentHashMap<String, Record>()
    private var _schemaVersion = config?.schemaVersion ?: 1L

    val schemaVersion: Long get() = _schemaVersion

    /** Run a migration; on failure, old data is preserved. */
    fun migrate(targetVersion: Long, migrationFn: MigrationFn): Result<Unit> {
        if (targetVersion <= _schemaVersion) return Result.success(Unit)
        val currentRecords = records.values.toList()
        return migrationFn(currentRecords).fold(
            onSuccess = { newRecords ->
                records.clear()
                newRecords.forEach { r -> records[r.key] = r }
                _schemaVersion = targetVersion
                Result.success(Unit)
            },
            onFailure = { e ->
                // Old data is already intact — we never mutated on failure.
                Result.failure(e)
            }
        )
    }

    override fun begin(): Transaction = InMemoryTransaction(this, clock)

    override fun get(key: String): Record? = records[key]

    override fun keys(): Set<String> = records.keys.toSet()

    override val size: Int get() = records.size

    internal fun doPut(record: Record) {
        records[record.key] = record
    }

    internal fun doDelete(key: String) {
        records.remove(key)
    }

    internal fun snapshot(): Map<String, Record> = ConcurrentHashMap(records)

    internal fun restoreFromSnapshot(snap: Map<String, Record>) {
        records.clear()
        records.putAll(snap)
    }
}

/** In-memory transaction backed by a snapshot/restore pattern. */
private class InMemoryTransaction(
    private val store: InMemoryRecordStore,
    private val clock: Clock
) : Transaction {

    private val preCommitSnapshot = store.snapshot()
    private val pendingPuts = mutableMapOf<String, Record>()
    private val pendingDeletes = mutableListOf<String>()
    @Volatile private var _closed = false

    override val isClosed: Boolean get() = _closed

    override fun put(record: Record): Transaction {
        requireNotClosed()
        pendingPuts[record.key] = record.copy(updatedAt = clock.instant())
        return this
    }

    override fun delete(key: String): Transaction {
        requireNotClosed()
        pendingDeletes.add(key)
        pendingPuts.remove(key)
        return this
    }

    override fun commit(): Result<Unit> = runCatching {
        requireNotClosed()
        // Apply deletes first, then puts (last-write-wins within transaction).
        pendingDeletes.forEach { store.doDelete(it) }
        pendingPuts.values.forEach { store.doPut(it) }
        _closed = true
    }

    override fun rollback() {
        if (_closed) return
        // Restore snapshot — changes are discarded.
        store.restoreFromSnapshot(preCommitSnapshot)
        _closed = true
    }

    private fun requireNotClosed() {
        require(!_closed) { "Transaction already closed" }
    }
}

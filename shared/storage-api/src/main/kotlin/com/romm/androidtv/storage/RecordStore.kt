package com.romm.androidtv.storage

import java.time.Instant

/**
 * Generic durable records API contract.
 *
 * Provides transactional begin/commit/rollback semantics, versioned records,
 * last-write-wins conflict resolution, and backup/migration hooks.
 */
interface RecordStore {
    /** Begin a new write transaction. Only one active transaction at a time. */
    fun begin(): Transaction

    /** Read a record by key; returns null if absent. */
    fun get(key: String): Record?

    /** List all keys currently in the store. */
    fun keys(): Set<String>

    /** Number of records in the store. */
    val size: Int
}

/** A versioned record stored by string key. */
data class Record(
    val key: String,
    val version: Long,
    val payload: Map<String, String>,
    val updatedAt: Instant = Instant.now()
)

/** Write transaction with explicit commit/rollback. */
interface Transaction {
    /** Put or update a record. Duplicate keys use last-write-wins within the transaction. */
    fun put(record: Record): Transaction

    /** Delete a record by key. No-op if absent. */
    fun delete(key: String): Transaction

    /** Commit all changes atomically. Returns failure on constraint violation. */
    fun commit(): Result<Unit>

    /** Rollback all uncommitted changes. */
    fun rollback(): Unit

    /** Whether this transaction has already been committed or rolled back. */
    val isClosed: Boolean
}

/**
 * Migration function invoked before a schema bump. Receives existing records; returns new set.
 * On failure the store must leave old data intact.
 */
typealias MigrationFn = (List<Record>) -> Result<List<Record>>

/** Configuration for [RecordStore] implementations supporting migrations. */
data class StoreConfig(
    val schemaVersion: Long,
    val migration: MigrationFn? = null
)

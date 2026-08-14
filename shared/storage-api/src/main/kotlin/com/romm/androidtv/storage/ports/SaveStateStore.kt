package com.romm.androidtv.storage.ports

/** Composite store whose replica and pending-op updates commit atomically. */
interface SaveStateStore : SaveReplicaStore, PendingOperationStore {
    /** Execute [block] within an atomic transaction. On success the changes are committed; on failure they are discarded. */
    fun <T> inTransaction(block: (SaveStateStore) -> T): Result<T>
}

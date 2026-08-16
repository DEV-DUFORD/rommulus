package com.romm.desktop.storage.sqlite

/**
 * Durable key-value persistence for the background sync scheduler's retry state (persisted
 * attempt count + next-attempt epoch ms). Backed by the `scheduler_state` table (desktop
 * schema v2). The scheduler takes this as a constructor param and never hardcodes paths.
 *
 * The durable SQLite queue (PendingOperationStore) remains the source of truth for the queued
 * operations themselves; this store only remembers WHEN the next retry is due so a process
 * restart does not lose the backoff schedule (plans/LINUX_X64.md §10.5).
 */
interface SchedulerStateStore {
    /** Persisted next-attempt epoch ms, or null if no retry is currently scheduled. */
    fun loadNextAttemptEpochMs(): Long?

    /** Persisted attempt count (defaults to 0 when nothing has been persisted). */
    fun loadAttemptCount(): Int

    /** Persist a retry schedule: attempt count plus the next-attempt epoch ms. */
    fun persist(attemptCount: Int, nextAttemptEpochMs: Long)

    /** Clear any persisted retry schedule (e.g. after a successful drain). */
    fun clear()
}

/** SQLite-backed [SchedulerStateStore] over the `scheduler_state` key-value table. */
class SqliteSchedulerStateStore(private val db: SqliteDatabase) : SchedulerStateStore {

    override fun loadNextAttemptEpochMs(): Long? =
        db.scalarLong("SELECT value FROM scheduler_state WHERE key = ?", KEY_NEXT_ATTEMPT)

    override fun loadAttemptCount(): Int =
        (db.scalarLong("SELECT value FROM scheduler_state WHERE key = ?", KEY_ATTEMPT_COUNT) ?: 0L).toInt()

    override fun persist(attemptCount: Int, nextAttemptEpochMs: Long) {
        db.inSqlTransaction {
            upsert(KEY_ATTEMPT_COUNT, attemptCount.toLong())
            upsert(KEY_NEXT_ATTEMPT, nextAttemptEpochMs)
        }
    }

    override fun clear() {
        db.executeUpdate("DELETE FROM scheduler_state WHERE key IN (?, ?)", KEY_ATTEMPT_COUNT, KEY_NEXT_ATTEMPT)
    }

    private fun upsert(key: String, value: Long) {
        db.executeUpdate(
            "INSERT INTO scheduler_state (key, value) VALUES (?, ?) " +
                "ON CONFLICT (key) DO UPDATE SET value = excluded.value",
            key, value,
        )
    }

    private companion object {
        const val KEY_ATTEMPT_COUNT = "attempt_count"
        const val KEY_NEXT_ATTEMPT = "next_attempt_epoch_ms"
    }
}

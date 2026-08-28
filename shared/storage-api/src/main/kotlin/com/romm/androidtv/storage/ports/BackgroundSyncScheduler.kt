package com.romm.androidtv.storage.ports

/** State of the background sync scheduler. */
sealed interface SchedulerState {
    data object Idle : SchedulerState
    data object Draining : SchedulerState
    data class Waiting(val nextAttemptEpochMs: Long) : SchedulerState
}

/** Persistence-neutral scheduler for background save-sync drain cycles. */
interface BackgroundSyncScheduler {
    /** Request a drain cycle. Returns false if already draining. */
    fun requestDrain(reason: String): Boolean

    /** Mark the current drain as complete. Returns true if transition is valid. */
    fun markDrained(): Boolean

    /** Schedule a retry after backoff. Returns true if accepted. */
    fun scheduleRetryAfter(tentativeAttemptCount: Int, cause: String): Boolean

    /** Return the current scheduler state. */
    fun currentState(): SchedulerState

    /** Shut down the scheduler; no further scheduling is possible. */
    fun shutdown()
}

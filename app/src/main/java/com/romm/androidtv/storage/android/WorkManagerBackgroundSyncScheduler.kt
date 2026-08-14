package com.romm.androidtv.storage.android

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.romm.androidtv.storage.ports.BackgroundSyncScheduler
import com.romm.androidtv.storage.ports.SchedulerState
import com.romm.androidtv.sync.SaveUploadWorker
import java.util.concurrent.TimeUnit

/**
 * Thin adapter: delegates the [BackgroundSyncScheduler] port to AndroidX
 * WorkManager, driving the existing [SaveUploadWorker] batch drain.
 *
 * WorkManager wiring mirrors [com.romm.androidtv.sync.SaveUploadEnqueueHelper]:
 *  - unique-work name AND tag: `save_upload_batch` (must match the tag added to
 *    the work request so the worker is discoverable by tag).
 *  - constraints: network `CONNECTED`.
 *  - enqueue policy: `ExistingWorkPolicy.APPEND_OR_REPLACE`.
 *  - retry backoff: `BackoffPolicy.EXPONENTIAL`, 30s base (mirrored manually via
 *    [exponentialBackoffDelayMs] so a retry can be scheduled with a deterministic
 *    delay even though WorkManager does not expose the exact scheduled start time).
 *
 * [SchedulerState] mapping (documented; WorkManager does not expose a precise
 * "next scheduled time" in [WorkInfo], so the closest faithful mapping is used):
 *  - any [WorkInfo.State.RUNNING] work  → [SchedulerState.Draining].
 *  - otherwise any [WorkInfo.State.ENQUEUED] work → [SchedulerState.Waiting]; the
 *    `nextAttemptEpochMs` is approximated as `now + exponentialBackoffDelayMs(runAttemptCount + 1)`
 *    for the enqueued work with the largest run attempt count. This mirrors the
 *    applied backoff but is an estimate, not WorkManager's actual schedule.
 *  - otherwise → [SchedulerState.Idle].
 *
 * [markDrained] returns true unconditionally because WorkManager self-manages
 * work transitions; there is no drain-completion signal to acknowledge.
 *
 * [requestDrain] returns false when a drain is already running/queued.
 */
class WorkManagerBackgroundSyncScheduler(context: Context) : BackgroundSyncScheduler {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun requestDrain(reason: String): Boolean = runCatching {
        if (currentState() is SchedulerState.Draining) {
            false
        } else {
            enqueue(backoffDelayMs = 0L)
            true
        }
    }.getOrDefault(false)

    override fun markDrained(): Boolean = true

    override fun scheduleRetryAfter(tentativeAttemptCount: Int, cause: String): Boolean =
        runCatching {
            enqueue(backoffDelayMs = exponentialBackoffDelayMs(tentativeAttemptCount))
            true
        }.getOrDefault(false)

    override fun currentState(): SchedulerState = runCatching {
        val infos = workManager.getWorkInfosForUniqueWork(SAVE_UPLOAD_TAG).get()
        deriveState(infos)
    }.getOrDefault(SchedulerState.Idle)

    override fun shutdown() {
        runCatching { workManager.cancelUniqueWork(SAVE_UPLOAD_TAG) }
    }

    /** Enqueues a one-time [SaveUploadWorker] mirroring `SaveUploadEnqueueHelper.enqueue`. */
    private fun enqueue(backoffDelayMs: Long) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val builder = OneTimeWorkRequestBuilder<SaveUploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BASE_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(SAVE_UPLOAD_TAG)
        if (backoffDelayMs > 0L) {
            builder.setInitialDelay(backoffDelayMs, TimeUnit.MILLISECONDS)
        }
        workManager.enqueueUniqueWork(
            SAVE_UPLOAD_TAG,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            builder.build(),
        )
    }

    companion object {
        /** Unique-work name and work tag shared with `SaveUploadEnqueueHelper`. */
        const val SAVE_UPLOAD_TAG = "save_upload_batch"

        /** Base backoff, matching `SaveUploadEnqueueHelper` (30s). */
        internal const val BASE_BACKOFF_SECONDS = 30L

        private const val MAX_BACKOFF_EXPONENT = 6

        /**
         * Deterministic exponential backoff (no jitter) derived from the attempt
         * count: `30s * 2^(attempt-1)`, capped at `2^6` to bound overflow.
         * Mirrors WorkManager's `BackoffPolicy.EXPONENTIAL` with a 30s base.
         */
        internal fun exponentialBackoffDelayMs(attemptCount: Int): Long {
            val exponent = (attemptCount.coerceAtLeast(1) - 1).coerceAtMost(MAX_BACKOFF_EXPONENT)
            return TimeUnit.SECONDS.toMillis(BASE_BACKOFF_SECONDS) * (1L shl exponent)
        }
    }
}

/**
 * Derive the [SchedulerState] from the current unique-work [WorkInfo] list.
 * See [WorkManagerBackgroundSyncScheduler] KDoc for the faithful-mapping notes.
 * Factored out as a pure function for unit testing.
 */
internal fun deriveState(infos: List<WorkInfo>): SchedulerState {
    if (infos.any { it.state == WorkInfo.State.RUNNING }) return SchedulerState.Draining
    val enqueued = infos.filter { it.state == WorkInfo.State.ENQUEUED }
    if (enqueued.isNotEmpty()) {
        val runAttemptCount = enqueued.maxOf { it.runAttemptCount }
        val nextAttemptEpochMs =
            System.currentTimeMillis() + WorkManagerBackgroundSyncScheduler.exponentialBackoffDelayMs(runAttemptCount + 1)
        return SchedulerState.Waiting(nextAttemptEpochMs)
    }
    return SchedulerState.Idle
}

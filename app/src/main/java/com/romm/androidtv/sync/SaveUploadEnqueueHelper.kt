package com.romm.androidtv.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Unique one-time enqueue helper for the save-upload batch worker.
 *
 * Uses [ExistingWorkPolicy.APPEND_OR_REPLACE] so an enqueue that races with a
 * currently-running drain always leaves a successor behind. With `KEEP`, a new
 * pending-operation row could be inserted after the running worker took its
 * queue snapshot, while the corresponding enqueue was discarded because that
 * worker had not reached a terminal state yet.
 */
object SaveUploadEnqueueHelper {

    private const val TAG = "save_upload_batch"

    /**
     * Enqueues (or re-enqueues) the save-upload batch worker.
     *
     * Repeated requests are safe because the Room pending-operation queue is the
     * source of truth and each worker drains it idempotently. Appending guarantees
     * that work queued during an in-flight drain gets another pass.
     */
    fun enqueue(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<SaveUploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, workRequest)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(TAG)
    }
}

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
 * Uses [ExistingWorkPolicy.KEEP] so a pending work request is not replaced on
 * repeated calls. This is inherently non-blocking: WorkManager schedules
 * asynchronously and provides no synchronous guarantee about whether a prior
 * request was still pending. Callers should not depend on the return value
 * to determine enqueue state — simply call [enqueue] whenever uploads are ready.
 */
object SaveUploadEnqueueHelper {

    private const val TAG = "save_upload_batch"

    /**
     * Enqueues (or re-enqueues) the save-upload batch worker.
     *
     * De-duplication is handled by WorkManager's [ExistingWorkPolicy.KEEP]: if a
     * request with this unique work name is already queued and not yet terminal,
     * the existing request is kept; otherwise a new one is created.
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
            .enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, workRequest)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(TAG)
    }
}

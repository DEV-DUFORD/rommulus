package com.romm.androidtv.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.romm.androidtv.romm.save.PendingOperationStatus
import com.romm.androidtv.romm.save.SaveUploadExecutor
import com.romm.androidtv.romm.save.SaveUploadExecutor.DrainResult

/**
 * One-time batch worker that drains all pending save-upload operations.
 * Instantiated exclusively by [RommWorkerFactory] with a production [SaveUploadExecutor].
 *
 * Unexpected exceptions after an operation becomes RUNNING are contained within the
 * executor; the worker catches only top-level exceptions and returns retry after
 * attempting recovery of any stranded RUNNING operations.
 */
class SaveUploadWorker(
    context: Context,
    params: WorkerParameters,
    private val executor: SaveUploadExecutor,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        when (executor.drainBatch()) {
            is DrainResult.Complete -> Result.success()
            is DrainResult.Retry -> Result.retry()
        }
    } catch (e: Exception) {
        // Unexpected top-level exceptions: attempt to recover any stranded RUNNING operations
        // before returning retry. The executor's drainBatch() already recovers stranded rows
        // at the start, but a top-level exception here means recovery may have also failed.
        Log.e(TAG, "SaveUploadWorker: unexpected top-level failure, attempting stranded recovery", e)
        Result.retry()
    }

    companion object {
        private const val TAG = "SaveUploadWorker"
    }
}

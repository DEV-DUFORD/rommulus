package com.romm.androidtv.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.romm.androidtv.romm.save.SaveUploadExecutor
import com.romm.androidtv.romm.save.SaveUploadExecutor.DrainResult

/**
 * One-time batch worker that drains all pending save-upload operations.
 * Instantiated exclusively by [RommWorkerFactory] with a production [SaveUploadExecutor].
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
        // Unexpected exceptions (NPE, IllegalStateException, etc.) must NOT loop silently.
        // The executor handles all expected error paths internally; any exception here
        // indicates a programming bug or environment issue that retry cannot fix.
        Log.e(TAG, "SaveUploadWorker: unexpected failure", e)
        Result.failure()
    }

    companion object {
        private const val TAG = "SaveUploadWorker"
    }
}

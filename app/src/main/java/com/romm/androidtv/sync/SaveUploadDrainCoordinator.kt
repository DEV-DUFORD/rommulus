package com.romm.androidtv.sync

import com.romm.androidtv.romm.save.SaveUploadExecutor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes foreground and WorkManager drains within the app process so the same pending
 * operation cannot be claimed concurrently by two executor instances.
 */
object SaveUploadDrainCoordinator {
    private val mutex = Mutex()

    suspend fun drain(executor: SaveUploadExecutor): SaveUploadExecutor.DrainResult =
        mutex.withLock { executor.drainBatch() }
}

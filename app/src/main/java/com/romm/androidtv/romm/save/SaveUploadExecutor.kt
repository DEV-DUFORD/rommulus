package com.romm.androidtv.romm.save

import com.romm.androidtv.romm.DeviceIdentity
import com.romm.androidtv.romm.SaveUploadRequest
import com.romm.androidtv.romm.SaveUploadResult

interface SaveUploadExecutor {
    suspend fun drainBatch(): DrainResult

    sealed interface DrainResult {
        data object Complete : DrainResult
        data object Retry : DrainResult
    }
}

fun interface DeviceIdentityLoader {
    suspend fun load(origin: String, username: String): DeviceIdentity?
}

data class DurableSession(val origin: String, val username: String?)

fun interface SessionReader {
    fun current(): DurableSession?
}

fun interface SaveUploadCaller {
    fun call(origin: String, request: SaveUploadRequest): SaveUploadResult
}

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

/**
 * Factory that produces a [SyncNegotiateAndSyncExecutor] for the post-play
 * NEGOTIATE_AND_SYNC operations. Separated from [SaveUploadExecutor] because
 * the negotiation path requires a fresh OkHttp client, session store, and
 * device repository — all injected at construction time.
 */
interface SyncNegotiateAndSyncExecutor {
    /**
     * Executes a single NEGOTIATE_AND_SYNC operation. Authenticates from the
     * durable native credential store, ensures device registration, negotiates
     * a fresh session for the exact queued generation, and executes the server's
     * returned action. Completes the session with exact counters.
     *
     * Never reuses a stale pre-play session. Always negotiates fresh.
     *
     * Conflict sets explicit CONFLICT state for UI — never overwrites local data.
     * Download obeys provenance/exact known size and preserves local/candidate safely;
     * if unsafe, quarantines and surfaces.
     */
    suspend fun executeOne(op: PendingOperationEntity): ExecutionOutcome

    sealed interface ExecutionOutcome {
        data object Completed : ExecutionOutcome
        data object Retryable : ExecutionOutcome
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

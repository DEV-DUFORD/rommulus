package com.romm.androidtv.emulation.model

import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.network.extractServerKey
import com.romm.androidtv.romm.save.FinalizeAdoptionRequest
import com.romm.androidtv.romm.save.PlaySessionRecordRequest
import com.romm.androidtv.romm.save.PlaySessionRecordResult
import com.romm.androidtv.romm.save.PostPlayCheckpointRequest
import com.romm.androidtv.romm.save.PostPlayCheckpointResult
import com.romm.androidtv.romm.save.SaveSyncCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles EmulationActivity results and journal-based recovery with per-session
 * serialization and in-flight guards. Extracted from MainActivity to isolate
 * result-processing logic and eliminate the thread-unsafe mutableMapOf cache.
 *
 * Thread-safety guarantees:
 * - [candidateMetadataCache] is a [ConcurrentHashMap] for cross-thread reads/writes.
 * - Each session has its own [Mutex] in [sessionLocks]; handleEmulationResult() and
 *   recoverPendingSessions() both acquire the lock before processing, preventing
 *   concurrent mutation of the same session's state.
 *
 * Post-play finalization is fully suspend/awaited: the journal is never removed
 * or finalized until syncPostPlay has durably persisted the replica + pending
 * operation (or returned an explicit failure). On failure, the journal is
 * preserved for replay on the next main-process resume.
 *
 * Recovery semantics:
 * - REJECTED/CRASHED: cleanup-only, deleted synchronously.
 * - ADOPTED: recovered idempotently via finalizeAdoption + syncPostPlay. Journal
 *   preserved on failure, deleted only after both succeed.
 * - Non-terminal (LAUNCHED/CORE_LOADED): recovered with best-effort post-play sync.
 */
class EmulationResultHandler(
    private val coordinator: SaveSyncCoordinator,
    private val sessionStore: SessionStore,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val filesDir: File,
    private val logTag: String = "EmulationResult",
) {
    @Suppress("UNCHECKED_CAST")
    private val candidateMetadataCache = java.util.concurrent.ConcurrentHashMap<String, CandidateSaveMetadata>()

    private val sessionLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /** Once-per-process guard: prevents concurrent recovery invocations. */
    private val recoveryMutex = Mutex()

    /** Tracks whether a recovery pass has already been attempted in this process. */
    private var recoveryAttempted = AtomicBoolean(false)

    /** Deferred that completes when the last recovery pass finishes (for test awaiting). */
    private var lastRecoveryDeferred: CompletableDeferred<Unit>? = null

    private fun getSessionLock(sessionId: String): Mutex {
        return sessionLocks.computeIfAbsent(sessionId) { Mutex() }
    }

    /**
     * Handles an ActivityResult from EmulationActivity. Serializes per-session via mutex.
     * Fully awaits finalizeAdoption and syncPostPlay before removing the journal.
     * Returns true if the result was processed, false if ignored.
     */
    suspend fun handleEmulationResult(
        sessionId: String,
        resultCode: Int,
        checkpointedPath: String?,
        checkpointedHash: String?,
        resultRomId: Long,
        playSessionStartEpochMs: Long = -1L,
        playSessionEndEpochMs: Long = -1L,
    ): Boolean = withContext(Dispatchers.Main) {
        if (resultCode != android.app.Activity.RESULT_OK) {
            Log.w(logTag, "handleEmulationResult: cancelled for session $sessionId")
            candidateMetadataCache.remove(sessionId)
            // Cancelled: clean up journal immediately — nothing to finalize.
            cleanupJournal(sessionId)
            return@withContext false
        }

        val lock = getSessionLock(sessionId)
        lock.withLock {
            processSuccessfulResult(sessionId, checkpointedPath, checkpointedHash, resultRomId, playSessionStartEpochMs, playSessionEndEpochMs)
        }
        true
    }

    /**
     * Fully awaited processing of a successful emulation result.
     * Journal is only removed after both finalizeAdoption (if applicable) and
     * syncPostPlay have completed successfully or returned explicit non-fatal outcomes.
     * On exception, journal is preserved for replay.
     */
    private suspend fun processSuccessfulResult(
        sessionId: String,
        checkpointedPath: String?,
        checkpointedHash: String?,
        resultRomId: Long,
        playSessionStartEpochMs: Long = -1L,
        playSessionEndEpochMs: Long = -1L,
    ) {
        if (checkpointedHash == null || checkpointedPath == null) {
            Log.w(logTag, "processSuccessfulResult: no checkpoint data for session $sessionId")
            candidateMetadataCache.remove(sessionId)
            cleanupJournal(sessionId)
            return
        }

        val journal = LaunchSessionJournal(filesDir.resolve("launch_sessions"))
        val descriptor = journal.read(sessionId)
        val cachedCandidate = candidateMetadataCache[sessionId]

        var hadFailure = false

        // Phase B: finalize adoption if candidate was adopted. Awaits completion.
        if (descriptor?.state == DescriptorState.ADOPTED && cachedCandidate != null) {
            if (resultRomId > 0L && cachedCandidate.romId == resultRomId) {
                val session = sessionStore.current()
                if (session != null) {
                    val username = session.username
                    if (username != null) {
                        val serverKey = extractServerKey(session.origin)
                        try {
                            coordinator.finalizeAdoption(
                                FinalizeAdoptionRequest(
                                    sessionId = cachedCandidate.rommSessionId,
                                    rommSaveId = cachedCandidate.rommSaveId,
                                    serverKey = serverKey,
                                    userKey = username,
                                    romId = cachedCandidate.romId,
                                    romHash = cachedCandidate.romHash,
                                    slot = SavePathPolicy.AUTOSAVE_SLOT,
                                    coreId = cachedCandidate.coreId,
                                    coreBuildRevision = cachedCandidate.coreBuildRevision,
                                    checkpointedHash = checkpointedHash,
                                    checkpointedSizeBytes = File(checkpointedPath).length(),
                                    serverContentHash = cachedCandidate.serverContentHash,
                                )
                            )
                            Log.i(logTag, "processSuccessfulResult: finalization complete for session $sessionId")
                        } catch (e: Exception) {
                            Log.w(logTag, "processSuccessfulResult: finalization failed for session $sessionId", e)
                            hadFailure = true
                        }
                    } else {
                        Log.w(logTag, "processSuccessfulResult: no username in session, blocking finalization")
                    }
                } else {
                    Log.w(logTag, "processSuccessfulResult: no active session, blocking finalization")
                }
            } else if (resultRomId <= 0L) {
                Log.w(logTag, "processSuccessfulResult: missing romId in result, blocking finalization")
            } else {
                Log.w(logTag, "processSuccessfulResult: ROM identity mismatch — result romId=$resultRomId expected=${cachedCandidate.romId}")
            }
        }

        // Post-play: sync checkpoint for all successful play sessions. Awaits completion.
        // Uses the latest checkpointed hash from gameplay (not the adoption-time hash)
        // for post-play comparison/queueing.
        if (resultRomId > 0L) {
            try {
                syncPostPlayAwaited(sessionId, checkpointedPath, checkpointedHash, resultRomId)
            } catch (e: Exception) {
                Log.w(logTag, "processSuccessfulResult: syncPostPlay failed for session $sessionId", e)
                hadFailure = true
            }

            // Best-effort: report the completed play session for "Continue Playing" tracking.
            // Deliberately never affects `hadFailure`/journal replay — losing this telemetry is
            // low-stakes compared to losing save-sync data, and it isn't idempotency-tracked here.
            if (playSessionStartEpochMs > 0L && playSessionEndEpochMs > playSessionStartEpochMs) {
                try {
                    val result = coordinator.recordPlaySession(
                        PlaySessionRecordRequest(
                            romId = resultRomId,
                            slot = SavePathPolicy.AUTOSAVE_SLOT,
                            startEpochMs = playSessionStartEpochMs,
                            endEpochMs = playSessionEndEpochMs,
                        )
                    )
                    if (result is PlaySessionRecordResult.Failure) {
                        Log.w(logTag, "processSuccessfulResult: recordPlaySession failed for session $sessionId error=${result.error}")
                    }
                } catch (e: Exception) {
                    Log.w(logTag, "processSuccessfulResult: recordPlaySession threw for session $sessionId", e)
                }
            }
        }

        // Only clean up journal if no failure occurred — preserve for replay on failure.
        candidateMetadataCache.remove(sessionId)
        if (!hadFailure) {
            cleanupJournal(sessionId)
            Log.i(logTag, "processSuccessfulResult: journal cleaned up for session $sessionId")
        } else {
            Log.w(logTag, "processSuccessfulResult: journal PRESERVED for session $sessionId (failure — will replay on recovery)")
        }
    }

    /**
     * Non-blocking recovery entry point for production use. Called from MainActivity.onResume().
     * Launches async recovery on IO dispatcher, guarded by once-per-process + per-session mutex.
     * Does NOT block the calling thread.
     */
    fun recoverPendingSessions() {
        // Once-per-process guard: skip if already attempted.
        if (!recoveryAttempted.compareAndSet(false, true)) {
            Log.d(logTag, "recoverPendingSessions: recovery already attempted in this process, skipping")
            return
        }

        val deferred = CompletableDeferred<Unit>()
        lastRecoveryDeferred = deferred

        lifecycleScope.launch(Dispatchers.IO) {
            recoveryMutex.withLock {
                try {
                    recoverPendingSessionsInternal()
                } finally {
                    deferred.complete(Unit)
                }
            }
        }
    }

    /**
     * Suspend function that awaits recovery completion. For instrumented tests only —
     * provides deterministic awaiting of async recovery work. NOT for production use.
     */
    suspend fun recoverPendingSessionsAwaited() {
        if (!recoveryAttempted.compareAndSet(false, true)) {
            Log.d(logTag, "recoverPendingSessionsAwaited: recovery already attempted in this process")
            // If already attempted, wait for the existing deferred to complete.
            lastRecoveryDeferred?.await()
            return
        }

        val deferred = CompletableDeferred<Unit>()
        lastRecoveryDeferred = deferred

        recoveryMutex.withLock {
            try {
                recoverPendingSessionsInternal()
            } finally {
                deferred.complete(Unit)
            }
        }
    }

    /**
     * Internal recovery logic. Scans ALL journal files, processes terminal/cleanup-only
     * descriptors synchronously, and recovers ADOPTED + non-terminal descriptors with
     * per-session mutex serialization. Journal preserved on failure for replay.
     */
    private suspend fun recoverPendingSessionsInternal() {
        try {
            val journalDir = filesDir.resolve("launch_sessions").apply { mkdirs() }
            val journal = LaunchSessionJournal(journalDir)

            // Scan ALL journal files, not just pending: terminal descriptors (REJECTED/CRASHED)
            // must be cleaned up on the next main-process resume; ADOPTED must be recovered.
            val allDescriptors = if (journalDir.isDirectory) {
                journalDir.listFiles()?.filter { it.extension == "json" }?.mapNotNull { file ->
                    val extractedId = file.nameWithoutExtension
                    journal.read(extractedId)
                } ?: emptyList()
            } else {
                emptyList()
            }

            // Cleanup-only descriptors: REJECTED and CRASHED — delete synchronously.
            for (descriptor in allDescriptors.filter { it.state.isCleanupOnly }) {
                Log.i(logTag, "recoverPendingSessions: cleaning up cleanup-only session ${descriptor.sessionId} (${descriptor.state})")
                journal.remove(descriptor.sessionId)
            }

            // ADOPTED descriptors: recover idempotently (finalizeAdoption + syncPostPlay).
            val adopted = allDescriptors.filter { it.state == DescriptorState.ADOPTED }
            val recoveryJobs = mutableListOf<Job>()
            for (descriptor in adopted) {
                val lock = getSessionLock(descriptor.sessionId)
                recoveryJobs.add(
                    lifecycleScope.launch(Dispatchers.IO) {
                        lock.withLock {
                            recoverAdoptedSession(journal, descriptor)
                        }
                    }
                )
            }

            // Non-terminal descriptors (LAUNCHED/CORE_LOADED): recover with best-effort post-play.
            val pending = allDescriptors.filter { !it.state.isTerminal }
            for (descriptor in pending) {
                val lock = getSessionLock(descriptor.sessionId)
                recoveryJobs.add(
                    lifecycleScope.launch(Dispatchers.IO) {
                        lock.withLock {
                            recoverNonAdoptedSession(journal, descriptor)
                        }
                    }
                )
            }

            // Await all recovery jobs so tests observe durable results.
            for (job in recoveryJobs) {
                job.join()
            }
        } catch (e: Exception) {
            Log.w(logTag, "recoverPendingSessionsInternal: error", e)
        }
    }

    /**
     * Recovers an ADOPTED descriptor idempotently: replays finalizeAdoption + syncPostPlay.
     * Journal preserved on failure, deleted only after both succeed.
     */
    private suspend fun recoverAdoptedSession(
        journal: LaunchSessionJournal,
        descriptor: SessionDescriptor,
    ) {
        Log.i(logTag, "recoverPendingSessions: replaying adoption for session ${descriptor.sessionId}")

        val rommSessionId = descriptor.rommSessionId
        val romId = descriptor.romId
        val romHash = descriptor.romHash
        val coreId = descriptor.coreId
        val coreBuildRevision = descriptor.coreBuildRevision
        val checkpointedHash = descriptor.checkpointedHash
        val canonicalSavePath = descriptor.canonicalSavePath
        val rommSaveId = descriptor.rommSaveId
        val serverContentHash = descriptor.serverContentHash

        if (rommSessionId == null || romId == null || romHash == null || coreId == null ||
            coreBuildRevision == null || checkpointedHash == null ||
            canonicalSavePath == null || rommSaveId == null) {
            Log.w(logTag, "recoverPendingSessions: journal descriptor missing authoritative fields — cannot recover ADOPTED session ${descriptor.sessionId}")
            // Missing fields: cannot recover, remove journal to avoid infinite replay.
            journal.remove(descriptor.sessionId)
            return
        }

        val session = sessionStore.current() ?: run {
            Log.w(logTag, "recoverPendingSessions: no active session for ADOPTED recovery ${descriptor.sessionId}")
            // No session: preserve journal for retry when user re-authenticates.
            return
        }
        val username = session.username ?: return
        val serverKey = extractServerKey(session.origin)

        var hadFailure = false

        try {
            coordinator.finalizeAdoption(
                FinalizeAdoptionRequest(
                    sessionId = rommSessionId,
                    rommSaveId = rommSaveId,
                    serverKey = serverKey,
                    userKey = username,
                    romId = romId,
                    romHash = romHash,
                    slot = SavePathPolicy.AUTOSAVE_SLOT,
                    coreId = coreId,
                    coreBuildRevision = coreBuildRevision,
                    checkpointedHash = checkpointedHash,
                    checkpointedSizeBytes = File(canonicalSavePath).length(),
                    serverContentHash = serverContentHash,
                    expectedSramSizeBytes = descriptor.expectedSramSizeBytes,
                )
            )
            Log.i(logTag, "recoverPendingSessions: finalization replay complete for session ${descriptor.sessionId}")
        } catch (e: Exception) {
            Log.w(logTag, "recoverPendingSessions: finalization replay failed", e)
            hadFailure = true
        }

        // Post-play: use latest checkpointed hash from gameplay for comparison/queueing.
        try {
            syncPostPlayAwaited(descriptor.sessionId, canonicalSavePath, checkpointedHash, romId)
        } catch (e: Exception) {
            Log.w(logTag, "recoverPendingSessions: syncPostPlay failed for session ${descriptor.sessionId}", e)
            hadFailure = true
        }

        // Only remove journal if no failure occurred.
        if (!hadFailure) {
            journal.remove(descriptor.sessionId)
            Log.i(logTag, "recoverPendingSessions: journal cleaned up for ADOPTED session ${descriptor.sessionId}")
        } else {
            Log.w(logTag, "recoverPendingSessions: journal PRESERVED for ADOPTED session ${descriptor.sessionId} (failure — will replay)")
        }
    }

    /**
     * Recovers a non-ADOPTED descriptor with checkpoint data: attempts post-play sync.
     */
    private suspend fun recoverNonAdoptedSession(
        journal: LaunchSessionJournal,
        descriptor: SessionDescriptor,
    ) {
        if (descriptor.checkpointedHash != null && descriptor.canonicalSavePath != null && descriptor.romId != null) {
            Log.i(logTag, "recoverPendingSessions: syncing checkpoint from non-ADOPTED descriptor ${descriptor.sessionId}")
            try {
                syncPostPlayAwaited(
                    descriptor.sessionId,
                    descriptor.canonicalSavePath,
                    descriptor.checkpointedHash,
                    descriptor.romId,
                )
                journal.remove(descriptor.sessionId)
            } catch (e: Exception) {
                Log.w(logTag, "recoverPendingSessions: syncPostPlay failed for non-ADOPTED session ${descriptor.sessionId}", e)
                // Preserve journal for retry.
            }
        } else {
            // No checkpoint data — clean up orphan descriptor.
            Log.i(logTag, "recoverPendingSessions: cleaning up non-ADOPTED descriptor without checkpoint data ${descriptor.sessionId}")
            journal.remove(descriptor.sessionId)
        }
    }

    /**
     * Awaits syncPostPlay synchronously. Returns the result (caller handles result classification).
     * Throws on unexpected exceptions so the caller can preserve the journal.
     */
    private suspend fun syncPostPlayAwaited(
        sessionId: String,
        canonicalSavePath: String,
        checkpointedHash: String,
        romId: Long,
    ): PostPlayCheckpointResult {
        val journal = LaunchSessionJournal(filesDir.resolve("launch_sessions"))
        val descriptor = journal.read(sessionId)
            ?: run { Log.w(logTag, "syncPostPlayAwaited: no journal descriptor for session $sessionId"); throw IllegalStateException("journal missing") }

        val coreId = descriptor.coreId ?: throw IllegalStateException("missing coreId")
        val coreBuildRevision = descriptor.coreBuildRevision ?: throw IllegalStateException("missing coreBuildRevision")
        val romHash = descriptor.romHash ?: throw IllegalStateException("missing romHash")
        val fileName = descriptor.canonicalFileName ?: "autosave.srm"

        val session = sessionStore.current() ?: throw IllegalStateException("no active session")
        val username = session.username ?: throw IllegalStateException("no username")
        val serverKey = extractServerKey(session.origin)

        val checkpointFile = File(canonicalSavePath)
        if (!checkpointFile.exists()) {
            throw IllegalStateException("checkpoint file missing at $canonicalSavePath")
        }

        return coordinator.syncPostPlay(
            PostPlayCheckpointRequest(
                serverKey = serverKey,
                userKey = username,
                romId = romId,
                romHash = romHash,
                slot = SavePathPolicy.AUTOSAVE_SLOT,
                coreId = coreId,
                coreBuildRevision = coreBuildRevision,
                fileName = fileName,
                checkpointedHash = checkpointedHash,
                checkpointedSizeBytes = checkpointFile.length(),
            )
        )
    }

    private fun cleanupJournal(sessionId: String) {
        try {
            val journalDir = filesDir.resolve("launch_sessions")
            LaunchSessionJournal(journalDir).remove(sessionId)
        } catch (_: Exception) {}
    }

    /**
     * Caches [metadata] under the given app launch [sessionId] (UUID string from LaunchSpec.sessionId).
     * The sessionId must match the one used in [handleEmulationResult] for finalization lookup.
     */
    fun cacheCandidateMetadata(sessionId: String, metadata: CandidateSaveMetadata) {
        candidateMetadataCache[sessionId] = metadata
    }

    /** @deprecated Use overload with explicit sessionId parameter. */
    @Deprecated("Use cacheCandidateMetadata(sessionId, metadata)", ReplaceWith("cacheCandidateMetadata(sessionId, metadata)"))
    fun cacheCandidateMetadata(metadata: CandidateSaveMetadata) {
        candidateMetadataCache[metadata.rommSessionId.toString()] = metadata
    }

    fun getCandidateMetadata(sessionId: String): CandidateSaveMetadata? =
        candidateMetadataCache[sessionId]

    fun removeCandidateMetadata(sessionId: String) {
        candidateMetadataCache.remove(sessionId)
    }
}

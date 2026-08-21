package com.romm.androidtv.emulation.model

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.romm.save.ConflictResolutionResult
import com.romm.androidtv.romm.save.FinalizeAdoptionRequest
import com.romm.androidtv.romm.save.FinalizeAdoptionResult
import com.romm.androidtv.romm.save.PostPlayCheckpointRequest
import com.romm.androidtv.romm.save.PostPlayCheckpointResult
import com.romm.androidtv.romm.save.ResolveConflictRequest
import com.romm.androidtv.romm.save.SaveReplicaEntity
import com.romm.androidtv.romm.save.SaveSyncCoordinator
import com.romm.androidtv.romm.save.SaveSyncCoordinatorInternal
import com.romm.androidtv.romm.save.SaveSyncOutcome
import com.romm.androidtv.romm.save.SaveSyncRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * Instrumented tests for [EmulationResultHandler] post-play awaited semantics.
 *
 * Validates that finalizeAdoption and syncPostPlay are fully suspend/awaited
 * (not fire-and-forget), journal retention on failure, ADOPTED recovery after
 * callback loss, per-session mutex/recovery, and non-blocking lifecycle wrapper.
 * Uses a real [SessionStore] backed by SharedPreferences (requires Android framework).
 */
class EmulationResultHandlerAwaitedTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var sessionStore: SessionStore
    private lateinit var filesDir: File
    private lateinit var lifecycleOwner: FakeLifecycleOwner
    private lateinit var fakeCoordinator: FakeCoordinator
    private lateinit var handler: EmulationResultHandler

    private val RESULT_OK = -1 // android.app.Activity.RESULT_OK
    private lateinit var instrumentation: android.app.Instrumentation

    @Before
    fun setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation()
        context = ApplicationProvider.getApplicationContext()
        sessionStore = SessionStore(context.getSharedPreferences("test_session", Context.MODE_PRIVATE))
        filesDir = tempFolder.newFolder("files")
        lifecycleOwner = FakeLifecycleOwner()
        instrumentation.runOnMainSync {
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        fakeCoordinator = FakeCoordinator()
        handler = EmulationResultHandler(
            coordinator = fakeCoordinator,
            sessionStore = sessionStore,
            lifecycleScope = lifecycleOwner.lifecycleScope,
            filesDir = filesDir,
            logTag = "TestEmulationResult",
        )
    }

    @After
    fun tearDown() {
        try {
            if (::sessionStore.isInitialized) sessionStore.clear()
        } finally {
            if (::lifecycleOwner.isInitialized) {
                instrumentation.runOnMainSync {
                    lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                }
            }
        }
    }

    private fun seedSession(origin: String = "https://romm.test", username: String = "testuser") {
        sessionStore.save(origin, username)
    }

    /**
     * Seeds a journal descriptor in ADOPTED state with all fields populated,
     * including serverContentHash for recovery finalization.
     */
    private fun seedJournalAdopted(
        sessionId: String = UUID.randomUUID().toString(),
        checkpointedHash: String = "abc123",
        serverContentHash: String? = "server-hash-xyz",
    ): File {
        val journalDir = filesDir.resolve("launch_sessions")
        journalDir.mkdirs()
        val checkpointFile = File(journalDir, "checkpoint.srm").apply {
            writeText("sram-data-content")
        }

        val journal = LaunchSessionJournal(journalDir)
        // LAUNCHED -> CORE_LOADED -> ADOPTED
        journal.createOrGet(sessionId)
        journal.advance(
            sessionId,
            DescriptorState.CORE_LOADED,
            SessionDescriptorPatch(
                rommSessionId = 100L,
                romId = 42L,
                romHash = "romhash-abc",
                coreId = "sameboy",
                coreBuildRevision = "v0.14",
                canonicalSavePath = checkpointFile.absolutePath,
                checkpointedHash = checkpointedHash,
                rommSaveId = 55L,
                canonicalFileName = "test.srm",
                expectedSramSizeBytes = 32768L,
                serverContentHash = serverContentHash,
            ),
        )
        journal.advance(
            sessionId,
            DescriptorState.ADOPTED,
            SessionDescriptorPatch(),
        )
        return checkpointFile
    }

    /**
     * Seeds a journal descriptor in CORE_LOADED state (non-terminal, picked up by listPending).
     * Includes checkpoint data so recovery triggers post-play sync.
     */
    private fun seedJournalCoreLoadedWithCheckpoint(
        sessionId: String = UUID.randomUUID().toString(),
        checkpointedHash: String = "recovery-hash",
    ): File {
        val journalDir = filesDir.resolve("launch_sessions")
        journalDir.mkdirs()
        val checkpointFile = File(journalDir, "checkpoint-recovery.srm").apply {
            writeText("recovery-sram-data")
        }

        val journal = LaunchSessionJournal(journalDir)
        journal.createOrGet(sessionId)
        journal.advance(
            sessionId,
            DescriptorState.CORE_LOADED,
            SessionDescriptorPatch(
                rommSessionId = 200L,
                romId = 43L,
                romHash = "romhash-def",
                coreId = "sameboy",
                coreBuildRevision = "v0.14",
                canonicalSavePath = checkpointFile.absolutePath,
                checkpointedHash = checkpointedHash,
                canonicalFileName = "test2.srm",
            ),
        )
        return checkpointFile
    }

    // ========== handleEmulationResult: successful path ==========

    @Test
    fun handleEmulationResultWithResultOkCallsFinalizeAdoptionAndSyncPostPlay() {
        runBlocking {
        seedSession()
        val sessionId = UUID.randomUUID().toString()
        val checkpointFile = seedJournalAdopted(sessionId = sessionId)
        val candidate = CandidateSaveMetadata(
            rommSessionId = 100L,
            rommSaveId = 55L,
            candidatePath = checkpointFile.absolutePath,
            downloadedSizeBytes = 32768,
            serverContentHash = "server-hash",
            emulator = "sameboy",
            romId = 42L,
            romHash = "romhash-abc",
            coreId = "sameboy",
            coreBuildRevision = "v0.14",
        )
        handler.cacheCandidateMetadata(sessionId, candidate)

        fakeCoordinator.finalizeAdoptionResult = FinalizeAdoptionResult.Success(confirmed = true)
        fakeCoordinator.syncPostPlayResult = PostPlayCheckpointResult.Unchanged

        val result = handler.handleEmulationResult(
            sessionId = sessionId,
            resultCode = RESULT_OK,
            checkpointedPath = checkpointFile.absolutePath,
            checkpointedHash = "abc123",
            resultRomId = 42L,
        )

        assertTrue(result)
        assertNotNull(fakeCoordinator.finalizeAdoptionRequest)
        assertEquals("abc123", fakeCoordinator.finalizeAdoptionRequest!!.checkpointedHash)
        assertNotNull(fakeCoordinator.syncPostPlayRequest)
        assertEquals("abc123", fakeCoordinator.syncPostPlayRequest!!.checkpointedHash)
        // Journal should be cleaned up on success.
        val descriptor = LaunchSessionJournal(filesDir.resolve("launch_sessions")).read(sessionId)
        assertFalse(descriptor != null)
        }
    }

    @Test
    fun handleEmulationResultWithoutSaveMemoryDoesNotRecordPlaySession() {
        runBlocking {
            seedSession()
            val sessionId = UUID.randomUUID().toString()
            val journal = LaunchSessionJournal(filesDir.resolve("launch_sessions"))
            journal.createOrGet(sessionId)
            journal.advance(
                sessionId,
                DescriptorState.CORE_LOADED,
                SessionDescriptorPatch(
                    romId = 31754L,
                    romHash = "romhash-no-sram",
                    coreId = "snes9x",
                    coreBuildRevision = "test",
                ),
            )

            val result = handler.handleEmulationResult(
                sessionId = sessionId,
                resultCode = RESULT_OK,
                checkpointedPath = null,
                checkpointedHash = null,
                resultRomId = 31754L,
            )

            assertTrue(result)
            assertFalse(fakeCoordinator.syncPostPlayCalled)
            assertFalse(fakeCoordinator.recordPlaySessionCalled)
            assertFalse(journal.read(sessionId) != null)
        }
    }

    @Test
    fun handleEmulationResultWithResultOkPreservesJournalOnFinalizeAdoptionFailure() {
        runBlocking {
        seedSession()
        val sessionId = UUID.randomUUID().toString()
        val checkpointFile = seedJournalAdopted(sessionId = sessionId)
        val candidate = CandidateSaveMetadata(
            rommSessionId = 100L,
            rommSaveId = 55L,
            candidatePath = checkpointFile.absolutePath,
            downloadedSizeBytes = 32768,
            serverContentHash = "server-hash",
            emulator = "sameboy",
            romId = 42L,
            romHash = "romhash-abc",
            coreId = "sameboy",
            coreBuildRevision = "v0.14",
        )
        handler.cacheCandidateMetadata(sessionId, candidate)

        fakeCoordinator.finalizeAdoptionThrows = RuntimeException("network failure")
        fakeCoordinator.syncPostPlayResult = PostPlayCheckpointResult.Unchanged

        val result = handler.handleEmulationResult(
            sessionId = sessionId,
            resultCode = RESULT_OK,
            checkpointedPath = checkpointFile.absolutePath,
            checkpointedHash = "abc123",
            resultRomId = 42L,
        )

        assertTrue(result)
        // Journal must be PRESERVED on failure for replay.
        val descriptor = LaunchSessionJournal(filesDir.resolve("launch_sessions")).read(sessionId)
        assertNotNull(descriptor)
        }
    }

    @Test
    fun handleEmulationResultWithResultOkPreservesJournalOnSyncPostPlayFailure() {
        runBlocking {
        seedSession()
        val sessionId = UUID.randomUUID().toString()
        val checkpointFile = seedJournalAdopted(sessionId = sessionId)
        val candidate = CandidateSaveMetadata(
            rommSessionId = 100L,
            rommSaveId = 55L,
            candidatePath = checkpointFile.absolutePath,
            downloadedSizeBytes = 32768,
            serverContentHash = "server-hash",
            emulator = "sameboy",
            romId = 42L,
            romHash = "romhash-abc",
            coreId = "sameboy",
            coreBuildRevision = "v0.14",
        )
        handler.cacheCandidateMetadata(sessionId, candidate)

        fakeCoordinator.finalizeAdoptionResult = FinalizeAdoptionResult.Success(confirmed = true)
        fakeCoordinator.syncPostPlayThrows = RuntimeException("db failure")

        val result = handler.handleEmulationResult(
            sessionId = sessionId,
            resultCode = RESULT_OK,
            checkpointedPath = checkpointFile.absolutePath,
            checkpointedHash = "abc123",
            resultRomId = 42L,
        )

        assertTrue(result)
        // Journal must be PRESERVED on failure.
        val descriptor = LaunchSessionJournal(filesDir.resolve("launch_sessions")).read(sessionId)
        assertNotNull(descriptor)
        }
    }

    @Test
    fun handleEmulationResultWithCancelledResultCodeReturnsFalseAndCleansJournal() {
        runBlocking {
        seedSession()
        val sessionId = UUID.randomUUID().toString()
        seedJournalAdopted(sessionId = sessionId)

        val result = handler.handleEmulationResult(
            sessionId = sessionId,
            resultCode = 0, // CANCELLED
            checkpointedPath = null,
            checkpointedHash = null,
            resultRomId = 0L,
        )

        assertFalse(result)
        assertFalse(fakeCoordinator.finalizeAdoptionCalled)
        assertFalse(fakeCoordinator.syncPostPlayCalled)
        // Journal should be cleaned up for cancelled results.
        val descriptor = LaunchSessionJournal(filesDir.resolve("launch_sessions")).read(sessionId)
        assertFalse(descriptor != null)
        }
    }

    @Test
    fun handleEmulationResultSkipsFinalizeAdoptionWhenNoCandidateMetadata() {
        runBlocking {
        seedSession()
        val sessionId = UUID.randomUUID().toString()
        val checkpointFile = seedJournalAdopted(sessionId = sessionId)

        handler.handleEmulationResult(
            sessionId = sessionId,
            resultCode = RESULT_OK,
            checkpointedPath = checkpointFile.absolutePath,
            checkpointedHash = "abc123",
            resultRomId = 42L,
        )

        assertFalse(fakeCoordinator.finalizeAdoptionCalled)
        // But syncPostPlay should still be called.
        assertTrue(fakeCoordinator.syncPostPlayCalled)
        }
    }

    @Test
    fun handleEmulationResultSkipsFinalizeAdoptionWhenNoActiveSession() {
        runBlocking {
        // No session seeded.
        val sessionId = UUID.randomUUID().toString()
        val checkpointFile = seedJournalAdopted(sessionId = sessionId)
        val candidate = CandidateSaveMetadata(
            rommSessionId = 100L,
            rommSaveId = 55L,
            candidatePath = checkpointFile.absolutePath,
            downloadedSizeBytes = 32768,
            serverContentHash = "server-hash",
            emulator = "sameboy",
            romId = 42L,
            romHash = "romhash-abc",
            coreId = "sameboy",
            coreBuildRevision = "v0.14",
        )
        handler.cacheCandidateMetadata(sessionId, candidate)

        handler.handleEmulationResult(
            sessionId = sessionId,
            resultCode = RESULT_OK,
            checkpointedPath = checkpointFile.absolutePath,
            checkpointedHash = "abc123",
            resultRomId = 42L,
        )

        // finalizeAdoption should be skipped (no active session). syncPostPlay also fails
        // because it needs an active session, so neither coordinator method is called.
        assertFalse(fakeCoordinator.finalizeAdoptionCalled)
        assertFalse(fakeCoordinator.syncPostPlayCalled)
        }
    }

    @Test
    fun handleEmulationResultSkipsFinalizeAdoptionOnRomIdentityMismatch() {
        runBlocking {
        seedSession()
        val sessionId = UUID.randomUUID().toString()
        val checkpointFile = seedJournalAdopted(sessionId = sessionId)
        val candidate = CandidateSaveMetadata(
            rommSessionId = 100L,
            rommSaveId = 55L,
            candidatePath = checkpointFile.absolutePath,
            downloadedSizeBytes = 32768,
            serverContentHash = "server-hash",
            emulator = "sameboy",
            romId = 42L,
            romHash = "romhash-abc",
            coreId = "sameboy",
            coreBuildRevision = "v0.14",
        )
        handler.cacheCandidateMetadata(sessionId, candidate)

        handler.handleEmulationResult(
            sessionId = sessionId,
            resultCode = RESULT_OK,
            checkpointedPath = checkpointFile.absolutePath,
            checkpointedHash = "abc123",
            resultRomId = 99L, // mismatch
        )

        // finalizeAdoption should be skipped (ROM identity mismatch), but syncPostPlay still runs.
        assertFalse(fakeCoordinator.finalizeAdoptionCalled)
        assertTrue(fakeCoordinator.syncPostPlayCalled)
        }
    }

    // ========== recoverPendingSessions: ADOPTED recovery after callback loss ==========

    @Test
    fun recoverPendingSessionsReplaysAdoptedDescriptorsAfterCallbackLoss() {
        seedSession()
        val sessionId = UUID.randomUUID().toString()
        seedJournalAdopted(sessionId = sessionId)

        fakeCoordinator.finalizeAdoptionResult = FinalizeAdoptionResult.Success(confirmed = true)
        fakeCoordinator.syncPostPlayResult = PostPlayCheckpointResult.Unchanged

        // Use awaited variant for deterministic test behavior.
        runBlocking {
            handler.recoverPendingSessionsAwaited()
        }

        // ADOPTED recovery should replay finalizeAdoption + syncPostPlay.
        assertTrue(fakeCoordinator.finalizeAdoptionCalled)
        assertTrue(fakeCoordinator.syncPostPlayCalled)
        // Journal should be cleaned up on success.
        val descriptor = LaunchSessionJournal(filesDir.resolve("launch_sessions")).read(sessionId)
        assertFalse(descriptor != null)
    }

    @Test
    fun recoverPendingSessionsPreservesAdoptedJournalOnFinalizeFailure() {
        seedSession()
        val sessionId = UUID.randomUUID().toString()
        seedJournalAdopted(sessionId = sessionId)

        fakeCoordinator.finalizeAdoptionThrows = RuntimeException("network failure")

        runBlocking {
            handler.recoverPendingSessionsAwaited()
        }

        // Journal must be PRESERVED on failure for replay.
        val descriptor = LaunchSessionJournal(filesDir.resolve("launch_sessions")).read(sessionId)
        assertNotNull(descriptor)
        assertEquals(DescriptorState.ADOPTED, descriptor!!.state)
    }

    @Test
    fun recoverPendingSessionsPreservesAdoptedJournalOnSyncPostPlayFailure() {
        seedSession()
        val sessionId = UUID.randomUUID().toString()
        seedJournalAdopted(sessionId = sessionId)

        fakeCoordinator.finalizeAdoptionResult = FinalizeAdoptionResult.Success(confirmed = true)
        fakeCoordinator.syncPostPlayThrows = RuntimeException("server down")

        runBlocking {
            handler.recoverPendingSessionsAwaited()
        }

        // Journal must be PRESERVED on failure.
        val descriptor = LaunchSessionJournal(filesDir.resolve("launch_sessions")).read(sessionId)
        assertNotNull(descriptor)
        assertEquals(DescriptorState.ADOPTED, descriptor!!.state)
    }

    @Test
    fun recoverPendingSessionsUsesServerContentHashFromDescriptor() {
        seedSession()
        val sessionId = UUID.randomUUID().toString()
        seedJournalAdopted(sessionId = sessionId, serverContentHash = "persisted-server-hash")

        fakeCoordinator.finalizeAdoptionResult = FinalizeAdoptionResult.Success(confirmed = true)
        fakeCoordinator.syncPostPlayResult = PostPlayCheckpointResult.Unchanged

        runBlocking {
            handler.recoverPendingSessionsAwaited()
        }

        // The finalizeAdoption request should carry serverContentHash from the descriptor.
        assertNotNull(fakeCoordinator.finalizeAdoptionRequest)
        assertEquals("persisted-server-hash", fakeCoordinator.finalizeAdoptionRequest!!.serverContentHash)
    }

    @Test
    fun recoverPendingSessionsCleansUpRejectedDescriptor() {
        val sessionId = UUID.randomUUID().toString()
        val journalDir = filesDir.resolve("launch_sessions")
        journalDir.mkdirs()
        // Write REJECTED descriptor directly (terminal state, cleanup-only).
        val descriptorFile = File(journalDir, "$sessionId.json")
        descriptorFile.writeText(
            """{"sessionId":"$sessionId","state":"REJECTED","candidatePath":null,"candidateDownloadedSizeBytes":null,"rommSaveId":null,"canonicalSavePath":null,"checkpointedHash":null,"errorDetail":"size mismatch","rommSessionId":null,"romId":null,"romHash":null,"coreId":null,"coreBuildRevision":null,"canonicalFileName":null,"expectedSramSizeBytes":null,"serverContentHash":null}""",
        )

        runBlocking {
            handler.recoverPendingSessionsAwaited()
        }

        // REJECTED descriptors should be cleaned up.
        val descriptor = LaunchSessionJournal(journalDir).read(sessionId)
        assertFalse(descriptor != null)
    }

    @Test
    fun recoverPendingSessionsProcessesCoreLoadedWithCheckpointData() {
        seedSession()
        val sessionId = UUID.randomUUID().toString()
        seedJournalCoreLoadedWithCheckpoint(sessionId = sessionId)

        fakeCoordinator.syncPostPlayResult = PostPlayCheckpointResult.Unchanged

        runBlocking {
            handler.recoverPendingSessionsAwaited()
        }

        assertTrue(fakeCoordinator.syncPostPlayCalled)
        assertEquals("recovery-hash", fakeCoordinator.syncPostPlayRequest!!.checkpointedHash)
        // Journal should be cleaned up on success.
        val descriptor = LaunchSessionJournal(filesDir.resolve("launch_sessions")).read(sessionId)
        assertFalse(descriptor != null)
    }

    @Test
    fun recoverPendingSessionsPreservesJournalOnSyncPostPlayFailure() {
        seedSession()
        val sessionId = UUID.randomUUID().toString()
        seedJournalCoreLoadedWithCheckpoint(sessionId = sessionId)

        fakeCoordinator.syncPostPlayThrows = RuntimeException("server down")

        runBlocking {
            handler.recoverPendingSessionsAwaited()
        }

        // Journal must be PRESERVED on failure.
        val descriptor = LaunchSessionJournal(filesDir.resolve("launch_sessions")).read(sessionId)
        assertNotNull(descriptor)
    }

    // ========== Non-blocking lifecycle wrapper ==========

    @Test
    fun recoverPendingSessionsNonblockingDoesNotBlockCallingThread() {
        seedSession()
        val sessionId = UUID.randomUUID().toString()
        seedJournalCoreLoadedWithCheckpoint(sessionId = sessionId)

        fakeCoordinator.syncPostPlayResult = PostPlayCheckpointResult.Unchanged

        // Call non-blocking variant — should return immediately.
        val startMs = System.currentTimeMillis()
        handler.recoverPendingSessions()
        val callDurationMs = System.currentTimeMillis() - startMs
        // Should return in well under 100ms (no blocking).
        assertTrue("recoverPendingSessions blocked for ${callDurationMs}ms", callDurationMs < 100)

        // Await async completion.
        runBlocking { delay(1000) }

        assertTrue(fakeCoordinator.syncPostPlayCalled)
    }

    @Test
    fun recoverPendingSessionsOncePerProcessSkipsSubsequentCalls() {
        seedSession()
        val sessionId = UUID.randomUUID().toString()
        seedJournalCoreLoadedWithCheckpoint(sessionId = sessionId)

        fakeCoordinator.syncPostPlayResult = PostPlayCheckpointResult.Unchanged

        // First call: awaited variant for deterministic completion.
        runBlocking {
            handler.recoverPendingSessionsAwaited()
        }
        assertTrue(fakeCoordinator.syncPostPlayCalled)

        // Reset flag to verify second call is a no-op.
        fakeCoordinator.syncPostPlayCalled = false

        // Second call should be a no-op (once-per-process guard).
        runBlocking {
            handler.recoverPendingSessionsAwaited()
        }

        // Second call should NOT have triggered another recovery.
        assertFalse(fakeCoordinator.syncPostPlayCalled)
    }

    // ========== Helpers ==========

    /** Minimal LifecycleOwner backed by a LifecycleRegistry for test use. */
    private class FakeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }

    /**
     * Fake [SaveSyncCoordinator] that also implements [SaveSyncCoordinatorInternal].
     * Tracks all calls for assertion.
     */
    private class FakeCoordinator : SaveSyncCoordinator, SaveSyncCoordinatorInternal {

        var finalizeAdoptionCalled = false
        var finalizeAdoptionRequest: FinalizeAdoptionRequest? = null
        var finalizeAdoptionResult: FinalizeAdoptionResult = FinalizeAdoptionResult.Success(true)
        var finalizeAdoptionThrows: Throwable? = null

        var syncPostPlayCalled = false
        var syncPostPlayRequest: PostPlayCheckpointRequest? = null
        var syncPostPlayResult: PostPlayCheckpointResult = PostPlayCheckpointResult.Unchanged
        var syncPostPlayThrows: Throwable? = null

        override suspend fun syncBeforeLaunch(request: SaveSyncRequest): SaveSyncOutcome {
            throw NotImplementedError()
        }

        override suspend fun adoptChosenSave(request: com.romm.androidtv.romm.save.AdoptSaveRequest): SaveSyncOutcome {
            throw NotImplementedError()
        }

        override suspend fun listSavesForRom(romId: Long): com.romm.androidtv.romm.SaveListResult {
            throw NotImplementedError()
        }

        override suspend fun syncPostPlay(request: PostPlayCheckpointRequest): PostPlayCheckpointResult {
            syncPostPlayCalled = true
            syncPostPlayRequest = request
            val e = syncPostPlayThrows
            if (e != null) throw e
            return syncPostPlayResult
        }

        override suspend fun finalizeAdoption(request: FinalizeAdoptionRequest): FinalizeAdoptionResult {
            finalizeAdoptionCalled = true
            finalizeAdoptionRequest = request
            val e = finalizeAdoptionThrows
            if (e != null) throw e
            return finalizeAdoptionResult
        }

        var recordPlaySessionCalled = false
        override suspend fun recordPlaySession(
            request: com.romm.androidtv.romm.save.PlaySessionRecordRequest,
        ): com.romm.androidtv.romm.save.PlaySessionRecordResult {
            recordPlaySessionCalled = true
            return com.romm.androidtv.romm.save.PlaySessionRecordResult.Success(1, 0)
        }

        override suspend fun findReplicaByScope(
            serverKey: String, userKey: String, romId: Long, romHash: String, slot: String,
        ): SaveReplicaEntity? = null

        override suspend fun resolveConflict(request: ResolveConflictRequest): ConflictResolutionResult {
            throw NotImplementedError()
        }
    }
}

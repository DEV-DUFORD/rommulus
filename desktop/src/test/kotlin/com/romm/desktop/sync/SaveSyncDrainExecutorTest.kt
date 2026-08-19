package com.romm.desktop.sync

import com.romm.androidtv.emulation.model.sha256Hex
import com.romm.androidtv.romm.DeviceIdentity
import com.romm.androidtv.romm.RommApiError
import com.romm.androidtv.romm.SaveConfirmResult
import com.romm.androidtv.romm.SaveDownloadResult
import com.romm.androidtv.romm.SaveUploadResult
import com.romm.androidtv.romm.ServerSaveInfo
import com.romm.androidtv.romm.SyncAction
import com.romm.androidtv.romm.SyncCompleteRequest
import com.romm.androidtv.romm.SyncNegotiateInfo
import com.romm.androidtv.romm.SyncNegotiateResult
import com.romm.androidtv.romm.SyncOperation
import com.romm.androidtv.storage.fakes.InMemorySaveStateStore
import com.romm.androidtv.storage.ports.SaveReplicaScope
import com.romm.androidtv.storage.records.PendingOperationRecord
import com.romm.androidtv.storage.records.PendingOperationStatus
import com.romm.androidtv.storage.records.PendingOperationType
import com.romm.androidtv.storage.records.SaveReplicaRecord
import com.romm.androidtv.storage.records.SaveSyncStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Unit tests for [SaveSyncDrainExecutor] — the ported Android save-sync state machine
 * (Phase 9, plans/LINUX_X64.md). Runs entirely on fakes: InMemorySaveStateStore (shared
 * storage-api fake), FakeSaveContentGateway, and a scripted FakeRommSyncGateway — no server.
 */
class SaveSyncDrainExecutorTest {

    // ── fixed scenario constants ────────────────────────────────────────────────
    private companion object {
        const val SRV = "srv"
        const val USER = "alice"
        const val ROM_ID = 42L
        const val HASH = "abc123hash"
        const val SLOT = "autosave"
        const val CORE_ID = "snes9x"
        const val CORE_REV = "rev-42"
        const val ORIGIN = "https://romm.test"
        const val DEVICE_ID = "device-1"

        val NOW = 1_700_000_000_000L
        val GEN = NOW - 60_000
        val BYTES = "local-save-bytes".toByteArray()
        const val SESSION_ID = 77L
    }

    private val scope = SaveReplicaScope(SRV, USER, ROM_ID, HASH, SLOT)

    // ── builders ────────────────────────────────────────────────────────────────

    private fun replica(
        generation: Long? = GEN,
        syncStatus: SaveSyncStatus = SaveSyncStatus.UNSYNCED,
        expectedSize: Long? = null,
    ) = SaveReplicaRecord(
        serverKey = SRV, userKey = USER, romId = ROM_ID, romHash = HASH, slot = SLOT,
        coreId = CORE_ID, coreBuildRevision = CORE_REV,
        expectedSramSizeBytes = expectedSize,
        localHash = sha256Hex(BYTES), localSizeBytes = BYTES.size.toLong(), localWrittenAtEpochMs = generation,
        syncStatus = syncStatus,
    )

    private fun uploadOp(
        generation: Long = GEN,
        status: PendingOperationStatus = PendingOperationStatus.PENDING,
        attemptCount: Int = 0,
        origin: String? = ORIGIN,
    ) = PendingOperationRecord(
        serverKey = SRV, userKey = USER, romId = ROM_ID, romHash = HASH, slot = SLOT,
        operationType = PendingOperationType.UPLOAD,
        localGenerationEpochMs = generation,
        status = status, attemptCount = attemptCount,
        origin = origin, uploadFileName = "save.srm", sessionId = SESSION_ID,
        createdAtEpochMs = NOW - 1000, updatedAtEpochMs = NOW - 1000,
    )

    private fun negotiateOp(
        generation: Long = GEN,
        coreBuildRevision: String? = CORE_REV,
    ) = PendingOperationRecord(
        serverKey = SRV, userKey = USER, romId = ROM_ID, romHash = HASH, slot = SLOT,
        operationType = PendingOperationType.NEGOTIATE_AND_SYNC,
        localGenerationEpochMs = generation,
        origin = ORIGIN,
        negotiateFileName = "neg.srm", negotiateCoreId = CORE_ID, negotiateCoreBuildRevision = coreBuildRevision,
        createdAtEpochMs = NOW - 1000, updatedAtEpochMs = NOW - 1000,
    )

    private fun syncOp(
        action: SyncAction,
        saveId: Long? = null,
        emulator: String? = CORE_ID,
        reason: String = "",
        serverUpdatedAt: Instant? = null,
        serverContentHash: String? = null,
    ) = SyncOperation(
        action = action, romId = ROM_ID, saveId = saveId, fileName = "neg.srm", slot = SLOT,
        emulator = emulator, reason = reason, serverUpdatedAt = serverUpdatedAt, serverContentHash = serverContentHash,
    )

    private fun uploadedSave() = ServerSaveInfo(
        saveId = 9001L, romId = ROM_ID, fileName = "save.srm", slot = SLOT, emulator = CORE_ID,
        contentHash = "srv-hash", updatedAt = Instant.ofEpochMilli(NOW + 5), fileSizeBytes = BYTES.size.toLong(),
    )

    private class Harness(
        val store: InMemorySaveStateStore = InMemorySaveStateStore(),
        val content: FakeSaveContentGateway = FakeSaveContentGateway(),
        val sync: FakeRommSyncGateway = FakeRommSyncGateway(),
        session: SaveSyncSession? = SaveSyncSession(ORIGIN, USER),
        identity: DeviceIdentity? = DeviceIdentity("install-1", DEVICE_ID),
    ) {
        val executor = SaveSyncDrainExecutor(
            pendingOperations = store,
            saveReplicas = store,
            content = content,
            sessionReader = FakeSaveSyncSessionReader(session),
            deviceIdentityLoader = FakeDeviceIdentityLoader(identity),
            sync = sync,
            clock = { NOW },
        )

        fun seedLocal() = content.setLocal(SRV, USER, ROM_ID, HASH, SLOT, BYTES)
    }

    // ── UPLOAD operations ───────────────────────────────────────────────────────

    @Test
    fun `upload success marks replica synced and op succeeded`() {
        val h = Harness()
        h.store.upsert(replica())
        val opId = h.store.enqueue(uploadOp()).getOrThrow()
        h.seedLocal()
        h.sync.uploadResult = SaveUploadResult.Success(uploadedSave())

        val result = h.executor.drainBatch()

        assertThat(result).isEqualTo(SaveSyncDrainExecutor.DrainResult.Complete)
        val op = h.store.findById(opId)!!
        assertThat(op.status).isEqualTo(PendingOperationStatus.SUCCEEDED)
        assertThat(op.attemptCount).isEqualTo(1)
        assertThat(op.lastError).isNull()

        val rep = h.store.findByScope(scope)!!
        assertThat(rep.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
        assertThat(rep.rommSaveId).isEqualTo(9001L)
        assertThat(rep.serverHash).isEqualTo("srv-hash")
        assertThat(rep.serverSizeBytes).isEqualTo(BYTES.size.toLong())
        assertThat(rep.serverUpdatedAtEpochMs).isEqualTo(NOW + 5)

        // Upload request is faithful: overwrite on, autocleanup 5, session id from the op.
        val (origin, req) = h.sync.uploadCalls.single()
        assertThat(origin).isEqualTo(ORIGIN)
        assertThat(req.overwrite).isTrue()
        assertThat(req.autocleanup).isTrue()
        assertThat(req.autocleanupLimit).isEqualTo(5)
        assertThat(req.sessionId).isEqualTo(SESSION_ID)
        assertThat(req.emulator).isEqualTo(CORE_ID)
        assertThat(req.deviceId).isEqualTo(DEVICE_ID)
        assertThat(req.fileName).isEqualTo("save.srm")
        assertThat(req.bytes).containsExactly(*BYTES)
    }

    @Test
    fun `upload 409 conflict is terminal and preserves local copy`() {
        val h = Harness()
        h.store.upsert(replica())
        val opId = h.store.enqueue(uploadOp()).getOrThrow()
        h.seedLocal()
        h.sync.uploadResult = SaveUploadResult.Conflict(409)

        val result = h.executor.drainBatch()

        assertThat(result).isEqualTo(SaveSyncDrainExecutor.DrainResult.Complete) // terminal — no retry
        val op = h.store.findById(opId)!!
        assertThat(op.status).isEqualTo(PendingOperationStatus.CONFLICT)
        assertThat(op.lastHttpCode).isEqualTo(409)
        // Replica keeps its local generation; local bytes untouched (both copies preserved).
        val rep = h.store.findByScope(scope)!!
        assertThat(rep.syncStatus).isEqualTo(SaveSyncStatus.UNSYNCED)
        assertThat(rep.localWrittenAtEpochMs).isEqualTo(GEN)
        assertThat(h.content.readLocal(SRV, USER, ROM_ID, HASH, SLOT)).containsExactly(*BYTES)
    }

    @Test
    fun `missing session or username classifies auth required`() {
        // No session at all.
        val h1 = Harness(session = null)
        h1.store.upsert(replica())
        val opId1 = h1.store.enqueue(uploadOp()).getOrThrow()
        h1.seedLocal()
        assertThat(h1.executor.drainBatch()).isEqualTo(SaveSyncDrainExecutor.DrainResult.Complete)
        assertThat(h1.store.findById(opId1)!!.status).isEqualTo(PendingOperationStatus.AUTH_REQUIRED)
        assertThat(h1.store.findById(opId1)!!.lastError).isEqualTo("no active session")
        assertThat(h1.sync.uploadCalls).isEmpty()

        // Session without a username.
        val h2 = Harness(session = SaveSyncSession(ORIGIN, null))
        h2.store.upsert(replica())
        val opId2 = h2.store.enqueue(uploadOp()).getOrThrow()
        h2.seedLocal()
        assertThat(h2.executor.drainBatch()).isEqualTo(SaveSyncDrainExecutor.DrainResult.Complete)
        assertThat(h2.store.findById(opId2)!!.status).isEqualTo(PendingOperationStatus.AUTH_REQUIRED)
        assertThat(h2.store.findById(opId2)!!.lastError).isEqualTo("no username in session")

        // Device not registered.
        val h3 = Harness(identity = null)
        h3.store.upsert(replica())
        val opId3 = h3.store.enqueue(uploadOp()).getOrThrow()
        h3.seedLocal()
        assertThat(h3.executor.drainBatch()).isEqualTo(SaveSyncDrainExecutor.DrainResult.Complete)
        assertThat(h3.store.findById(opId3)!!.status).isEqualTo(PendingOperationStatus.AUTH_REQUIRED)
        assertThat(h3.store.findById(opId3)!!.lastError).isEqualTo("device not registered")
    }

    @Test
    fun `auth expired upload classifies auth required`() {
        val h = Harness()
        h.store.upsert(replica())
        val opId = h.store.enqueue(uploadOp()).getOrThrow()
        h.seedLocal()
        h.sync.uploadResult = SaveUploadResult.Failure(RommApiError.AUTH_EXPIRED, 401)

        assertThat(h.executor.drainBatch()).isEqualTo(SaveSyncDrainExecutor.DrainResult.Complete)
        val op = h.store.findById(opId)!!
        assertThat(op.status).isEqualTo(PendingOperationStatus.AUTH_REQUIRED)
        assertThat(op.lastHttpCode).isEqualTo(401)
    }

    @Test
    fun `network and tls errors are retryable back to pending`() {
        for (error in listOf(RommApiError.NETWORK_ERROR, RommApiError.TLS_ERROR)) {
            val h = Harness()
            h.store.upsert(replica())
            val opId = h.store.enqueue(uploadOp()).getOrThrow()
            h.seedLocal()
            h.sync.uploadResult = SaveUploadResult.Failure(error)

            val result = h.executor.drainBatch()

            assertThat(result).isEqualTo(SaveSyncDrainExecutor.DrainResult.Retry(1))
            val op = h.store.findById(opId)!!
            assertThat(op.status).isEqualTo(PendingOperationStatus.PENDING)
            assertThat(op.attemptCount).isEqualTo(1)
            // Faithful to Android: the "transport failure" error lives only on the transient
            // RETRYABLE_FAILURE row; the final PENDING row clears it.
            assertThat(op.lastError).isNull()
        }
    }

    @Test
    fun `server error 5xx is retryable but 4xx and other errors are permanent`() {
        // 5xx -> PENDING retry.
        val h5 = Harness()
        h5.store.upsert(replica())
        val opId5 = h5.store.enqueue(uploadOp()).getOrThrow()
        h5.seedLocal()
        h5.sync.uploadResult = SaveUploadResult.Failure(RommApiError.SERVER_ERROR, 503)
        assertThat(h5.executor.drainBatch()).isEqualTo(SaveSyncDrainExecutor.DrainResult.Retry(1))
        assertThat(h5.store.findById(opId5)!!.status).isEqualTo(PendingOperationStatus.PENDING)

        // SERVER_ERROR with a non-5xx code -> PERMANENT_FAILURE "server error".
        val h4 = Harness()
        h4.store.upsert(replica())
        val opId4 = h4.store.enqueue(uploadOp()).getOrThrow()
        h4.seedLocal()
        h4.sync.uploadResult = SaveUploadResult.Failure(RommApiError.SERVER_ERROR, 404)
        assertThat(h4.executor.drainBatch()).isEqualTo(SaveSyncDrainExecutor.DrainResult.Complete)
        val op4 = h4.store.findById(opId4)!!
        assertThat(op4.status).isEqualTo(PendingOperationStatus.PERMANENT_FAILURE)
        assertThat(op4.lastError).isEqualTo("server error")
        assertThat(op4.lastHttpCode).isEqualTo(404)

        // Any other classification (e.g. PARSE_ERROR) -> PERMANENT_FAILURE "error: X".
        val hP = Harness()
        hP.store.upsert(replica())
        val opIdP = hP.store.enqueue(uploadOp()).getOrThrow()
        hP.seedLocal()
        hP.sync.uploadResult = SaveUploadResult.Failure(RommApiError.PARSE_ERROR, 200)
        assertThat(hP.executor.drainBatch()).isEqualTo(SaveSyncDrainExecutor.DrainResult.Complete)
        val opP = hP.store.findById(opIdP)!!
        assertThat(opP.status).isEqualTo(PendingOperationStatus.PERMANENT_FAILURE)
        assertThat(opP.lastError).isEqualTo("error: PARSE_ERROR")
    }

    @Test
    fun `validation failures are permanent and never call the server`() {
        // Missing replica.
        val h1 = Harness()
        val opId1 = h1.store.enqueue(uploadOp()).getOrThrow()
        h1.seedLocal()
        h1.executor.drainBatch()
        assertThat(h1.store.findById(opId1)!!.status).isEqualTo(PendingOperationStatus.PERMANENT_FAILURE)
        assertThat(h1.store.findById(opId1)!!.lastError).isEqualTo("no local SaveReplica found for scope")

        // Generation mismatch (replica newer than the queued generation).
        val h2 = Harness()
        h2.store.upsert(replica(generation = GEN + 1))
        val opId2 = h2.store.enqueue(uploadOp()).getOrThrow()
        h2.seedLocal()
        h2.executor.drainBatch()
        assertThat(h2.store.findById(opId2)!!.status).isEqualTo(PendingOperationStatus.PERMANENT_FAILURE)
        assertThat(h2.store.findById(opId2)!!.lastError)
            .isEqualTo("generation mismatch: replica=${GEN + 1} vs operation=$GEN")

        // Null local generation on the replica.
        val h3 = Harness()
        h3.store.upsert(replica(generation = null))
        val opId3 = h3.store.enqueue(uploadOp()).getOrThrow()
        h3.seedLocal()
        h3.executor.drainBatch()
        assertThat(h3.store.findById(opId3)!!.status).isEqualTo(PendingOperationStatus.PERMANENT_FAILURE)
        assertThat(h3.store.findById(opId3)!!.lastError).contains("null local generation")

        // Local SRAM file missing.
        val h4 = Harness()
        h4.store.upsert(replica())
        val opId4 = h4.store.enqueue(uploadOp()).getOrThrow()
        h4.executor.drainBatch() // no seedLocal()
        assertThat(h4.store.findById(opId4)!!.status).isEqualTo(PendingOperationStatus.PERMANENT_FAILURE)
        assertThat(h4.store.findById(opId4)!!.lastError).isEqualTo("local SRAM file missing")

        // Legacy op missing origin.
        val h5 = Harness()
        h5.store.upsert(replica())
        val opId5 = h5.store.enqueue(uploadOp(origin = null)).getOrThrow()
        h5.seedLocal()
        h5.executor.drainBatch()
        assertThat(h5.store.findById(opId5)!!.status).isEqualTo(PendingOperationStatus.PERMANENT_FAILURE)
        assertThat(h5.store.findById(opId5)!!.lastError).isEqualTo("legacy operation missing origin metadata")

        // No upload attempt in any of the above.
        listOf(h1, h2, h3, h4, h5).forEach { assertThat(it.sync.uploadCalls).isEmpty() }
    }

    @Test
    fun `stranded running operation is recovered and processed in the same drain`() {
        val h = Harness()
        h.store.upsert(replica())
        // A prior crash left this op RUNNING with 2 attempts already spent.
        val opId = h.store.enqueue(uploadOp(status = PendingOperationStatus.RUNNING, attemptCount = 2)).getOrThrow()
        h.seedLocal()
        h.sync.uploadResult = SaveUploadResult.Success(uploadedSave())

        val result = h.executor.drainBatch()

        assertThat(result).isEqualTo(SaveSyncDrainExecutor.DrainResult.Complete)
        val op = h.store.findById(opId)!!
        assertThat(op.status).isEqualTo(PendingOperationStatus.SUCCEEDED)
        // Recovery preserved the attempt count (2); this run added exactly one more.
        assertThat(op.attemptCount).isEqualTo(3)
        assertThat(op.lastError).isNull()
    }

    @Test
    fun `unexpected exception after running never strands the operation`() {
        val h = Harness()
        h.store.upsert(replica())
        val opId = h.store.enqueue(uploadOp()).getOrThrow()
        h.content.throwOnRead = true // I/O failure mid-processing, after the op is RUNNING

        val result = h.executor.drainBatch()

        assertThat(result).isEqualTo(SaveSyncDrainExecutor.DrainResult.Retry(1))
        val op = h.store.findById(opId)!!
        assertThat(op.status).isEqualTo(PendingOperationStatus.PENDING)
        assertThat(op.attemptCount).isEqualTo(1)
        // Same transient-error semantics as Android: the exception detail is recorded on the
        // RETRYABLE_FAILURE row, then cleared when the op returns to PENDING.
        assertThat(op.lastError).isNull()
    }

    // ── NEGOTIATE_AND_SYNC operations ───────────────────────────────────────────

    @Test
    fun `negotiate upload action uploads with fresh session and completes session`() {
        val h = Harness()
        h.store.upsert(replica())
        val opId = h.store.enqueue(negotiateOp()).getOrThrow()
        h.seedLocal()
        h.sync.negotiateResult = SyncNegotiateResult.Success(
            SyncNegotiateInfo(sessionId = 55L, operations = listOf(syncOp(SyncAction.UPLOAD)))
        )
        h.sync.uploadResult = SaveUploadResult.Success(uploadedSave())

        val result = h.executor.drainBatch()

        assertThat(result).isEqualTo(SaveSyncDrainExecutor.DrainResult.Complete)
        assertThat(h.store.findById(opId)!!.status).isEqualTo(PendingOperationStatus.SUCCEEDED)
        assertThat(h.store.findByScope(scope)!!.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
        assertThat(h.store.findByScope(scope)!!.rommSaveId).isEqualTo(9001L)

        // Negotiate carried the local client save state (hash from the replica, fresh session id).
        val (negOrigin, negReq) = h.sync.negotiateCalls.single()
        assertThat(negOrigin).isEqualTo(ORIGIN)
        assertThat(negReq.deviceId).isEqualTo(DEVICE_ID)
        val save = negReq.saves.single()
        assertThat(save.romId).isEqualTo(ROM_ID)
        assertThat(save.fileName).isEqualTo("neg.srm")
        assertThat(save.slot).isEqualTo(SLOT)
        assertThat(save.emulator).isEqualTo(CORE_ID)
        assertThat(save.contentHash).isEqualTo(sha256Hex(BYTES))
        assertThat(save.fileSizeBytes).isEqualTo(BYTES.size.toLong())

        // The upload under a fresh negotiation carries NO stale session id.
        val (_, uploadReq) = h.sync.uploadCalls.single()
        assertThat(uploadReq.sessionId).isNull()

        // Session completed with exact counters (1 completed, 0 failed).
        assertThat(h.sync.completeSessionCalls)
            .containsExactly(Triple(ORIGIN, 55L, SyncCompleteRequest(operationsCompleted = 1, operationsFailed = 0)))
    }

    @Test
    fun `negotiate no-op action marks replica synced and completes session`() {
        val h = Harness()
        h.store.upsert(replica())
        val opId = h.store.enqueue(negotiateOp()).getOrThrow()
        h.seedLocal()
        h.sync.negotiateResult = SyncNegotiateResult.Success(
            SyncNegotiateInfo(
                sessionId = 61L,
                operations = listOf(
                    syncOp(SyncAction.NO_OP, saveId = 777L, serverUpdatedAt = Instant.ofEpochMilli(NOW + 9), serverContentHash = "srv-noop-hash")
                ),
            )
        )

        val result = h.executor.drainBatch()

        assertThat(result).isEqualTo(SaveSyncDrainExecutor.DrainResult.Complete)
        assertThat(h.store.findById(opId)!!.status).isEqualTo(PendingOperationStatus.SUCCEEDED)
        val rep = h.store.findByScope(scope)!!
        assertThat(rep.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
        assertThat(rep.rommSaveId).isEqualTo(777L)
        assertThat(rep.serverHash).isEqualTo("srv-noop-hash")
        assertThat(rep.serverUpdatedAtEpochMs).isEqualTo(NOW + 9)
        // No-op: no bytes moved either direction.
        assertThat(h.sync.uploadCalls).isEmpty()
        assertThat(h.sync.downloadCalls).isEmpty()
        assertThat(h.sync.completeSessionCalls)
            .containsExactly(Triple(ORIGIN, 61L, SyncCompleteRequest(operationsCompleted = 1, operationsFailed = 0)))
    }

    @Test
    fun `negotiate conflict action marks both sides conflict preserving local copy`() {
        val h = Harness()
        h.store.upsert(replica())
        val opId = h.store.enqueue(negotiateOp()).getOrThrow()
        h.seedLocal()
        h.sync.negotiateResult = SyncNegotiateResult.Success(
            SyncNegotiateInfo(sessionId = 71L, operations = listOf(syncOp(SyncAction.CONFLICT, reason = "server-newer")))
        )

        val result = h.executor.drainBatch()

        assertThat(result).isEqualTo(SaveSyncDrainExecutor.DrainResult.Complete) // terminal — no retry
        val op = h.store.findById(opId)!!
        assertThat(op.status).isEqualTo(PendingOperationStatus.CONFLICT)
        assertThat(op.lastError).isEqualTo("server-newer")
        // Replica flagged CONFLICT for the UI; local generation + bytes fully preserved.
        val rep = h.store.findByScope(scope)!!
        assertThat(rep.syncStatus).isEqualTo(SaveSyncStatus.CONFLICT)
        assertThat(rep.lastError).isEqualTo("server-newer")
        assertThat(rep.localWrittenAtEpochMs).isEqualTo(GEN)
        assertThat(h.content.readLocal(SRV, USER, ROM_ID, HASH, SLOT)).containsExactly(*BYTES)
        // No bytes moved either direction.
        assertThat(h.sync.uploadCalls).isEmpty()
        assertThat(h.sync.downloadCalls).isEmpty()
        assertThat(h.sync.completeSessionCalls)
            .containsExactly(Triple(ORIGIN, 71L, SyncCompleteRequest(operationsCompleted = 0, operationsFailed = 1)))
    }

    @Test
    fun `negotiate download action validates adopts and confirms`() {
        val serverBytes = "server-save-bytes".toByteArray()
        val h = Harness()
        h.store.upsert(replica())
        val opId = h.store.enqueue(negotiateOp()).getOrThrow()
        h.seedLocal()
        h.sync.negotiateResult = SyncNegotiateResult.Success(
            SyncNegotiateInfo(sessionId = 81L, operations = listOf(syncOp(SyncAction.DOWNLOAD, saveId = 8823L)))
        )
        h.sync.downloadResult = SaveDownloadResult.Success(serverBytes)
        h.sync.confirmResult = SaveConfirmResult.Success

        val result = h.executor.drainBatch()

        assertThat(result).isEqualTo(SaveSyncDrainExecutor.DrainResult.Complete)
        assertThat(h.store.findById(opId)!!.status).isEqualTo(PendingOperationStatus.SUCCEEDED)
        // Download used the negotiated session; confirm followed adoption.
        assertThat(h.sync.downloadCalls.single().sessionId).isEqualTo(81L)
        assertThat(h.sync.confirmCalls).containsExactly(Triple(ORIGIN, 8823L, DEVICE_ID))
        // Local file replaced atomically with the server bytes; replica re-anchored to them.
        assertThat(h.content.readLocal(SRV, USER, ROM_ID, HASH, SLOT)).containsExactly(*serverBytes)
        val rep = h.store.findByScope(scope)!!
        assertThat(rep.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
        assertThat(rep.localHash).isEqualTo(sha256Hex(serverBytes))
        assertThat(rep.localSizeBytes).isEqualTo(serverBytes.size.toLong())
        assertThat(rep.localWrittenAtEpochMs).isEqualTo(NOW)
        assertThat(rep.rommSaveId).isEqualTo(8823L)
        assertThat(h.sync.completeSessionCalls)
            .containsExactly(Triple(ORIGIN, 81L, SyncCompleteRequest(operationsCompleted = 1, operationsFailed = 0)))
    }

    @Test
    fun `negotiate download size mismatch quarantines and preserves local copy`() {
        val serverBytes = "way-too-big-server-bytes".toByteArray()
        val h = Harness()
        h.store.upsert(replica(expectedSize = 99L)) // exact-size gate: expect 99 bytes
        val opId = h.store.enqueue(negotiateOp()).getOrThrow()
        h.seedLocal()
        h.sync.negotiateResult = SyncNegotiateResult.Success(
            SyncNegotiateInfo(sessionId = 91L, operations = listOf(syncOp(SyncAction.DOWNLOAD, saveId = 8823L)))
        )
        h.sync.downloadResult = SaveDownloadResult.Success(serverBytes)

        val result = h.executor.drainBatch()

        assertThat(result).isEqualTo(SaveSyncDrainExecutor.DrainResult.Complete) // terminal — no retry
        val op = h.store.findById(opId)!!
        assertThat(op.status).isEqualTo(PendingOperationStatus.PERMANENT_FAILURE)
        assertThat(op.lastError).isEqualTo("download quarantined: size-mismatch")
        // Candidate preserved in quarantine; canonical local copy untouched.
        assertThat(h.content.quarantined.single().second).isEqualTo("size-mismatch")
        assertThat(h.content.quarantined.single().third).containsExactly(*serverBytes)
        val rep = h.store.findByScope(scope)!!
        assertThat(rep.syncStatus).isEqualTo(SaveSyncStatus.QUARANTINED)
        assertThat(rep.lastError).isEqualTo("quarantined: size-mismatch (post-play)")
        assertThat(h.content.readLocal(SRV, USER, ROM_ID, HASH, SLOT)).containsExactly(*BYTES)
        // Never adopted -> never confirmed.
        assertThat(h.sync.confirmCalls).isEmpty()
        assertThat(h.sync.completeSessionCalls)
            .containsExactly(Triple(ORIGIN, 91L, SyncCompleteRequest(operationsCompleted = 0, operationsFailed = 1)))
    }

    @Test
    fun `negotiate failure classifications match android`() {
        // Auth expired -> AUTH_REQUIRED terminal.
        val hA = Harness()
        hA.store.upsert(replica())
        val opIdA = hA.store.enqueue(negotiateOp()).getOrThrow()
        hA.seedLocal()
        hA.sync.negotiateResult = SyncNegotiateResult.Failure(RommApiError.AUTH_EXPIRED, 401)
        assertThat(hA.executor.drainBatch()).isEqualTo(SaveSyncDrainExecutor.DrainResult.Complete)
        assertThat(hA.store.findById(opIdA)!!.status).isEqualTo(PendingOperationStatus.AUTH_REQUIRED)

        // Network error -> PENDING retry.
        val hN = Harness()
        hN.store.upsert(replica())
        val opIdN = hN.store.enqueue(negotiateOp()).getOrThrow()
        hN.seedLocal()
        hN.sync.negotiateResult = SyncNegotiateResult.Failure(RommApiError.NETWORK_ERROR)
        assertThat(hN.executor.drainBatch()).isEqualTo(SaveSyncDrainExecutor.DrainResult.Retry(1))
        assertThat(hN.store.findById(opIdN)!!.status).isEqualTo(PendingOperationStatus.PENDING)

        // 5xx -> PENDING retry.
        val hS = Harness()
        hS.store.upsert(replica())
        val opIdS = hS.store.enqueue(negotiateOp()).getOrThrow()
        hS.seedLocal()
        hS.sync.negotiateResult = SyncNegotiateResult.Failure(RommApiError.SERVER_ERROR, 502)
        assertThat(hS.executor.drainBatch()).isEqualTo(SaveSyncDrainExecutor.DrainResult.Retry(1))
        assertThat(hS.store.findById(opIdS)!!.status).isEqualTo(PendingOperationStatus.PENDING)

        // 4xx -> PERMANENT_FAILURE.
        val hF = Harness()
        hF.store.upsert(replica())
        val opIdF = hF.store.enqueue(negotiateOp()).getOrThrow()
        hF.seedLocal()
        hF.sync.negotiateResult = SyncNegotiateResult.Failure(RommApiError.SERVER_ERROR, 403)
        assertThat(hF.executor.drainBatch()).isEqualTo(SaveSyncDrainExecutor.DrainResult.Complete)
        assertThat(hF.store.findById(opIdF)!!.status).isEqualTo(PendingOperationStatus.PERMANENT_FAILURE)

        // Negotiate succeeded but returned no operation for this ROM/slot.
        val hM = Harness()
        hM.store.upsert(replica())
        val opIdM = hM.store.enqueue(negotiateOp()).getOrThrow()
        hM.seedLocal()
        hM.sync.negotiateResult = SyncNegotiateResult.Success(SyncNegotiateInfo(sessionId = 95L, operations = emptyList()))
        assertThat(hM.executor.drainBatch()).isEqualTo(SaveSyncDrainExecutor.DrainResult.Complete)
        assertThat(hM.store.findById(opIdM)!!.status).isEqualTo(PendingOperationStatus.PERMANENT_FAILURE)
        assertThat(hM.store.findById(opIdM)!!.lastError).isEqualTo("negotiate returned no matching operation")

        // Stale core build revision -> PERMANENT_FAILURE before any network call.
        val hR = Harness()
        hR.store.upsert(replica())
        val opIdR = hR.store.enqueue(negotiateOp(coreBuildRevision = "rev-99")).getOrThrow()
        hR.seedLocal()
        assertThat(hR.executor.drainBatch()).isEqualTo(SaveSyncDrainExecutor.DrainResult.Complete)
        assertThat(hR.store.findById(opIdR)!!.status).isEqualTo(PendingOperationStatus.PERMANENT_FAILURE)
        assertThat(hR.store.findById(opIdR)!!.lastError).contains("coreBuildRevision mismatch")
        assertThat(hR.sync.negotiateCalls).isEmpty()
    }

    // ── dedupe semantics (findActiveByScope / deleteStaleForScope) ─────────────

    @Test
    fun `dedupe terminal success frees the scope and stale ops are dropped by generation`() {
        val h = Harness()
        h.store.upsert(replica(generation = 200L))
        h.seedLocal()
        h.sync.uploadResult = SaveUploadResult.Success(uploadedSave())

        // A duplicate enqueue race left two non-terminal ops for the same scope.
        h.store.enqueue(uploadOp(generation = 100L)).getOrThrow() // stale generation
        val freshId = h.store.enqueue(uploadOp(generation = 200L)).getOrThrow()
        assertThat(h.store.findActiveByScope(scope, PendingOperationType.UPLOAD)).hasSize(2)

        // Dedupe rule "preserving the newest durable local generation": drop older non-terminal ops.
        assertThat(h.store.deleteStaleForScope(scope, PendingOperationType.UPLOAD, 200L)).isEqualTo(1)
        assertThat(h.store.findActiveByScope(scope, PendingOperationType.UPLOAD).map { it.id }).containsExactly(freshId)

        // The drain processes only the surviving op; success leaves NO active ops for the scope.
        val result = h.executor.drainBatch()
        assertThat(result).isEqualTo(SaveSyncDrainExecutor.DrainResult.Complete)
        assertThat(h.store.findById(freshId)!!.status).isEqualTo(PendingOperationStatus.SUCCEEDED)
        assertThat(h.store.findActiveByScope(scope, PendingOperationType.UPLOAD)).isEmpty()
    }

    @Test
    fun `drain with no pending operations completes without touching the session`() {
        val h = Harness(session = null) // would be AUTH_REQUIRED if anything were processed
        assertThat(h.executor.drainBatch()).isEqualTo(SaveSyncDrainExecutor.DrainResult.Complete)
        assertThat(h.sync.negotiateCalls).isEmpty()
        assertThat(h.sync.uploadCalls).isEmpty()
    }
}

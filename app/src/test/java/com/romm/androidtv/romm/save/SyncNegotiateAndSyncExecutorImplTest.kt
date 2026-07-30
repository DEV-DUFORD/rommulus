package com.romm.androidtv.romm.save

import com.romm.androidtv.romm.DeviceIdentity
import com.romm.androidtv.romm.RommApiError
import com.romm.androidtv.romm.SaveUploadRequest
import com.romm.androidtv.romm.SaveUploadResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("SyncNegotiateAndSyncExecutorImpl")
class SyncNegotiateAndSyncExecutorImplTest {

    private lateinit var server: MockWebServer
    private lateinit var client: okhttp3.OkHttpClient
    private lateinit var pendingOpDao: FakePendingOperationDao
    private lateinit var replicaDao: FakeSaveReplicaDao
    private lateinit var contentStore: FakeSaveContentStore
    private lateinit var executor: SyncNegotiateAndSyncExecutorImpl

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start(0)
        client = okhttp3.OkHttpClient.Builder().build()
        pendingOpDao = FakePendingOperationDao()
        replicaDao = FakeSaveReplicaDao()
        contentStore = FakeSaveContentStore()
        executor = buildExecutor()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString().removeSuffix("/")

    private fun buildExecutor(
        sessionReader: SessionReader = SessionReader { DurableSession(baseUrl(), "testuser") },
        deviceIdentityLoader: DeviceIdentityLoader = DeviceIdentityLoader { _, _ -> DeviceIdentity("install-uuid", "device-uuid") },
        uploadBehavior: (String, SaveUploadRequest) -> SaveUploadResult = { _, _ ->
            SaveUploadResult.Failure(RommApiError.NETWORK_ERROR)
        },
    ): SyncNegotiateAndSyncExecutorImpl {
        return SyncNegotiateAndSyncExecutorImpl(
            client = client,
            pendingOperationDao = pendingOpDao,
            saveReplicaDao = replicaDao,
            saveContentStore = contentStore,
            sessionReader = sessionReader,
            deviceIdentityLoader = deviceIdentityLoader,
            uploadCaller = SaveUploadCaller { origin, request -> uploadBehavior(origin, request) },
        )
    }

    private fun enqueueNegotiate(operationsJson: String, sessionId: Long = 100L) {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"session_id": $sessionId, "operations": [$operationsJson], "total_upload": 0, "total_download": 0, "total_conflict": 0, "total_no_op": 0}"""
            )
        )
    }

    private fun enqueueComplete() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"session": {"id": 100, "status": "COMPLETED"}}""")
        )
    }

    /**
     * Creates an operation entity and inserts it into the DAO. Returns the
     * inserted entity (with a real id) so [SyncNegotiateAndSyncExecutorImpl.executeOne]
     * can locate the row for status transitions.
     */
    private suspend fun insertOp(
        localGenerationEpochMs: Long = 1000L,
        dao: FakePendingOperationDao = pendingOpDao,
    ): PendingOperationEntity {
        val op = PendingOperationEntity(
            serverKey = "localhost",
            userKey = "testuser",
            romId = 42L,
            romHash = "abc123",
            slot = "autosave",
            operationType = PendingOperationType.NEGOTIATE_AND_SYNC,
            localGenerationEpochMs = localGenerationEpochMs,
            status = PendingOperationStatus.PENDING,
            origin = baseUrl(),
            negotiateFileName = "test.srm",
            negotiateCoreId = "sameboy",
            negotiateCoreBuildRevision = "v0.14",
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 1000L,
        )
        val id = dao.insert(op)
        return dao.findById(id)!!
    }

    private fun makeReplica(
        op: PendingOperationEntity,
        expectedSramSizeBytes: Long? = null,
        localWrittenAtEpochMs: Long = op.localGenerationEpochMs,
    ): SaveReplicaEntity {
        return SaveReplicaEntity(
            serverKey = op.serverKey,
            userKey = op.userKey,
            romId = op.romId,
            romHash = op.romHash,
            slot = op.slot,
            coreId = "sameboy",
            coreBuildRevision = "v0.14",
            expectedSramSizeBytes = expectedSramSizeBytes,
            localWrittenAtEpochMs = localWrittenAtEpochMs,
        )
    }

    // ===== UPLOAD action tests =====

    @Test
    fun `UPLOAD action with successful upload transitions to SUCCEEDED`() {
        runBlocking {
            val op = insertOp()
            contentStore.seedLocal(op.serverKey, op.userKey, op.romId, op.romHash, op.slot, byteArrayOf(1, 2, 3))
            replicaDao.seed(makeReplica(op))

            enqueueNegotiate(
                """{"action": "upload", "rom_id": 42, "file_name": "test.srm", "slot": "autosave", "emulator": "sameboy", "reason": "not on server"}"""
            )
            enqueueComplete()

            val testExecutor = buildExecutor(
                uploadBehavior = { _, _ ->
                    SaveUploadResult.Success(
                        com.romm.androidtv.romm.ServerSaveInfo(
                            saveId = 42L, romId = op.romId, fileName = "test.srm",
                            slot = op.slot, emulator = "sameboy", contentHash = "server-hash",
                            updatedAt = Instant.now(), fileSizeBytes = 3,
                        )
                    )
                }
            )

            val result = testExecutor.executeOne(op)
            assertThat(result).isEqualTo(SyncNegotiateAndSyncExecutor.ExecutionOutcome.Completed)
            assertThat(pendingOpDao.findById(op.id)!!.status).isEqualTo(PendingOperationStatus.SUCCEEDED)
            assertThat(replicaDao.findByScope(op.serverKey, op.userKey, op.romId, op.romHash, op.slot)!!.syncStatus)
                .isEqualTo(SaveSyncStatus.SYNCED)
        }
    }

    @Test
    fun `UPLOAD action with conflict transitions to CONFLICT terminal`() {
        runBlocking {
            val op = insertOp()
            contentStore.seedLocal(op.serverKey, op.userKey, op.romId, op.romHash, op.slot, byteArrayOf(1, 2, 3))
            replicaDao.seed(makeReplica(op))

            enqueueNegotiate(
                """{"action": "upload", "rom_id": 42, "file_name": "test.srm", "slot": "autosave", "emulator": "sameboy", "reason": "not on server"}"""
            )
            enqueueComplete()

            val testExecutor = buildExecutor(
                uploadBehavior = { _, _ -> SaveUploadResult.Conflict(409) }
            )

            val result = testExecutor.executeOne(op)
            assertThat(result).isEqualTo(SyncNegotiateAndSyncExecutor.ExecutionOutcome.Completed)
            assertThat(pendingOpDao.findById(op.id)!!.status).isEqualTo(PendingOperationStatus.CONFLICT)
        }
    }

    @Test
    fun `UPLOAD action with auth expired transitions to AUTH_REQUIRED terminal`() {
        runBlocking {
            val op = insertOp()
            contentStore.seedLocal(op.serverKey, op.userKey, op.romId, op.romHash, op.slot, byteArrayOf(1, 2, 3))
            replicaDao.seed(makeReplica(op))

            enqueueNegotiate(
                """{"action": "upload", "rom_id": 42, "file_name": "test.srm", "slot": "autosave", "emulator": "sameboy", "reason": "not on server"}"""
            )
            enqueueComplete()

            val testExecutor = buildExecutor(
                uploadBehavior = { _, _ -> SaveUploadResult.Failure(RommApiError.AUTH_EXPIRED, 401) }
            )

            val result = testExecutor.executeOne(op)
            assertThat(result).isEqualTo(SyncNegotiateAndSyncExecutor.ExecutionOutcome.Completed)
            assertThat(pendingOpDao.findById(op.id)!!.status).isEqualTo(PendingOperationStatus.AUTH_REQUIRED)
        }
    }

    @Test
    fun `UPLOAD action with network error transitions to PENDING retry`() {
        runBlocking {
            val op = insertOp()
            contentStore.seedLocal(op.serverKey, op.userKey, op.romId, op.romHash, op.slot, byteArrayOf(1))
            replicaDao.seed(makeReplica(op))

            enqueueNegotiate(
                """{"action": "upload", "rom_id": 42, "file_name": "test.srm", "slot": "autosave", "emulator": "sameboy", "reason": "not on server"}"""
            )
            enqueueComplete()

            val testExecutor = buildExecutor(
                uploadBehavior = { _, _ -> SaveUploadResult.Failure(RommApiError.NETWORK_ERROR) }
            )

            val result = testExecutor.executeOne(op)
            assertThat(result).isEqualTo(SyncNegotiateAndSyncExecutor.ExecutionOutcome.Retryable)
            assertThat(pendingOpDao.findById(op.id)!!.status).isEqualTo(PendingOperationStatus.PENDING)
        }
    }

    // ===== NO_OP action tests =====

    @Test
    fun `NO_OP action transitions to SUCCEEDED and updates replica`() {
        runBlocking {
            val op = insertOp()
            contentStore.seedLocal(op.serverKey, op.userKey, op.romId, op.romHash, op.slot, byteArrayOf(1, 2))
            replicaDao.seed(makeReplica(op))

            enqueueNegotiate(
                """{"action": "no_op", "rom_id": 42, "save_id": 55, "file_name": "test.srm", "slot": "autosave", "emulator": "sameboy", "reason": "identical", "server_content_hash": "server-hash"}"""
            )
            enqueueComplete()

            val result = executor.executeOne(op)
            assertThat(result).isEqualTo(SyncNegotiateAndSyncExecutor.ExecutionOutcome.Completed)
            assertThat(pendingOpDao.findById(op.id)!!.status).isEqualTo(PendingOperationStatus.SUCCEEDED)
            val replica = replicaDao.findByScope(op.serverKey, op.userKey, op.romId, op.romHash, op.slot)!!
            assertThat(replica.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
            assertThat(replica.rommSaveId).isEqualTo(55L)
            assertThat(replica.serverHash).isEqualTo("server-hash")
        }
    }

    // ===== DOWNLOAD action tests =====

    @Test
    fun `DOWNLOAD action with successful confirm transitions to SUCCEEDED`() {
        runBlocking {
            val op = insertOp()
            replicaDao.seed(makeReplica(op, expectedSramSizeBytes = 3L))

            enqueueNegotiate(
                """{"action": "download", "rom_id": 42, "save_id": 10, "file_name": "test.srm", "slot": "autosave", "emulator": "sameboy", "reason": "newer on server", "server_content_hash": "server-hash"}"""
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
            server.enqueue(MockResponse().setResponseCode(200)) // confirm download
            enqueueComplete()

            val result = executor.executeOne(op)
            assertThat(result).isEqualTo(SyncNegotiateAndSyncExecutor.ExecutionOutcome.Completed)
            assertThat(pendingOpDao.findById(op.id)!!.status).isEqualTo(PendingOperationStatus.SUCCEEDED)
            assertThat(contentStore.readLocal(op.serverKey, op.userKey, op.romId, op.romHash, op.slot))
                .isEqualTo(byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun `DOWNLOAD confirmDownload auth failure transitions to AUTH_REQUIRED terminal`() {
        runBlocking {
            val op = insertOp()
            replicaDao.seed(makeReplica(op, expectedSramSizeBytes = 3L))

            enqueueNegotiate(
                """{"action": "download", "rom_id": 42, "save_id": 10, "file_name": "test.srm", "slot": "autosave", "emulator": "sameboy", "reason": "newer on server", "server_content_hash": "server-hash"}"""
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
            server.enqueue(MockResponse().setResponseCode(401)) // confirm fails with auth
            enqueueComplete()

            val result = executor.executeOne(op)
            assertThat(result).isEqualTo(SyncNegotiateAndSyncExecutor.ExecutionOutcome.Completed)
            assertThat(pendingOpDao.findById(op.id)!!.status).isEqualTo(PendingOperationStatus.AUTH_REQUIRED)
            // Local data was adopted despite confirm failure.
            assertThat(contentStore.readLocal(op.serverKey, op.userKey, op.romId, op.romHash, op.slot))
                .isEqualTo(byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun `DOWNLOAD confirmDownload network failure transitions to PENDING retry`() {
        runBlocking {
            val op = insertOp()
            replicaDao.seed(makeReplica(op, expectedSramSizeBytes = 3L))

            enqueueNegotiate(
                """{"action": "download", "rom_id": 42, "save_id": 10, "file_name": "test.srm", "slot": "autosave", "emulator": "sameboy", "reason": "newer on server", "server_content_hash": "server-hash"}"""
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
            server.enqueue(MockResponse().setResponseCode(502)) // confirm fails with 5xx -> retry
            enqueueComplete()

            val result = executor.executeOne(op)
            assertThat(result).isEqualTo(SyncNegotiateAndSyncExecutor.ExecutionOutcome.Retryable)
            assertThat(pendingOpDao.findById(op.id)!!.status).isEqualTo(PendingOperationStatus.PENDING)
        }
    }

    @Test
    fun `DOWNLOAD confirmDownload permanent 4xx transitions to PERMANENT_FAILURE`() {
        runBlocking {
            val op = insertOp()
            replicaDao.seed(makeReplica(op, expectedSramSizeBytes = 3L))

            enqueueNegotiate(
                """{"action": "download", "rom_id": 42, "save_id": 10, "file_name": "test.srm", "slot": "autosave", "emulator": "sameboy", "reason": "newer on server", "server_content_hash": "server-hash"}"""
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
            server.enqueue(MockResponse().setResponseCode(404)) // confirm fails with 404 -> permanent
            enqueueComplete()

            val result = executor.executeOne(op)
            assertThat(result).isEqualTo(SyncNegotiateAndSyncExecutor.ExecutionOutcome.Completed)
            assertThat(pendingOpDao.findById(op.id)!!.status).isEqualTo(PendingOperationStatus.PERMANENT_FAILURE)
        }
    }

    @Test
    fun `DOWNLOAD unknown provenance quarantines and transitions to PERMANENT_FAILURE`() {
        runBlocking {
            val op = insertOp()
            replicaDao.seed(makeReplica(op))

            enqueueNegotiate(
                """{"action": "download", "rom_id": 42, "save_id": 10, "file_name": "test.srm", "slot": "autosave", "emulator": "different-core", "reason": "newer on server", "server_content_hash": "server-hash"}"""
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
            enqueueComplete()

            val result = executor.executeOne(op)
            assertThat(result).isEqualTo(SyncNegotiateAndSyncExecutor.ExecutionOutcome.Completed)
            assertThat(pendingOpDao.findById(op.id)!!.status).isEqualTo(PendingOperationStatus.PERMANENT_FAILURE)
            assertThat(contentStore.quarantinedFiles).hasSize(1)
            assertThat(contentStore.quarantinedFiles[0].second).isEqualTo(byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun `DOWNLOAD size mismatch quarantines and transitions to PERMANENT_FAILURE`() {
        runBlocking {
            val op = insertOp()
            replicaDao.seed(makeReplica(op, expectedSramSizeBytes = 100L))

            enqueueNegotiate(
                """{"action": "download", "rom_id": 42, "save_id": 10, "file_name": "test.srm", "slot": "autosave", "emulator": "sameboy", "reason": "newer on server", "server_content_hash": "server-hash"}"""
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
            enqueueComplete()

            val result = executor.executeOne(op)
            assertThat(result).isEqualTo(SyncNegotiateAndSyncExecutor.ExecutionOutcome.Completed)
            assertThat(pendingOpDao.findById(op.id)!!.status).isEqualTo(PendingOperationStatus.PERMANENT_FAILURE)
            assertThat(contentStore.quarantinedFiles).hasSize(1)
        }
    }

    @Test
    fun `DOWNLOAD auth expired during download transitions to AUTH_REQUIRED terminal`() {
        runBlocking {
            val op = insertOp()
            replicaDao.seed(makeReplica(op))

            enqueueNegotiate(
                """{"action": "download", "rom_id": 42, "save_id": 10, "file_name": "test.srm", "slot": "autosave", "emulator": "sameboy", "reason": "newer on server", "server_content_hash": "server-hash"}"""
            )
            server.enqueue(MockResponse().setResponseCode(401)) // download fails with auth
            enqueueComplete()

            val result = executor.executeOne(op)
            assertThat(result).isEqualTo(SyncNegotiateAndSyncExecutor.ExecutionOutcome.Completed)
            assertThat(pendingOpDao.findById(op.id)!!.status).isEqualTo(PendingOperationStatus.AUTH_REQUIRED)
        }
    }

    @Test
    fun `DOWNLOAD 5xx during download transitions to PENDING retry`() {
        runBlocking {
            val op = insertOp()
            replicaDao.seed(makeReplica(op))

            enqueueNegotiate(
                """{"action": "download", "rom_id": 42, "save_id": 10, "file_name": "test.srm", "slot": "autosave", "emulator": "sameboy", "reason": "newer on server", "server_content_hash": "server-hash"}"""
            )
            server.enqueue(MockResponse().setResponseCode(503)) // 5xx → retryable
            enqueueComplete()

            val result = executor.executeOne(op)
            assertThat(result).isEqualTo(SyncNegotiateAndSyncExecutor.ExecutionOutcome.Retryable)
            assertThat(pendingOpDao.findById(op.id)!!.status).isEqualTo(PendingOperationStatus.PENDING)
        }
    }

    // ===== CONFLICT action tests =====

    @Test
    fun `CONFLICT action sets CONFLICT terminal state for UI`() {
        runBlocking {
            val op = insertOp()
            replicaDao.seed(makeReplica(op))

            enqueueNegotiate(
                """{"action": "conflict", "rom_id": 42, "save_id": 11, "file_name": "test.srm", "slot": "autosave", "emulator": "sameboy", "reason": "both changed"}"""
            )
            enqueueComplete()

            val result = executor.executeOne(op)
            assertThat(result).isEqualTo(SyncNegotiateAndSyncExecutor.ExecutionOutcome.Completed)
            assertThat(pendingOpDao.findById(op.id)!!.status).isEqualTo(PendingOperationStatus.CONFLICT)
            assertThat(pendingOpDao.findById(op.id)!!.lastError).isEqualTo("both changed")
            val replica = replicaDao.findByScope(op.serverKey, op.userKey, op.romId, op.romHash, op.slot)!!
            assertThat(replica.syncStatus).isEqualTo(SaveSyncStatus.CONFLICT)
            assertThat(replica.lastError).isEqualTo("both changed")
        }
    }

    // ===== Session and generation validation tests =====

    @Test
    fun `missing session transitions to AUTH_REQUIRED`() {
        runBlocking {
            val dao = FakePendingOperationDao()
            val testExecutor = SyncNegotiateAndSyncExecutorImpl(
                client = client,
                pendingOperationDao = dao,
                saveReplicaDao = FakeSaveReplicaDao(),
                saveContentStore = FakeSaveContentStore(),
                sessionReader = { null },
                deviceIdentityLoader = { _, _ -> DeviceIdentity("d", "d") },
                uploadCaller = { _, _ -> SaveUploadResult.Failure(RommApiError.NETWORK_ERROR) },
            )
            val op = insertOp(dao = dao)
            testExecutor.executeOne(op)
            assertThat(dao.findByStatus(PendingOperationStatus.AUTH_REQUIRED)).isNotEmpty()
        }
    }

    @Test
    fun `generation mismatch transitions to PERMANENT_FAILURE`() {
        runBlocking {
            val op = insertOp(localGenerationEpochMs = 1_000L)
            replicaDao.seed(makeReplica(op, localWrittenAtEpochMs = 5_000L))

            val result = executor.executeOne(op)
            assertThat(result).isEqualTo(SyncNegotiateAndSyncExecutor.ExecutionOutcome.Completed)
            assertThat(pendingOpDao.findById(op.id)!!.status).isEqualTo(PendingOperationStatus.PERMANENT_FAILURE)
        }
    }

    @Test
    fun `negotiate auth expired transitions to AUTH_REQUIRED`() {
        runBlocking {
            val op = insertOp()
            replicaDao.seed(makeReplica(op))

            server.enqueue(MockResponse().setResponseCode(401)) // negotiate fails with auth
            enqueueComplete()

            val result = executor.executeOne(op)
            assertThat(result).isEqualTo(SyncNegotiateAndSyncExecutor.ExecutionOutcome.Completed)
            assertThat(pendingOpDao.findById(op.id)!!.status).isEqualTo(PendingOperationStatus.AUTH_REQUIRED)
        }
    }

    @Test
    fun `negotiate 5xx transitions to PENDING retry`() {
        runBlocking {
            val op = insertOp()
            replicaDao.seed(makeReplica(op))

            server.enqueue(MockResponse().setResponseCode(503)) // 5xx → retryable
            enqueueComplete()

            val result = executor.executeOne(op)
            assertThat(result).isEqualTo(SyncNegotiateAndSyncExecutor.ExecutionOutcome.Retryable)
            assertThat(pendingOpDao.findById(op.id)!!.status).isEqualTo(PendingOperationStatus.PENDING)
        }
    }

    @Test
    fun `coreBuildRevision mismatch against current replica transitions to PERMANENT_FAILURE`() {
        runBlocking {
            val op = PendingOperationEntity(
                serverKey = "localhost",
                userKey = "testuser",
                romId = 42L,
                romHash = "abc123",
                slot = "autosave",
                operationType = PendingOperationType.NEGOTIATE_AND_SYNC,
                localGenerationEpochMs = 1000L,
                status = PendingOperationStatus.PENDING,
                origin = baseUrl(),
                negotiateFileName = "test.srm",
                negotiateCoreId = "sameboy",
                negotiateCoreBuildRevision = "v0.13-old", // Stale revision in operation.
                createdAtEpochMs = 1000L,
                updatedAtEpochMs = 1000L,
            )
            val opId = pendingOpDao.insert(op)
            val persistedOp = pendingOpDao.findById(opId)!!

            // Replica has a newer coreBuildRevision.
            replicaDao.seed(
                SaveReplicaEntity(
                    serverKey = "localhost", userKey = "testuser", romId = 42L, romHash = "abc123",
                    slot = "autosave", coreId = "sameboy", coreBuildRevision = "v0.14-newer",
                    localWrittenAtEpochMs = 1000L,
                )
            )

            val result = executor.executeOne(persistedOp)
            assertThat(result).isEqualTo(SyncNegotiateAndSyncExecutor.ExecutionOutcome.Completed)
            assertThat(pendingOpDao.findById(opId)!!.status).isEqualTo(PendingOperationStatus.PERMANENT_FAILURE)
        }
    }

    @Test
    fun `coreBuildRevision match against current replica allows negotiation to proceed`() {
        runBlocking {
            val op = insertOp() // negotiateCoreBuildRevision = "v0.14"
            // Replica coreBuildRevision = "v0.14" (matching).
            replicaDao.seed(makeReplica(op))

            // Negotiate returns no-op, so we complete successfully.
            enqueueNegotiate(
                """{"action": "no_op", "rom_id": 42, "save_id": 55, "file_name": "test.srm", "slot": "autosave", "emulator": "sameboy", "reason": "identical"}"""
            )
            enqueueComplete()

            val result = executor.executeOne(op)
            assertThat(result).isEqualTo(SyncNegotiateAndSyncExecutor.ExecutionOutcome.Completed)
            // With matching coreBuildRevision, no PERMANENT_FAILURE.
            assertThat(pendingOpDao.findById(op.id)!!.status).isNotEqualTo(PendingOperationStatus.PERMANENT_FAILURE)
        }
    }

}

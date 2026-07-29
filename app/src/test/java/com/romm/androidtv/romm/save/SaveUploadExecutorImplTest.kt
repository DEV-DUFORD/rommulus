package com.romm.androidtv.romm.save

import com.romm.androidtv.romm.DeviceIdentity
import com.romm.androidtv.romm.RommApiError
import com.romm.androidtv.romm.SaveUploadRequest
import com.romm.androidtv.romm.SaveUploadResult
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.time.Instant

class SaveUploadExecutorImplTest {

    @Test
    fun `empty queue returns Complete immediately`() {
        runBlocking {
            val dao = FakePendingOperationDao()
            val executor = buildExecutor(dao)
            assertThat(executor.drainBatch()).isEqualTo(SaveUploadExecutor.DrainResult.Complete)
        }
    }

    @Test
    fun `successful upload transitions to SUCCEEDED and updates replica`() {
        runBlocking {
            val dao = FakePendingOperationDao()
            val replicaDao = FakeSaveReplicaDao()
            val contentStore = FakeSaveContentStore()
            val sramBytes = byteArrayOf(1, 2, 3)

            val op = makePendingOp()
            val opId = dao.insert(op)
            contentStore.seedLocal(op.serverKey, op.userKey, op.romId, op.romHash, op.slot, sramBytes)
            replicaDao.seed(makeReplicaWithGeneration(op))

            val uploadCallCount = AtomicInteger(0)
            val executor = buildExecutor(
                pendingOperationDao = dao,
                saveReplicaDao = replicaDao,
                saveContentStore = contentStore,
                uploadBehavior = { _, _ ->
                    uploadCallCount.incrementAndGet()
                    SaveUploadResult.Success(
                        com.romm.androidtv.romm.ServerSaveInfo(
                            saveId = 42L, romId = op.romId, fileName = "test.srm",
                            slot = op.slot, emulator = null, contentHash = "abc",
                            updatedAt = Instant.now(), fileSizeBytes = 3,
                        )
                    )
                },
            )

            val result = executor.drainBatch()
            assertThat(result).isEqualTo(SaveUploadExecutor.DrainResult.Complete)
            assertThat(uploadCallCount.get()).isEqualTo(1)
            val updatedOp = dao.findById(opId)!!
            assertThat(updatedOp.status).isEqualTo(PendingOperationStatus.SUCCEEDED)
            assertThat(updatedOp.attemptCount).isEqualTo(1)

            val updatedReplica = replicaDao.findByScope(op.serverKey, op.userKey, op.romId, op.romHash, op.slot)!!
            assertThat(updatedReplica.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
            assertThat(updatedReplica.rommSaveId).isEqualTo(42L)
        }
    }

    @Test
    fun `network error transitions to RETRYABLE_FAILURE then PENDING`() {
        runBlocking {
            val dao = FakePendingOperationDao()
            val replicaDao = FakeSaveReplicaDao()
            val contentStore = FakeSaveContentStore()

            val op = makePendingOp()
            val opId = dao.insert(op)
            contentStore.seedLocal(op.serverKey, op.userKey, op.romId, op.romHash, op.slot, byteArrayOf(1))
            replicaDao.seed(makeReplicaWithGeneration(op))

            val executor = buildExecutor(
                pendingOperationDao = dao,
                saveReplicaDao = replicaDao,
                saveContentStore = contentStore,
                uploadBehavior = { _, _ -> SaveUploadResult.Failure(RommApiError.NETWORK_ERROR) },
            )

            assertThat(executor.drainBatch()).isEqualTo(SaveUploadExecutor.DrainResult.Retry)
            assertThat(dao.findById(opId)!!.status).isEqualTo(PendingOperationStatus.PENDING)
        }
    }

    @Test
    fun `TLS error transitions to PENDING for retry`() {
        runBlocking {
            val dao = FakePendingOperationDao()
            val replicaDao = FakeSaveReplicaDao()
            val contentStore = FakeSaveContentStore()

            val op = makePendingOp()
            val opId = dao.insert(op)
            contentStore.seedLocal(op.serverKey, op.userKey, op.romId, op.romHash, op.slot, byteArrayOf(0))
            replicaDao.seed(makeReplicaWithGeneration(op))

            val executor = buildExecutor(
                pendingOperationDao = dao,
                saveReplicaDao = replicaDao,
                saveContentStore = contentStore,
                uploadBehavior = { _, _ -> SaveUploadResult.Failure(RommApiError.TLS_ERROR) },
            )

            assertThat(executor.drainBatch()).isEqualTo(SaveUploadExecutor.DrainResult.Retry)
            assertThat(dao.findById(opId)!!.status).isEqualTo(PendingOperationStatus.PENDING)
        }
    }

    @Test
    fun `5xx server error transitions to PENDING for retry`() {
        runBlocking {
            val dao = FakePendingOperationDao()
            val replicaDao = FakeSaveReplicaDao()
            val contentStore = FakeSaveContentStore()

            val op = makePendingOp()
            val opId = dao.insert(op)
            contentStore.seedLocal(op.serverKey, op.userKey, op.romId, op.romHash, op.slot, byteArrayOf(0))
            replicaDao.seed(makeReplicaWithGeneration(op))

            val executor = buildExecutor(
                pendingOperationDao = dao,
                saveReplicaDao = replicaDao,
                saveContentStore = contentStore,
                uploadBehavior = { _, _ -> SaveUploadResult.Failure(RommApiError.SERVER_ERROR, 503) },
            )

            assertThat(executor.drainBatch()).isEqualTo(SaveUploadExecutor.DrainResult.Retry)
            assertThat(dao.findById(opId)!!.status).isEqualTo(PendingOperationStatus.PENDING)
        }
    }

    @Test
    fun `4xx server error transitions to PERMANENT_FAILURE`() {
        runBlocking {
            val dao = FakePendingOperationDao()
            val replicaDao = FakeSaveReplicaDao()
            val contentStore = FakeSaveContentStore()

            val op = makePendingOp()
            val opId = dao.insert(op)
            contentStore.seedLocal(op.serverKey, op.userKey, op.romId, op.romHash, op.slot, byteArrayOf(0))
            replicaDao.seed(makeReplicaWithGeneration(op))

            val executor = buildExecutor(
                pendingOperationDao = dao,
                saveReplicaDao = replicaDao,
                saveContentStore = contentStore,
                uploadBehavior = { _, _ -> SaveUploadResult.Failure(RommApiError.SERVER_ERROR, 400) },
            )

            assertThat(executor.drainBatch()).isEqualTo(SaveUploadExecutor.DrainResult.Complete)
            assertThat(dao.findById(opId)!!.status).isEqualTo(PendingOperationStatus.PERMANENT_FAILURE)
        }
    }

    @Test
    fun `401 auth expired transitions to AUTH_REQUIRED terminal`() {
        runBlocking {
            val dao = FakePendingOperationDao()
            val replicaDao = FakeSaveReplicaDao()
            val contentStore = FakeSaveContentStore()

            val op = makePendingOp()
            val opId = dao.insert(op)
            contentStore.seedLocal(op.serverKey, op.userKey, op.romId, op.romHash, op.slot, byteArrayOf(0))
            replicaDao.seed(makeReplicaWithGeneration(op))

            val executor = buildExecutor(
                pendingOperationDao = dao,
                saveReplicaDao = replicaDao,
                saveContentStore = contentStore,
                uploadBehavior = { _, _ -> SaveUploadResult.Failure(RommApiError.AUTH_EXPIRED, 401) },
            )

            assertThat(executor.drainBatch()).isEqualTo(SaveUploadExecutor.DrainResult.Complete)
            assertThat(dao.findById(opId)!!.status).isEqualTo(PendingOperationStatus.AUTH_REQUIRED)
        }
    }

    @Test
    fun `conflict transitions to CONFLICT terminal`() {
        runBlocking {
            val dao = FakePendingOperationDao()
            val replicaDao = FakeSaveReplicaDao()
            val contentStore = FakeSaveContentStore()

            val op = makePendingOp()
            val opId = dao.insert(op)
            contentStore.seedLocal(op.serverKey, op.userKey, op.romId, op.romHash, op.slot, byteArrayOf(0))
            replicaDao.seed(makeReplicaWithGeneration(op))

            val executor = buildExecutor(
                pendingOperationDao = dao,
                saveReplicaDao = replicaDao,
                saveContentStore = contentStore,
                uploadBehavior = { _, _ -> SaveUploadResult.Conflict(409) },
            )

            assertThat(executor.drainBatch()).isEqualTo(SaveUploadExecutor.DrainResult.Complete)
            assertThat(dao.findById(opId)!!.status).isEqualTo(PendingOperationStatus.CONFLICT)
        }
    }

    @Test
    fun `missing session transitions to AUTH_REQUIRED`() {
        runBlocking {
            val dao = FakePendingOperationDao()
            val op = makePendingOp()
            val opId = dao.insert(op)

            val executor = buildExecutor(
                pendingOperationDao = dao,
                sessionReader = { null },
            )

            assertThat(executor.drainBatch()).isEqualTo(SaveUploadExecutor.DrainResult.Complete)
            assertThat(dao.findById(opId)!!.status).isEqualTo(PendingOperationStatus.AUTH_REQUIRED)
        }
    }

    @Test
    fun `missing device identity transitions to AUTH_REQUIRED`() {
        runBlocking {
            val dao = FakePendingOperationDao()
            val op = makePendingOp()
            val opId = dao.insert(op)

            val executor = buildExecutor(
                pendingOperationDao = dao,
                deviceIdentityLoader = { _, _ -> null },
            )

            assertThat(executor.drainBatch()).isEqualTo(SaveUploadExecutor.DrainResult.Complete)
            assertThat(dao.findById(opId)!!.status).isEqualTo(PendingOperationStatus.AUTH_REQUIRED)
        }
    }

    @Test
    fun `missing local SRAM transitions to PERMANENT_FAILURE`() {
        runBlocking {
            val dao = FakePendingOperationDao()
            val replicaDao = FakeSaveReplicaDao()
            val contentStore = FakeSaveContentStore()

            val op = makePendingOp()
            val opId = dao.insert(op)
            replicaDao.seed(makeReplicaWithGeneration(op))

            val executor = buildExecutor(
                pendingOperationDao = dao,
                saveReplicaDao = replicaDao,
                saveContentStore = contentStore,
            )

            assertThat(executor.drainBatch()).isEqualTo(SaveUploadExecutor.DrainResult.Complete)
            assertThat(dao.findById(opId)!!.status).isEqualTo(PendingOperationStatus.PERMANENT_FAILURE)
        }
    }

    @Test
    fun `legacy null origin fails explicitly with PERMANENT_FAILURE`() {
        runBlocking {
            val dao = FakePendingOperationDao()
            val op = makePendingOp(origin = null)
            val opId = dao.insert(op)

            val executor = buildExecutor(pendingOperationDao = dao)

            assertThat(executor.drainBatch()).isEqualTo(SaveUploadExecutor.DrainResult.Complete)
            val updated = dao.findById(opId)!!
            assertThat(updated.status).isEqualTo(PendingOperationStatus.PERMANENT_FAILURE)
            assertThat(updated.lastError).contains("origin")
        }
    }

    @Test
    fun `legacy null uploadFileName fails explicitly with PERMANENT_FAILURE`() {
        runBlocking {
            val dao = FakePendingOperationDao()
            val op = makePendingOp(uploadFileName = null)
            val opId = dao.insert(op)

            val executor = buildExecutor(pendingOperationDao = dao)

            assertThat(executor.drainBatch()).isEqualTo(SaveUploadExecutor.DrainResult.Complete)
            val updated = dao.findById(opId)!!
            assertThat(updated.status).isEqualTo(PendingOperationStatus.PERMANENT_FAILURE)
            assertThat(updated.lastError).contains("filename")
        }
    }

    @Test
    fun `generation mismatch fails explicitly with PERMANENT_FAILURE`() {
        runBlocking {
            val dao = FakePendingOperationDao()
            val replicaDao = FakeSaveReplicaDao()
            val contentStore = FakeSaveContentStore()

            val op = makePendingOp(localGenerationEpochMs = 1_000L)
            val opId = dao.insert(op)
            contentStore.seedLocal(op.serverKey, op.userKey, op.romId, op.romHash, op.slot, byteArrayOf(1))
            // Seed replica with a different generation (newer local write superseded the operation)
            val mismatchedReplica = makeReplicaWithGeneration(op).copy(localWrittenAtEpochMs = 5_000L)
            replicaDao.seed(mismatchedReplica)

            val executor = buildExecutor(
                pendingOperationDao = dao,
                saveReplicaDao = replicaDao,
                saveContentStore = contentStore,
            )

            assertThat(executor.drainBatch()).isEqualTo(SaveUploadExecutor.DrainResult.Complete)
            val updated = dao.findById(opId)!!
            assertThat(updated.status).isEqualTo(PendingOperationStatus.PERMANENT_FAILURE)
            assertThat(updated.lastError).contains("generation")
        }
    }

    @Test
    fun `attempt count is preserved through retryable failure`() {
        runBlocking {
            val dao = FakePendingOperationDao()
            val replicaDao = FakeSaveReplicaDao()
            val contentStore = FakeSaveContentStore()

            // Start with attemptCount=2 (already retried once)
            val op = makePendingOp().copy(attemptCount = 2)
            val opId = dao.insert(op)
            contentStore.seedLocal(op.serverKey, op.userKey, op.romId, op.romHash, op.slot, byteArrayOf(1))
            replicaDao.seed(makeReplicaWithGeneration(op))

            val executor = buildExecutor(
                pendingOperationDao = dao,
                saveReplicaDao = replicaDao,
                saveContentStore = contentStore,
                uploadBehavior = { _, _ -> SaveUploadResult.Failure(RommApiError.NETWORK_ERROR) },
            )

            assertThat(executor.drainBatch()).isEqualTo(SaveUploadExecutor.DrainResult.Retry)
            val updated = dao.findById(opId)!!
            assertThat(updated.status).isEqualTo(PendingOperationStatus.PENDING)
            // attemptCount should be 3 (2 + 1 for this run), preserved through RETRYABLE->PENDING
            assertThat(updated.attemptCount).isEqualTo(3)
        }
    }

    @Test
    fun `mixed batch one success one retry returns Retry`() {
        runBlocking {
            val dao = FakePendingOperationDao()
            val replicaDao = FakeSaveReplicaDao()
            val contentStore = FakeSaveContentStore()

            val op1 = makePendingOp(idOverride = 0L)
            val op2 = makePendingOp(idOverride = 0L, serverKey = "other-server")
            val op1Id = dao.insert(op1)
            val op2Id = dao.insert(op2)

            for (op in listOf(op1, op2)) {
                contentStore.seedLocal(op.serverKey, op.userKey, op.romId, op.romHash, op.slot, byteArrayOf(1))
                replicaDao.seed(makeReplicaWithGeneration(op))
            }

            val callCount = AtomicInteger(0)
            val executor = buildExecutor(
                pendingOperationDao = dao,
                saveReplicaDao = replicaDao,
                saveContentStore = contentStore,
                uploadBehavior = { _, _ ->
                    if (callCount.incrementAndGet() == 1) {
                        SaveUploadResult.Success(
                            com.romm.androidtv.romm.ServerSaveInfo(
                                saveId = 42L, romId = op1.romId, fileName = "test.srm",
                                slot = op1.slot, emulator = null, contentHash = "abc",
                                updatedAt = Instant.now(), fileSizeBytes = 1,
                            )
                        )
                    } else {
                        SaveUploadResult.Failure(RommApiError.NETWORK_ERROR)
                    }
                },
            )

            assertThat(executor.drainBatch()).isEqualTo(SaveUploadExecutor.DrainResult.Retry)
            assertThat(dao.findById(op1Id)!!.status).isEqualTo(PendingOperationStatus.SUCCEEDED)
            assertThat(dao.findById(op2Id)!!.status).isEqualTo(PendingOperationStatus.PENDING)
        }
    }

    // ---- Helpers ----

    private fun makePendingOp(
        idOverride: Long = 0L,
        serverKey: String = "test-server",
        localGenerationEpochMs: Long = System.currentTimeMillis(),
        origin: String? = "https://romm.example.com",
        uploadFileName: String? = "pokemon_blue.srm",
    ): PendingOperationEntity {
        val now = System.currentTimeMillis()
        return PendingOperationEntity(
            id = idOverride,
            serverKey = serverKey,
            userKey = "testuser",
            romId = 42L,
            romHash = "abc123",
            slot = "autosave",
            operationType = PendingOperationType.UPLOAD,
            localGenerationEpochMs = localGenerationEpochMs,
            status = PendingOperationStatus.PENDING,
            origin = origin,
            uploadFileName = uploadFileName,
            sessionId = 99L,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
    }

    private fun makeReplicaWithGeneration(op: PendingOperationEntity): SaveReplicaEntity {
        return SaveReplicaEntity(
            serverKey = op.serverKey,
            userKey = op.userKey,
            romId = op.romId,
            romHash = op.romHash,
            slot = op.slot,
            coreId = "sameboy",
            coreBuildRevision = "v0.14",
            expectedSramSizeBytes = 32768,
            localWrittenAtEpochMs = op.localGenerationEpochMs,
        )
    }

    private fun buildExecutor(
        pendingOperationDao: FakePendingOperationDao = FakePendingOperationDao(),
        saveReplicaDao: SaveReplicaDao = FakeSaveReplicaDao(),
        saveContentStore: FakeSaveContentStore = FakeSaveContentStore(),
        sessionReader: SessionReader = SessionReader {
            DurableSession("https://romm.example.com", "testuser")
        },
        deviceIdentityLoader: DeviceIdentityLoader = DeviceIdentityLoader { _, _ ->
            DeviceIdentity("install-uuid", "device-uuid")
        },
        uploadBehavior: (String, SaveUploadRequest) -> SaveUploadResult = { _, _ ->
            SaveUploadResult.Failure(RommApiError.NETWORK_ERROR)
        },
    ): SaveUploadExecutorImpl {
        return SaveUploadExecutorImpl(
            pendingOperationDao = pendingOperationDao,
            saveReplicaDao = saveReplicaDao,
            saveContentStore = saveContentStore,
            sessionReader = sessionReader,
            deviceIdentityLoader = deviceIdentityLoader,
            uploadCaller = SaveUploadCaller { origin, request ->
                uploadBehavior(origin, request)
            },
        )
    }
}

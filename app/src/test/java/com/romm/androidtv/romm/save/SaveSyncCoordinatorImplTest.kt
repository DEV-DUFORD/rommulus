package com.romm.androidtv.romm.save

import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.config.FakeSharedPreferences
import com.romm.androidtv.romm.DeviceIdentityStore
import com.romm.androidtv.romm.DeviceRepositoryImpl
import com.romm.androidtv.romm.RommApiError
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Exercises [SaveSyncCoordinatorImpl] end-to-end against a real
 * [MockWebServer] (for `RommSyncApi`'s HTTP contract) but in-memory fakes for
 * [SaveReplicaDao]/[PendingOperationDao]/[SaveContentStore] — the coordinator's
 * negotiation *logic* is fully testable this way without a real Room database
 * or filesystem; [SaveReplicaDaoInstrumentedTest]/[PendingOperationDaoInstrumentedTest]
 * separately verify the real Room-backed DAOs themselves.
 */
class SaveSyncCoordinatorImplTest {

    private lateinit var server: MockWebServer
    private lateinit var client: okhttp3.OkHttpClient
    private lateinit var sessionStore: SessionStore
    private lateinit var deviceRepository: DeviceRepositoryImpl
    private lateinit var saveReplicaDao: FakeSaveReplicaDao
    private lateinit var pendingOperationDao: FakePendingOperationDao
    private lateinit var saveContentStore: FakeSaveContentStore
    private lateinit var coordinator: SaveSyncCoordinatorImpl

    private var clockValue = 10_000L

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start(0)
        client = okhttp3.OkHttpClient.Builder().build()
        sessionStore = SessionStore(FakeSharedPreferences())
        sessionStore.save(baseUrl(), "alice")
        deviceRepository = DeviceRepositoryImpl(client, DeviceIdentityStore(FakeSharedPreferences()))
        saveReplicaDao = FakeSaveReplicaDao()
        pendingOperationDao = FakePendingOperationDao()
        saveContentStore = FakeSaveContentStore()
        coordinator = SaveSyncCoordinatorImpl(
            client, sessionStore, deviceRepository, saveReplicaDao, pendingOperationDao, saveContentStore,
            clock = { clockValue },
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString().removeSuffix("/")

    private fun enqueueDeviceRegistered() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"device_id": "device-1", "name": null, "created_at": "2026-01-01T00:00:00Z"}""")
        )
    }

    private fun enqueueNegotiate(operationsJson: String) {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"session_id": 7, "operations": [$operationsJson], "total_upload": 0, "total_download": 0, "total_conflict": 0, "total_no_op": 0}"""
            )
        )
    }

    private fun enqueueComplete() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"session": {"id": 7, "status": "COMPLETED"}}""")
        )
    }

    private fun request(romId: Long = 1L, romHash: String = "hash-a", expectedSramSizeBytes: Long = 3L) = SaveSyncRequest(
        romId = romId,
        romHash = romHash,
        coreId = "sameboy",
        coreBuildRevision = "sameboy-v0.16.2",
        expectedSramSizeBytes = expectedSramSizeBytes,
        fileName = "autosave.srm",
    )

    @Test
    fun `device registration failure short-circuits before any negotiate call`() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(401))

            val outcome = coordinator.syncBeforeLaunch(request())

            assertThat(outcome).isInstanceOf(SaveSyncOutcome.Failure::class.java)
            assertThat((outcome as SaveSyncOutcome.Failure).error).isEqualTo(RommApiError.AUTH_EXPIRED)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun `no_op records agreed metadata and completes the session`() {
        runBlocking {
            enqueueDeviceRegistered()
            enqueueNegotiate(
                """{"action": "no_op", "rom_id": 1, "save_id": 55, "file_name": "autosave.srm", "slot": "autosave", "emulator": "sameboy", "reason": "identical", "server_content_hash": "hash-x"}"""
            )
            enqueueComplete()

            val outcome = coordinator.syncBeforeLaunch(request())

            assertThat(outcome).isEqualTo(SaveSyncOutcome.NoOpSynced(7))
            val replica = saveReplicaDao.findByScope("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(replica).isNotNull
            assertThat(replica!!.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
            assertThat(replica.rommSaveId).isEqualTo(55)
            assertThat(replica.serverHash).isEqualTo("hash-x")
        }
    }

    @Test
    fun `download adopts server bytes, confirms, and completes the session`() {
        runBlocking {
            enqueueDeviceRegistered()
            enqueueNegotiate(
                """{"action": "download", "rom_id": 1, "save_id": 10, "file_name": "autosave.srm", "slot": "autosave", "emulator": "sameboy", "reason": "newer on server", "server_updated_at": "2026-01-02T00:00:00Z", "server_content_hash": "hash1"}"""
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
            server.enqueue(MockResponse().setResponseCode(200)) // /downloaded confirm
            enqueueComplete()

            val outcome = coordinator.syncBeforeLaunch(request())

            assertThat(outcome).isEqualTo(SaveSyncOutcome.Downloaded(7, 10, 3, true))
            val adopted = saveContentStore.readLocal("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(adopted).isEqualTo(byteArrayOf(1, 2, 3))
            val replica = saveReplicaDao.findByScope("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(replica!!.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
            assertThat(replica.rommSaveId).isEqualTo(10)
            assertThat(saveContentStore.quarantinedFiles).isEmpty()
        }
    }

    @Test
    fun `download with mismatched emulator is quarantined and never confirmed`() {
        runBlocking {
            enqueueDeviceRegistered()
            enqueueNegotiate(
                """{"action": "download", "rom_id": 1, "save_id": 10, "file_name": "autosave.srm", "slot": "autosave", "emulator": "some-other-core", "reason": "newer on server", "server_content_hash": "hash1"}"""
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
            enqueueComplete()

            val outcome = coordinator.syncBeforeLaunch(request())

            assertThat(outcome).isInstanceOf(SaveSyncOutcome.Quarantined::class.java)
            assertThat((outcome as SaveSyncOutcome.Quarantined).reason).isEqualTo("unknown-provenance")
            assertThat(saveContentStore.readLocal("localhost", "alice", 1L, "hash-a", "autosave")).isNull()
            assertThat(saveContentStore.quarantinedFiles).hasSize(1)
            val replica = saveReplicaDao.findByScope("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(replica!!.syncStatus).isEqualTo(SaveSyncStatus.QUARANTINED)
            // Only device-register + negotiate + download + complete — no /downloaded confirm request.
            assertThat(server.requestCount).isEqualTo(4)
        }
    }

    @Test
    fun `download with wrong size is quarantined`() {
        runBlocking {
            enqueueDeviceRegistered()
            enqueueNegotiate(
                """{"action": "download", "rom_id": 1, "save_id": 10, "file_name": "autosave.srm", "slot": "autosave", "emulator": "sameboy", "reason": "newer on server", "server_content_hash": "hash1"}"""
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(byteArrayOf(1, 2)))) // 2 bytes, expected 3
            enqueueComplete()

            val outcome = coordinator.syncBeforeLaunch(request(expectedSramSizeBytes = 3L))

            assertThat(outcome).isInstanceOf(SaveSyncOutcome.Quarantined::class.java)
            assertThat((outcome as SaveSyncOutcome.Quarantined).reason).isEqualTo("size-mismatch")
        }
    }

    @Test
    fun `upload queues a pending operation instead of uploading inline`() {
        runBlocking {
            saveContentStore.seedLocal("localhost", "alice", 1L, "hash-a", "autosave", byteArrayOf(9, 9, 9))
            saveReplicaDao.upsert(
                SaveReplicaEntity(
                    serverKey = "localhost", userKey = "alice", romId = 1L, romHash = "hash-a", slot = "autosave",
                    coreId = "sameboy", coreBuildRevision = "sameboy-v0.16.2", expectedSramSizeBytes = 3L,
                    localHash = "local-hash", localSizeBytes = 3L, localWrittenAtEpochMs = 5_000L,
                )
            )
            enqueueDeviceRegistered()
            enqueueNegotiate(
                """{"action": "upload", "rom_id": 1, "file_name": "autosave.srm", "slot": "autosave", "reason": "not on server"}"""
            )
            enqueueComplete()

            val outcome = coordinator.syncBeforeLaunch(request())

            assertThat(outcome).isInstanceOf(SaveSyncOutcome.UploadQueued::class.java)
            val pendingId = (outcome as SaveSyncOutcome.UploadQueued).pendingOperationId
            val op = pendingOperationDao.findById(pendingId)
            assertThat(op).isNotNull
            assertThat(op!!.status).isEqualTo(PendingOperationStatus.PENDING)
            assertThat(op.operationType).isEqualTo(PendingOperationType.UPLOAD)
            assertThat(op.localGenerationEpochMs).isEqualTo(5_000L)
            val replica = saveReplicaDao.findByScope("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(replica!!.syncStatus).isEqualTo(SaveSyncStatus.PENDING_UPLOAD)
        }
    }

    @Test
    fun `upload is idempotent across repeated syncs for the same local generation`() {
        runBlocking {
            saveContentStore.seedLocal("localhost", "alice", 1L, "hash-a", "autosave", byteArrayOf(9, 9, 9))
            saveReplicaDao.upsert(
                SaveReplicaEntity(
                    serverKey = "localhost", userKey = "alice", romId = 1L, romHash = "hash-a", slot = "autosave",
                    coreId = "sameboy", coreBuildRevision = "sameboy-v0.16.2", expectedSramSizeBytes = 3L,
                    localWrittenAtEpochMs = 5_000L,
                )
            )
            enqueueDeviceRegistered()
            enqueueNegotiate("""{"action": "upload", "rom_id": 1, "file_name": "autosave.srm", "slot": "autosave", "reason": "not on server"}""")
            enqueueComplete()
            val first = coordinator.syncBeforeLaunch(request()) as SaveSyncOutcome.UploadQueued

            enqueueDeviceRegistered()
            enqueueNegotiate("""{"action": "upload", "rom_id": 1, "file_name": "autosave.srm", "slot": "autosave", "reason": "not on server"}""")
            enqueueComplete()
            val second = coordinator.syncBeforeLaunch(request()) as SaveSyncOutcome.UploadQueued

            assertThat(second.pendingOperationId).isEqualTo(first.pendingOperationId)
            assertThat(pendingOperationDao.allRows()).hasSize(1)
        }
    }

    @Test
    fun `conflict stops automatic replacement and preserves the operation for later resolution`() {
        runBlocking {
            enqueueDeviceRegistered()
            enqueueNegotiate(
                """{"action": "conflict", "rom_id": 1, "save_id": 11, "file_name": "autosave.srm", "slot": "autosave", "reason": "both changed"}"""
            )
            enqueueComplete()

            val outcome = coordinator.syncBeforeLaunch(request())

            assertThat(outcome).isInstanceOf(SaveSyncOutcome.ConflictRequiresResolution::class.java)
            val conflict = outcome as SaveSyncOutcome.ConflictRequiresResolution
            assertThat(conflict.operation.reason).isEqualTo("both changed")
            val replica = saveReplicaDao.findByScope("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(replica!!.syncStatus).isEqualTo(SaveSyncStatus.CONFLICT)
            assertThat(replica.lastError).isEqualTo("both changed")
            assertThat(saveContentStore.readLocal("localhost", "alice", 1L, "hash-a", "autosave")).isNull()
        }
    }
}

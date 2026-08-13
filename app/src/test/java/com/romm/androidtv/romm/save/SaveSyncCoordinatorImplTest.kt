package com.romm.androidtv.romm.save

import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.config.FakeSharedPreferences
import com.romm.androidtv.romm.DeviceIdentityStore
import com.romm.androidtv.romm.DeviceRepositoryImpl
import com.romm.androidtv.romm.RommApiError
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
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
    private var operationQueuedCount = 0

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
            onOperationQueued = { operationQueuedCount++ },
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

    private fun request(romId: Long = 1L, romHash: String = "hash-a", expectedSramSizeBytes: Long? = 3L) = SaveSyncRequest(
        romId = romId,
        romHash = romHash,
        coreId = "sameboy",
        coreBuildRevision = "v1.0.3-libretro",
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
    fun `negotiate transport failure with a valid local save returns PlayOfflineLocal and keeps local bytes`() {
        runBlocking {
            saveContentStore.seedLocal("localhost", "alice", 1L, "hash-a", "autosave", byteArrayOf(9, 9, 9))
            saveReplicaDao.upsert(
                SaveReplicaEntity(
                    serverKey = "localhost", userKey = "alice", romId = 1L, romHash = "hash-a", slot = "autosave",
                    coreId = "sameboy", coreBuildRevision = "v1.0.3-libretro", expectedSramSizeBytes = 3L,
                    localHash = "local-hash", localSizeBytes = 3L, localWrittenAtEpochMs = 5_000L,
                )
            )
            enqueueDeviceRegistered()
            // Negotiate fails at the transport layer (server unreachable).
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

            val outcome = coordinator.syncBeforeLaunch(request())

            assertThat(outcome).isInstanceOf(SaveSyncOutcome.PlayOfflineLocal::class.java)
            assertThat((outcome as SaveSyncOutcome.PlayOfflineLocal).error).isEqualTo(RommApiError.NETWORK_ERROR)
            // The prior locale baseline is left intact for offline play.
            assertThat(saveContentStore.readLocal("localhost", "alice", 1L, "hash-a", "autosave"))
                .isEqualTo(byteArrayOf(9, 9, 9))
        }
    }

    @Test
    fun `negotiate transport failure without a local save still returns Failure`() {
        runBlocking {
            enqueueDeviceRegistered()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

            val outcome = coordinator.syncBeforeLaunch(request())

            assertThat(outcome).isInstanceOf(SaveSyncOutcome.Failure::class.java)
            assertThat((outcome as SaveSyncOutcome.Failure).error).isEqualTo(RommApiError.NETWORK_ERROR)
        }
    }

    @Test
    fun `device registration auth failure with a local save still returns Failure (not offline)`() {
        // AUTH_EXPIRED is a definitive auth outcome, not a transient outage — it must never
        // silently fall back to offline play; the caller should re-authenticate instead.
        runBlocking {
            saveContentStore.seedLocal("localhost", "alice", 1L, "hash-a", "autosave", byteArrayOf(9, 9, 9))
            saveReplicaDao.upsert(
                SaveReplicaEntity(
                    serverKey = "localhost", userKey = "alice", romId = 1L, romHash = "hash-a", slot = "autosave",
                    coreId = "sameboy", coreBuildRevision = "v1.0.3-libretro", expectedSramSizeBytes = 3L,
                    localHash = "local-hash", localSizeBytes = 3L, localWrittenAtEpochMs = 5_000L,
                )
            )
            server.enqueue(MockResponse().setResponseCode(401))

            val outcome = coordinator.syncBeforeLaunch(request())

            assertThat(outcome).isInstanceOf(SaveSyncOutcome.Failure::class.java)
            assertThat((outcome as SaveSyncOutcome.Failure).error).isEqualTo(RommApiError.AUTH_EXPIRED)
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
    fun `negotiate response with no operation for this rom is treated as no-op, not a parse failure`() {
        runBlocking {
            // Fresh title: no local save, nothing recorded on the server either. The server
            // legitimately omits an operation entry entirely in this case (there is nothing to
            // reconcile) — this must not surface as PARSE_ERROR and block the game from launching.
            enqueueDeviceRegistered()
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"session_id": 7, "operations": [], "total_upload": 0, "total_download": 0, "total_conflict": 0, "total_no_op": 0}"""
                )
            )
            enqueueComplete()

            val outcome = coordinator.syncBeforeLaunch(request())

            assertThat(outcome).isEqualTo(SaveSyncOutcome.NoOpSynced(7))
            assertThat(saveReplicaDao.findByScope("localhost", "alice", 1L, "hash-a", "autosave")).isNull()
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
                    coreId = "sameboy", coreBuildRevision = "v1.0.3-libretro", expectedSramSizeBytes = 3L,
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
    fun `upload action with no local replica and a server save falls back to download-adopt instead of failing`() {
        // Regression test for the "first sync" bug: a cloud save already exists (e.g. uploaded
        // from another device or the web UI) but this device has never downloaded anything yet —
        // no SaveReplicaEntity, no local bytes. If the server still negotiates "upload" for this
        // scope (e.g. because it saw no reported client state), the coordinator must NOT fail on
        // the bad assumption that a local baseline already exists; it must treat the server's copy
        // as authoritative, download it, and adopt it locally — exactly like a "download" action.
        runBlocking {
            enqueueDeviceRegistered()
            enqueueNegotiate(
                """{"action": "upload", "rom_id": 1, "save_id": 42, "file_name": "autosave.srm", "slot": "autosave", "emulator": "sameboy", "reason": "no client state reported", "server_content_hash": "hash1"}"""
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
            server.enqueue(MockResponse().setResponseCode(200)) // /downloaded confirm
            enqueueComplete()

            val outcome = coordinator.syncBeforeLaunch(request())

            assertThat(outcome).isEqualTo(SaveSyncOutcome.Downloaded(7, 42, 3, true))
            val adopted = saveContentStore.readLocal("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(adopted).isEqualTo(byteArrayOf(1, 2, 3))
            val replica = saveReplicaDao.findByScope("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(replica).isNotNull
            assertThat(replica!!.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
            assertThat(replica.rommSaveId).isEqualTo(42)
            assertThat(replica.lastError).isNull()
        }
    }

    @Test
    fun `upload action with no local replica and no server save id is a benign no-op, not a failure`() {
        // Nothing local and nothing addressable on the server for this scope — there is genuinely
        // nothing to reconcile. Must not surface as PARSE_ERROR / block launch.
        runBlocking {
            enqueueDeviceRegistered()
            enqueueNegotiate(
                """{"action": "upload", "rom_id": 1, "file_name": "autosave.srm", "slot": "autosave", "reason": "not on server"}"""
            )
            enqueueComplete()

            val outcome = coordinator.syncBeforeLaunch(request())

            assertThat(outcome).isEqualTo(SaveSyncOutcome.NoOpSynced(7))
            assertThat(saveReplicaDao.findByScope("localhost", "alice", 1L, "hash-a", "autosave")).isNull()
        }
    }

    @Test
    fun `upload is idempotent across repeated syncs for the same local generation`() {
        runBlocking {
            saveContentStore.seedLocal("localhost", "alice", 1L, "hash-a", "autosave", byteArrayOf(9, 9, 9))
            saveReplicaDao.upsert(
                SaveReplicaEntity(
                    serverKey = "localhost", userKey = "alice", romId = 1L, romHash = "hash-a", slot = "autosave",
                    coreId = "sameboy", coreBuildRevision = "v1.0.3-libretro", expectedSramSizeBytes = 3L,
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

    @Test
    fun `download with known size adopts server bytes, confirms, and completes the session`() {
        runBlocking {
            enqueueDeviceRegistered()
            enqueueNegotiate(
                """{"action": "download", "rom_id": 1, "save_id": 10, "file_name": "autosave.srm", "slot": "autosave", "emulator": "sameboy", "reason": "newer on server", "server_updated_at": "2026-01-02T00:00:00Z", "server_content_hash": "hash1"}"""
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
            server.enqueue(MockResponse().setResponseCode(200)) // /downloaded confirm
            enqueueComplete()

            val outcome = coordinator.syncBeforeLaunch(request(expectedSramSizeBytes = 3L))

            assertThat(outcome).isEqualTo(SaveSyncOutcome.Downloaded(7, 10, 3, true))
            val adopted = saveContentStore.readLocal("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(adopted).isEqualTo(byteArrayOf(1, 2, 3))
            val replica = saveReplicaDao.findByScope("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(replica!!.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
            assertThat(saveContentStore.quarantinedFiles).isEmpty()
        }
    }

    @Test
    fun `download with unknown size returns AwaitingCoreValidation, quarantines, does not confirm`() {
        runBlocking {
            enqueueDeviceRegistered()
            enqueueNegotiate(
                """{"action": "download", "rom_id": 1, "save_id": 10, "file_name": "autosave.srm", "slot": "autosave", "emulator": "sameboy", "reason": "newer on server", "server_updated_at": "2026-01-02T00:00:00Z", "server_content_hash": "hash1"}"""
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(byteArrayOf(1, 2, 3, 4, 5))))
            // No /downloaded confirm or complete expected

            val outcome = coordinator.syncBeforeLaunch(request(expectedSramSizeBytes = null))

            assertThat(outcome).isInstanceOf(SaveSyncOutcome.AwaitingCoreValidation::class.java)
            val awaiting = outcome as SaveSyncOutcome.AwaitingCoreValidation
            assertThat(awaiting.sessionId).isEqualTo(7)
            assertThat(awaiting.rommSaveId).isEqualTo(10)
            assertThat(awaiting.downloadedSizeBytes).isEqualTo(5L)
            assertThat(awaiting.serverContentHash).isEqualTo("hash1")
            assertThat(awaiting.emulator).isEqualTo("sameboy")
            assertThat(saveContentStore.readLocal("localhost", "alice", 1L, "hash-a", "autosave")).isNull()
            assertThat(saveContentStore.quarantinedFiles).hasSize(1)
            val quarantinedBytes = saveContentStore.readQuarantined(awaiting.quarantinedPath)
            assertThat(quarantinedBytes).isEqualTo(byteArrayOf(1, 2, 3, 4, 5))
            val replica = saveReplicaDao.findByScope("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(replica!!.syncStatus).isEqualTo(SaveSyncStatus.AWAITING_CORE_VALIDATION)
            assertThat(replica.rommSaveId).isEqualTo(10)
            assertThat(replica.serverHash).isEqualTo("hash1")
            assertThat(replica.serverSizeBytes).isEqualTo(5L)
            // Only device-register + negotiate + download — no /downloaded confirm, no complete.
            assertThat(server.requestCount).isEqualTo(3)
        }
    }

    @Test
    fun `download with unknown size but mismatched provenance is permanently quarantined`() {
        runBlocking {
            enqueueDeviceRegistered()
            enqueueNegotiate(
                """{"action": "download", "rom_id": 1, "save_id": 10, "file_name": "autosave.srm", "slot": "autosave", "emulator": "different-core", "reason": "newer on server", "server_content_hash": "hash1"}"""
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
            enqueueComplete()

            val outcome = coordinator.syncBeforeLaunch(request(expectedSramSizeBytes = null))

            assertThat(outcome).isInstanceOf(SaveSyncOutcome.Quarantined::class.java)
            assertThat((outcome as SaveSyncOutcome.Quarantined).reason).isEqualTo("unknown-provenance")
            // Provenance mismatch is permanent quarantine regardless of size knowledge.
            assertThat(server.requestCount).isEqualTo(4) // register + negotiate + download + complete
        }
    }

    @Test
    fun `download with known size mismatch is quarantined and session completed as failed`() {
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
            assertThat(server.requestCount).isEqualTo(4) // register + negotiate + download + complete
        }
    }

    @Test
    fun `download adoption succeeds locally even when completeSyncSession fails`() {
        runBlocking {
            enqueueDeviceRegistered()
            enqueueNegotiate(
                """{"action": "download", "rom_id": 1, "save_id": 10, "file_name": "autosave.srm", "slot": "autosave", "emulator": "sameboy", "reason": "newer on server", "server_updated_at": "2026-01-02T00:00:00Z", "server_content_hash": "hash1"}"""
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
            server.enqueue(MockResponse().setResponseCode(200)) // confirm download
            server.enqueue(MockResponse().setResponseCode(500)) // completeSyncSession fails

            val outcome = coordinator.syncBeforeLaunch(request(expectedSramSizeBytes = 3L))

            // Local adoption still succeeds — completeSyncSession is best-effort.
            assertThat(outcome).isEqualTo(SaveSyncOutcome.Downloaded(7, 10, 3, true))

            // Local data was adopted and replica persisted despite server completion failure.
            val adopted = saveContentStore.readLocal("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(adopted).isEqualTo(byteArrayOf(1, 2, 3))
            val replica = saveReplicaDao.findByScope("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(replica!!.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
        }
    }

    @Test
    fun `no-op sync succeeds locally even when completeSyncSession fails`() {
        runBlocking {
            enqueueDeviceRegistered()
            enqueueNegotiate(
                """{"action": "no_op", "rom_id": 1, "save_id": 55, "file_name": "autosave.srm", "slot": "autosave", "emulator": "sameboy", "reason": "identical", "server_content_hash": "hash-x"}"""
            )
            server.enqueue(MockResponse().setResponseCode(503)) // completeSyncSession fails

            val outcome = coordinator.syncBeforeLaunch(request())

            assertThat(outcome).isEqualTo(SaveSyncOutcome.NoOpSynced(7))
            val replica = saveReplicaDao.findByScope("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(replica!!.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
        }
    }

    // ---- syncPostPlay tests (section 11.3 post-play) ----

    @Test
    fun `syncPostPlay unchanged bytes returns Unchanged`() {
        runBlocking {
            // Seed existing replica with matching hash.
            saveReplicaDao.seed(SaveReplicaEntity(
                serverKey = "localhost", userKey = "alice", romId = 1L, romHash = "hash-a",
                slot = "autosave", coreId = "sameboy", coreBuildRevision = "v1.6",
                localHash = "checkpoint-hash-abc", localWrittenAtEpochMs = 10_000L,
                syncStatus = SaveSyncStatus.SYNCED,
            ))

            val result = coordinator.syncPostPlay(PostPlayCheckpointRequest(
                serverKey = "localhost", userKey = "alice", romId = 1L, romHash = "hash-a",
                slot = "autosave", coreId = "sameboy", coreBuildRevision = "v1.6",
                fileName = "test.srm", checkpointedHash = "checkpoint-hash-abc",
                checkpointedSizeBytes = 128,
            ))

            assertThat(result).isEqualTo(PostPlayCheckpointResult.Unchanged)
            // No operation enqueued.
            assertThat(pendingOperationDao.findByStatus(PendingOperationStatus.PENDING)).isEmpty()
        }
    }

    @Test
    fun `syncPostPlay changed bytes queues NEGOTIATE_AND_SYNC`() {
        runBlocking {
            // Seed existing replica with different hash.
            saveReplicaDao.seed(SaveReplicaEntity(
                serverKey = "localhost", userKey = "alice", romId = 1L, romHash = "hash-a",
                slot = "autosave", coreId = "sameboy", coreBuildRevision = "v1.6",
                localHash = "old-hash", localWrittenAtEpochMs = 9_000L,
            ))

            val result = coordinator.syncPostPlay(PostPlayCheckpointRequest(
                serverKey = "localhost", userKey = "alice", romId = 1L, romHash = "hash-a",
                slot = "autosave", coreId = "sameboy", coreBuildRevision = "v1.6",
                fileName = "test.srm", checkpointedHash = "new-checkpoint-hash",
                checkpointedSizeBytes = 256,
            ))

            assertThat(result).isInstanceOf(PostPlayCheckpointResult.Queued::class.java)
            val queued = result as PostPlayCheckpointResult.Queued
            assertThat(queued.pendingOperationId).isGreaterThan(0)

            // Replica updated with new generation.
            val replica = saveReplicaDao.findByScope("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(replica!!.localHash).isEqualTo("new-checkpoint-hash")
            assertThat(replica.localSizeBytes).isEqualTo(256L)
            assertThat(replica.localWrittenAtEpochMs).isEqualTo(10_000L)
            assertThat(replica.syncStatus).isEqualTo(SaveSyncStatus.UNSYNCED)

            // Operation enqueued with correct metadata.
            val ops = pendingOperationDao.findByStatus(PendingOperationStatus.PENDING)
            assertThat(ops).hasSize(1)
            assertThat(ops[0].operationType).isEqualTo(PendingOperationType.NEGOTIATE_AND_SYNC)
            assertThat(ops[0].negotiateFileName).isEqualTo("test.srm")
            assertThat(ops[0].negotiateCoreId).isEqualTo("sameboy")
            assertThat(ops[0].negotiateCoreBuildRevision).isEqualTo("v1.6")
        }
    }

    @Test
    fun `syncPostPlay invokes foreground drain hook instead of generic queue hook`() {
        runBlocking {
            var genericQueueCount = 0
            var foregroundDrainCount = 0
            val foregroundCoordinator = SaveSyncCoordinatorImpl(
                client,
                sessionStore,
                deviceRepository,
                saveReplicaDao,
                pendingOperationDao,
                saveContentStore,
                clock = { clockValue },
                onOperationQueued = { genericQueueCount++ },
                onPostPlayOperationQueued = { foregroundDrainCount++ },
            )

            val result = foregroundCoordinator.syncPostPlay(
                PostPlayCheckpointRequest(
                    serverKey = "localhost",
                    userKey = "alice",
                    romId = 1L,
                    romHash = "hash-a",
                    slot = "autosave",
                    coreId = "sameboy",
                    coreBuildRevision = "v1.6",
                    fileName = "test.srm",
                    checkpointedHash = "new-checkpoint-hash",
                    checkpointedSizeBytes = 256,
                )
            )

            assertThat(result).isInstanceOf(PostPlayCheckpointResult.Queued::class.java)
            assertThat(foregroundDrainCount).isEqualTo(1)
            assertThat(genericQueueCount).isZero()
        }
    }

    @Test
    fun `syncPostPlay no existing replica creates new and queues operation`() {
        runBlocking {
            // No existing replica.
            val result = coordinator.syncPostPlay(PostPlayCheckpointRequest(
                serverKey = "localhost", userKey = "alice", romId = 1L, romHash = "hash-a",
                slot = "autosave", coreId = "sameboy", coreBuildRevision = "v1.6",
                fileName = "test.srm", checkpointedHash = "first-checkpoint-hash",
                checkpointedSizeBytes = 512,
            ))

            assertThat(result).isInstanceOf(PostPlayCheckpointResult.Queued::class.java)

            // Replica created.
            val replica = saveReplicaDao.findByScope("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(replica).isNotNull
            assertThat(replica!!.localHash).isEqualTo("first-checkpoint-hash")
            assertThat(replica.syncStatus).isEqualTo(SaveSyncStatus.UNSYNCED)
        }
    }

    @Test
    fun `syncPostPlay same unsynced generation re-kicks existing operation`() {
        runBlocking {
            // First call: creates replica and queues operation.
            val result1 = coordinator.syncPostPlay(PostPlayCheckpointRequest(
                serverKey = "localhost", userKey = "alice", romId = 1L, romHash = "hash-a",
                slot = "autosave", coreId = "sameboy", coreBuildRevision = "v1.6",
                fileName = "test.srm", checkpointedHash = "checkpoint-hash",
                checkpointedSizeBytes = 128,
            ))
            assertThat(result1).isInstanceOf(PostPlayCheckpointResult.Queued::class.java)

            // Advance clock slightly but hash is same.
            clockValue = 11_000L

            // Second call: hash matches, but the server has not confirmed it yet.
            val result2 = coordinator.syncPostPlay(PostPlayCheckpointRequest(
                serverKey = "localhost", userKey = "alice", romId = 1L, romHash = "hash-a",
                slot = "autosave", coreId = "sameboy", coreBuildRevision = "v1.6",
                fileName = "test.srm", checkpointedHash = "checkpoint-hash",
                checkpointedSizeBytes = 128,
            ))
            assertThat(result2).isEqualTo(result1)

            // The durable row remains deduplicated, while WorkManager is kicked again.
            val ops = pendingOperationDao.findByStatus(PendingOperationStatus.PENDING)
            assertThat(ops).hasSize(1)
            assertThat(operationQueuedCount).isEqualTo(2)
        }
    }

    @Test
    fun `syncPostPlay new generation after first drops stale queues new operation`() {
        runBlocking {
            // First generation.
            val result1 = coordinator.syncPostPlay(PostPlayCheckpointRequest(
                serverKey = "localhost", userKey = "alice", romId = 1L, romHash = "hash-a",
                slot = "autosave", coreId = "sameboy", coreBuildRevision = "v1.6",
                fileName = "test.srm", checkpointedHash = "gen1-hash",
                checkpointedSizeBytes = 128,
            ))
            assertThat(result1).isInstanceOf(PostPlayCheckpointResult.Queued::class.java)

            // Advance clock for new generation.
            clockValue = 11_000L

            // Second generation: different hash.
            val result2 = coordinator.syncPostPlay(PostPlayCheckpointRequest(
                serverKey = "localhost", userKey = "alice", romId = 1L, romHash = "hash-a",
                slot = "autosave", coreId = "sameboy", coreBuildRevision = "v1.6",
                fileName = "test.srm", checkpointedHash = "gen2-hash",
                checkpointedSizeBytes = 256,
            ))
            assertThat(result2).isInstanceOf(PostPlayCheckpointResult.Queued::class.java)

            // Stale generation dropped, only new one remains.
            val ops = pendingOperationDao.findByStatus(PendingOperationStatus.PENDING)
            assertThat(ops).hasSize(1)
            assertThat(ops[0].localGenerationEpochMs).isEqualTo(11_000L)

            // Replica reflects latest generation.
            val replica = saveReplicaDao.findByScope("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(replica!!.localHash).isEqualTo("gen2-hash")
            assertThat(replica.localWrittenAtEpochMs).isEqualTo(11_000L)
        }
    }

    // ---- resolveConflict: SyncOperation metadata preservation (Defect 1) ----

    @Test
    fun `resolveConflict keep-server validates full server hash and persists authoritative timestamp`() {
        runBlocking {
            val serverHash = "a]b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2"
            val serverTimestamp = java.time.Instant.parse("2026-06-15T12:30:00Z")

            // Seed local replica for scope lookup inside resolveConflict.
            saveReplicaDao.upsert(
                SaveReplicaEntity(
                    serverKey = "localhost", userKey = "alice", romId = 1L, romHash = "hash-a",
                    slot = "autosave", coreId = "sameboy", coreBuildRevision = "v1.0.3-libretro",
                    localHash = "local-hash", localSizeBytes = 3L, localWrittenAtEpochMs = 5_000L,
                    syncStatus = SaveSyncStatus.CONFLICT, lastError = "conflict",
                )
            )
            saveContentStore.seedLocal("localhost", "alice", 1L, "hash-a", "autosave", byteArrayOf(1, 2, 3))

            // Enqueue: download server bytes for keep-server resolution, confirm download, complete session.
            val serverBytes = byteArrayOf(40, 50, 60)
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(serverBytes)))
            server.enqueue(MockResponse().setResponseCode(200)) // confirm download
            enqueueComplete()

            val originalOperation = com.romm.androidtv.romm.SyncOperation(
                action = com.romm.androidtv.romm.SyncAction.CONFLICT,
                romId = 1L,
                saveId = 11L,
                fileName = "autosave.srm",
                slot = "autosave",
                emulator = "sameboy",
                reason = "both changed",
                serverUpdatedAt = serverTimestamp,
                serverContentHash = serverHash,
            )

            // Use a fake conflict resolver that records the operation it receives.
            var receivedOperation: com.romm.androidtv.romm.SyncOperation? = null
            val fakeResolver = object : ConflictResolver {
                override suspend fun resolveKeepLocal(
                    sessionId: Long, serverOrigin: String, username: String,
                    localEntity: SaveReplicaEntity, operation: com.romm.androidtv.romm.SyncOperation,
                    localFileName: String,
                ): ConflictResolutionResult {
                    receivedOperation = operation
                    return ConflictResolutionResult.Success(ConflictChoice.KEEP_LOCAL, null, null, null)
                }
                override suspend fun resolveKeepServer(
                    sessionId: Long, serverOrigin: String, username: String,
                    localEntity: SaveReplicaEntity, operation: com.romm.androidtv.romm.SyncOperation,
                ): ConflictResolutionResult {
                    receivedOperation = operation
                    return ConflictResolutionResult.Success(ConflictChoice.KEEP_SERVER, null, null, null)
                }
            }

            val coordinatorWithFake = SaveSyncCoordinatorImpl(
                client, sessionStore, deviceRepository, saveReplicaDao, pendingOperationDao, saveContentStore,
                clock = { clockValue }, conflictResolver = fakeResolver,
            )

            val result = (coordinatorWithFake as com.romm.androidtv.romm.save.SaveSyncCoordinatorInternal).resolveConflict(
                ResolveConflictRequest(
                    sessionId = 7L,
                    serverOrigin = baseUrl(),
                    username = "alice",
                    romId = 1L,
                    romHash = "hash-a",
                    slot = "autosave",
                    choice = ConflictChoice.KEEP_SERVER,
                    operation = originalOperation,
                    serverSaveId = 11L,
                    fileName = "autosave.srm",
                    serverSlot = "autosave",
                    serverEmulator = "sameboy",
                    reason = "both changed",
                )
            )

            assertThat(result).isInstanceOf(ConflictResolutionResult.Success::class.java)
            // The resolver received the EXACT original operation, not a reconstructed one.
            assertThat(receivedOperation).isNotNull
            assertThat(receivedOperation!!.serverContentHash).isEqualTo(serverHash)
            assertThat(receivedOperation!!.serverUpdatedAt).isEqualTo(serverTimestamp)
        }
    }

    @Test
    fun `resolveConflict mismatched server hash causes resolver to reject without adoption`() {
        runBlocking {
            // Seed local replica.
            saveReplicaDao.upsert(
                SaveReplicaEntity(
                    serverKey = "localhost", userKey = "alice", romId = 1L, romHash = "hash-a",
                    slot = "autosave", coreId = "sameboy", coreBuildRevision = "v1.0.3-libretro",
                    localHash = "local-hash", localSizeBytes = 3L, localWrittenAtEpochMs = 5_000L,
                    syncStatus = SaveSyncStatus.CONFLICT, lastError = "conflict",
                )
            )
            saveContentStore.seedLocal("localhost", "alice", 1L, "hash-a", "autosave", byteArrayOf(1, 2, 3))

            // Operation carries a server hash that does NOT match downloaded bytes.
            val originalOperation = com.romm.androidtv.romm.SyncOperation(
                action = com.romm.androidtv.romm.SyncAction.CONFLICT,
                romId = 1L, saveId = 11L, fileName = "autosave.srm", slot = "autosave",
                emulator = "sameboy", reason = "both changed",
                serverUpdatedAt = java.time.Instant.parse("2026-06-15T12:30:00Z"),
                serverContentHash = "mismatched-hash-value",
            )

            // Use production conflict resolver (which validates hash).
            val coordinatorWithProdResolver = SaveSyncCoordinatorImpl(
                client, sessionStore, deviceRepository, saveReplicaDao, pendingOperationDao, saveContentStore,
                clock = { clockValue },
            )

            // Enqueue: device registration, download server bytes for keep-server resolution.
            enqueueDeviceRegistered()
            val serverBytes = byteArrayOf(40, 50, 60)
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(serverBytes)))

            val result = (coordinatorWithProdResolver as com.romm.androidtv.romm.save.SaveSyncCoordinatorInternal).resolveConflict(
                ResolveConflictRequest(
                    sessionId = 7L,
                    serverOrigin = baseUrl(),
                    username = "alice",
                    romId = 1L,
                    romHash = "hash-a",
                    slot = "autosave",
                    choice = ConflictChoice.KEEP_SERVER,
                    operation = originalOperation,
                    serverSaveId = 11L,
                    fileName = "autosave.srm",
                    serverSlot = "autosave",
                    serverEmulator = "sameboy",
                    reason = "both changed",
                )
            )

            // Hash mismatch: resolver rejects without adopting.
            assertThat(result).isInstanceOf(ConflictResolutionResult.Failure::class.java)
            val failure = result as ConflictResolutionResult.Failure
            assertThat(failure.reason).contains("server-hash-mismatch")
            // Local bytes untouched (no adoption occurred).
            assertThat(saveContentStore.readLocal("localhost", "alice", 1L, "hash-a", "autosave"))
                .isEqualTo(byteArrayOf(1, 2, 3))
        }
    }

    // ---- adoptChosenSave (native save-picker "Choose Save" flow) ----

    private fun adoptRequest(
        romId: Long = 1L,
        romHash: String = "hash-a",
        expectedSramSizeBytes: Long? = 3L,
        chosenSaveId: Long = 99L,
        chosenSaveEmulator: String? = "sameboy",
        chosenSaveContentHash: String? = "hash-picked",
    ) = AdoptSaveRequest(
        romId = romId,
        romHash = romHash,
        coreId = "sameboy",
        coreBuildRevision = "v1.0.3-libretro",
        expectedSramSizeBytes = expectedSramSizeBytes,
        chosenSaveId = chosenSaveId,
        chosenSaveEmulator = chosenSaveEmulator,
        chosenSaveContentHash = chosenSaveContentHash,
    )

    @Test
    fun `adoptChosenSave device registration failure short-circuits before any download`() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(401))

            val outcome = coordinator.adoptChosenSave(adoptRequest())

            assertThat(outcome).isInstanceOf(SaveSyncOutcome.Failure::class.java)
            assertThat((outcome as SaveSyncOutcome.Failure).error).isEqualTo(RommApiError.AUTH_EXPIRED)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun `adoptChosenSave with known matching size downloads and adopts without a negotiate session`() {
        runBlocking {
            enqueueDeviceRegistered()
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
            server.enqueue(MockResponse().setResponseCode(200)) // /downloaded confirm
            enqueueComplete() // best-effort sync/sessions/0/complete

            val outcome = coordinator.adoptChosenSave(adoptRequest(chosenSaveId = 99L))

            assertThat(outcome).isEqualTo(SaveSyncOutcome.Downloaded(0L, 99L, 3, true))
            assertThat(saveContentStore.readLocal("localhost", "alice", 1L, "hash-a", "autosave"))
                .isEqualTo(byteArrayOf(1, 2, 3))
            val replica = saveReplicaDao.findByScope("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(replica!!.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
            assertThat(replica.rommSaveId).isEqualTo(99L)
            assertThat(saveContentStore.quarantinedFiles).isEmpty()
            // device-register, download content, /downloaded confirm, sync/sessions/0/complete — never negotiate.
            assertThat(server.requestCount).isEqualTo(4)
        }
    }

    @Test
    fun `adoptChosenSave with a mismatched emulator still adopts normally (SRAM saves are cross-core compatible)`() {
        runBlocking {
            enqueueDeviceRegistered()
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(byteArrayOf(9, 9, 9))))
            server.enqueue(MockResponse().setResponseCode(200)) // /downloaded confirm
            enqueueComplete() // best-effort sync/sessions/0/complete

            val outcome = coordinator.adoptChosenSave(
                adoptRequest(chosenSaveEmulator = "some-other-core", expectedSramSizeBytes = 3L)
            )

            assertThat(outcome).isEqualTo(SaveSyncOutcome.Downloaded(0L, 99L, 3, true))
            assertThat(saveContentStore.readLocal("localhost", "alice", 1L, "hash-a", "autosave"))
                .isEqualTo(byteArrayOf(9, 9, 9))
            val replica = saveReplicaDao.findByScope("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(replica!!.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
            assertThat(saveContentStore.quarantinedFiles).isEmpty()
        }
    }

    @Test
    fun `adoptChosenSave with wrong size is quarantined and never confirmed`() {
        runBlocking {
            enqueueDeviceRegistered()
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(byteArrayOf(1, 2)))) // 2 bytes, expected 3

            val outcome = coordinator.adoptChosenSave(adoptRequest(expectedSramSizeBytes = 3L))

            assertThat(outcome).isInstanceOf(SaveSyncOutcome.Quarantined::class.java)
            assertThat((outcome as SaveSyncOutcome.Quarantined).reason).isEqualTo("size-mismatch")
            assertThat(server.requestCount).isEqualTo(2)
        }
    }

    @Test
    fun `adoptChosenSave with unknown expected size quarantines pending post-load JNI validation, never confirms`() {
        runBlocking {
            enqueueDeviceRegistered()
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(byteArrayOf(1, 2, 3, 4))))

            val outcome = coordinator.adoptChosenSave(adoptRequest(expectedSramSizeBytes = null, chosenSaveId = 42L))

            assertThat(outcome).isInstanceOf(SaveSyncOutcome.AwaitingCoreValidation::class.java)
            val awaiting = outcome as SaveSyncOutcome.AwaitingCoreValidation
            assertThat(awaiting.rommSaveId).isEqualTo(42L)
            assertThat(awaiting.downloadedSizeBytes).isEqualTo(4L)
            val replica = saveReplicaDao.findByScope("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(replica!!.syncStatus).isEqualTo(SaveSyncStatus.AWAITING_CORE_VALIDATION)
            // device-register + download only — no confirm/complete until post-load JNI validation.
            assertThat(server.requestCount).isEqualTo(2)
        }
    }

}

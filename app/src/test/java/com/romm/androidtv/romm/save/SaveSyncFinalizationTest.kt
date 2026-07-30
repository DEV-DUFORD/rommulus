package com.romm.androidtv.romm.save

import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.config.FakeSharedPreferences
import com.romm.androidtv.romm.DeviceIdentityStore
import com.romm.androidtv.romm.DeviceRepositoryImpl
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SaveSyncFinalizationTest {

    private lateinit var server: MockWebServer
    private lateinit var client: okhttp3.OkHttpClient
    private lateinit var sessionStore: SessionStore
    private lateinit var deviceRepository: DeviceRepositoryImpl
    private lateinit var saveReplicaDao: FakeSaveReplicaDao
    private lateinit var pendingOperationDao: FakePendingOperationDao
    private lateinit var saveContentStore: FakeSaveContentStore
    private lateinit var coordinator: SaveSyncCoordinatorImpl

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

    @Test
    fun `finalizeAdoption confirms download, completes session, and upserts SYNCED replica`() {
        runBlocking {
            enqueueDeviceRegistered()
            // /downloaded confirm
            server.enqueue(MockResponse().setResponseCode(200))
            // /sync/sessions/7/complete
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"session": {"id": 7, "status": "COMPLETED"}}"""))

            val result = coordinator.finalizeAdoption(
                FinalizeAdoptionRequest(
                    sessionId = 7L,
                    rommSaveId = 10L,
                    serverKey = "localhost",
                    userKey = "alice",
                    romId = 1L,
                    romHash = "hash-a",
                    slot = "autosave",
                    coreId = "sameboy",
                    coreBuildRevision = "v1.0.3-libretro",
                    checkpointedHash = "checkpoint-hash-abc",
                    checkpointedSizeBytes = 32768L,
                    serverContentHash = "server-hash",
                )
            )

            assertThat(result).isInstanceOf(FinalizeAdoptionResult.Success::class.java)
            assertThat((result as FinalizeAdoptionResult.Success).confirmed).isTrue()

            val replica = saveReplicaDao.findByScope("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(replica).isNotNull
            assertThat(replica!!.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
            assertThat(replica.localHash).isEqualTo("checkpoint-hash-abc")
            assertThat(replica.localSizeBytes).isEqualTo(32768L)
            assertThat(replica.rommSaveId).isEqualTo(10L)
            assertThat(replica.serverHash).isEqualTo("server-hash")

            // 3 requests: device-register + /downloaded confirm + complete session
            assertThat(server.requestCount).isEqualTo(3)
        }
    }

    @Test
    fun `finalizeAdoption is idempotent — second call re-confirms and upserts identical data`() {
        runBlocking {
            enqueueDeviceRegistered()
            server.enqueue(MockResponse().setResponseCode(200)) // confirm 1
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"session": {"id": 7, "status": "COMPLETED"}}"""))

            val first = coordinator.finalizeAdoption(
                FinalizeAdoptionRequest(
                    sessionId = 7L, rommSaveId = 10L, serverKey = "localhost", userKey = "alice",
                    romId = 1L, romHash = "hash-a", slot = "autosave", coreId = "sameboy",
                    coreBuildRevision = "v1.0.3-libretro", checkpointedHash = "h1", checkpointedSizeBytes = 32768L,
                    serverContentHash = "sh",
                )
            )

            // Second call (idempotent replay).
            enqueueDeviceRegistered()
            server.enqueue(MockResponse().setResponseCode(200)) // confirm 2
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"session": {"id": 7, "status": "COMPLETED"}}"""))

            val second = coordinator.finalizeAdoption(
                FinalizeAdoptionRequest(
                    sessionId = 7L, rommSaveId = 10L, serverKey = "localhost", userKey = "alice",
                    romId = 1L, romHash = "hash-a", slot = "autosave", coreId = "sameboy",
                    coreBuildRevision = "v1.0.3-libretro", checkpointedHash = "h1", checkpointedSizeBytes = 32768L,
                    serverContentHash = "sh",
                )
            )

            assertThat(first).isEqualTo(second)
            val replica = saveReplicaDao.findByScope("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(replica!!.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
        }
    }

    @Test
    fun `finalizeAdoption returns Failure when device registration fails`() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(401))

            val result = coordinator.finalizeAdoption(
                FinalizeAdoptionRequest(
                    sessionId = 7L, rommSaveId = 10L, serverKey = "localhost", userKey = "alice",
                    romId = 1L, romHash = "hash-a", slot = "autosave", coreId = "sameboy",
                    coreBuildRevision = "v1.0.3-libretro", checkpointedHash = "h", checkpointedSizeBytes = 32768L,
                    serverContentHash = null,
                )
            )

            assertThat(result).isInstanceOf(FinalizeAdoptionResult.Failure::class.java)
            assertThat(server.requestCount).isEqualTo(1) // Only device registration attempted.
        }
    }

    @Test
    fun `finalizeAdoption returns Failure when no session is available`() {
        runBlocking {
            sessionStore.clear()

            val result = coordinator.finalizeAdoption(
                FinalizeAdoptionRequest(
                    sessionId = 7L, rommSaveId = 10L, serverKey = "localhost", userKey = "alice",
                    romId = 1L, romHash = "hash-a", slot = "autosave", coreId = "sameboy",
                    coreBuildRevision = "v1.0.3-libretro", checkpointedHash = "h", checkpointedSizeBytes = 32768L,
                    serverContentHash = null,
                )
            )

            assertThat(result).isInstanceOf(FinalizeAdoptionResult.Failure::class.java)
            assertThat(server.requestCount).isEqualTo(0) // No network calls made.
        }
    }

    @Test
    fun `finalizeAdoption upserts new replica when none existed`() {
        runBlocking {
            enqueueDeviceRegistered()
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"session": {"id": 7, "status": "COMPLETED"}}"""))

            // No existing replica — finalizeAdoption creates one.
            val result = coordinator.finalizeAdoption(
                FinalizeAdoptionRequest(
                    sessionId = 7L, rommSaveId = 10L, serverKey = "localhost", userKey = "alice",
                    romId = 1L, romHash = "hash-a", slot = "autosave", coreId = "sameboy",
                    coreBuildRevision = "v1.0.3-libretro", checkpointedHash = "new-hash", checkpointedSizeBytes = 16384L,
                    serverContentHash = null,
                )
            )

            assertThat(result).isInstanceOf(FinalizeAdoptionResult.Success::class.java)
            val replica = saveReplicaDao.findByScope("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(replica).isNotNull
            assertThat(replica!!.localHash).isEqualTo("new-hash")
            assertThat(replica.localSizeBytes).isEqualTo(16384L)
            assertThat(replica.rommSaveId).isEqualTo(10L)
        }
    }

    @Test
    fun `finalizeAdoption marks replica SYNCED even when confirmDownload fails`() {
        runBlocking {
            enqueueDeviceRegistered()
            server.enqueue(MockResponse().setResponseCode(500)) // confirm fails
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"session": {"id": 7, "status": "COMPLETED"}}"""))

            val result = coordinator.finalizeAdoption(
                FinalizeAdoptionRequest(
                    sessionId = 7L, rommSaveId = 10L, serverKey = "localhost", userKey = "alice",
                    romId = 1L, romHash = "hash-a", slot = "autosave", coreId = "sameboy",
                    coreBuildRevision = "v1.0.3-libretro", checkpointedHash = "h", checkpointedSizeBytes = 32768L,
                    serverContentHash = null,
                )
            )

            // confirmed=false but still Success — local replica is persisted honestly.
            assertThat(result).isInstanceOf(FinalizeAdoptionResult.Success::class.java)
            assertThat((result as FinalizeAdoptionResult.Success).confirmed).isFalse()
            val replica = saveReplicaDao.findByScope("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(replica!!.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
        }
    }

    @Test
    fun `finalizeAdoption persists JNI-learned expectedSramSizeBytes`() {
        runBlocking {
            enqueueDeviceRegistered()
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"session": {"id": 7, "status": "COMPLETED"}}"""))

            val result = coordinator.finalizeAdoption(
                FinalizeAdoptionRequest(
                    sessionId = 7L, rommSaveId = 10L, serverKey = "localhost", userKey = "alice",
                    romId = 1L, romHash = "hash-a", slot = "autosave", coreId = "sameboy",
                    coreBuildRevision = "v1.0.3-libretro", checkpointedHash = "checkpoint-xyz",
                    checkpointedSizeBytes = 32768L, serverContentHash = "server-h",
                    expectedSramSizeBytes = 32768L, // JNI-learned size persisted for future sync decisions.
                )
            )

            assertThat(result).isInstanceOf(FinalizeAdoptionResult.Success::class.java)
            val replica = saveReplicaDao.findByScope("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(replica).isNotNull
            assertThat(replica!!.expectedSramSizeBytes).isEqualTo(32768L)
        }
    }

    @Test
    fun `finalizeAdoption preserves existing expectedSramSizeBytes when request omits it`() {
        runBlocking {
            // Seed an existing replica with expectedSramSizeBytes.
            saveReplicaDao.upsert(
                SaveReplicaEntity(
                    serverKey = "localhost", userKey = "alice", romId = 1L, romHash = "hash-a", slot = "autosave",
                    coreId = "sameboy", coreBuildRevision = "v1.0.3-libretro", expectedSramSizeBytes = 16384L,
                )
            )

            enqueueDeviceRegistered()
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"session": {"id": 7, "status": "COMPLETED"}}"""))

            // finalizeAdoption with expectedSramSizeBytes = null (not provided by caller).
            coordinator.finalizeAdoption(
                FinalizeAdoptionRequest(
                    sessionId = 7L, rommSaveId = 10L, serverKey = "localhost", userKey = "alice",
                    romId = 1L, romHash = "hash-a", slot = "autosave", coreId = "sameboy",
                    coreBuildRevision = "v1.0.3-libretro", checkpointedHash = "h", checkpointedSizeBytes = 16384L,
                    serverContentHash = null, expectedSramSizeBytes = null,
                )
            )

            val replica = saveReplicaDao.findByScope("localhost", "alice", 1L, "hash-a", "autosave")
            assertThat(replica).isNotNull
            // Existing value preserved when request omits it.
            assertThat(replica!!.expectedSramSizeBytes).isEqualTo(16384L)
        }
    }
}

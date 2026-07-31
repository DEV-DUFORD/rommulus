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
 * [SaveSyncCoordinatorImpl.recordPlaySession] — reports completed gameplay to
 * `POST /api/play-sessions`, the only server mechanism that advances
 * `rom_user.last_played` (drives the RomM Home screen's "Continue Playing" row).
 * Must never throw for ordinary server-side failures — callers treat this as best-effort.
 */
class RecordPlaySessionTest {

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
    fun `recordPlaySession registers the device then posts the session and returns Success`() {
        runBlocking {
            enqueueDeviceRegistered()
            server.enqueue(
                MockResponse().setResponseCode(201)
                    .setBody("""{"results": [{"index": 0, "status": "created", "id": 1, "detail": null}], "created_count": 1, "skipped_count": 0}""")
            )

            val result = coordinator.recordPlaySession(
                PlaySessionRecordRequest(
                    romId = 42L,
                    slot = "autosave",
                    startEpochMs = 1_000L,
                    endEpochMs = 61_000L,
                )
            )

            assertThat(result).isInstanceOf(PlaySessionRecordResult.Success::class.java)
            assertThat((result as PlaySessionRecordResult.Success).createdCount).isEqualTo(1)
            assertThat(result.skippedCount).isEqualTo(0)
            assertThat(server.requestCount).isEqualTo(2) // device-register + play-sessions
        }
    }

    @Test
    fun `recordPlaySession returns Failure without a network call when no session is available`() {
        runBlocking {
            sessionStore.clear()

            val result = coordinator.recordPlaySession(
                PlaySessionRecordRequest(romId = 1L, slot = "autosave", startEpochMs = 0L, endEpochMs = 1_000L)
            )

            assertThat(result).isInstanceOf(PlaySessionRecordResult.Failure::class.java)
            assertThat((result as PlaySessionRecordResult.Failure).error).isEqualTo(RommApiError.AUTH_EXPIRED)
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun `recordPlaySession returns Failure when device registration fails`() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(401))

            val result = coordinator.recordPlaySession(
                PlaySessionRecordRequest(romId = 1L, slot = "autosave", startEpochMs = 0L, endEpochMs = 1_000L)
            )

            assertThat(result).isInstanceOf(PlaySessionRecordResult.Failure::class.java)
            assertThat(server.requestCount).isEqualTo(1) // Only device registration attempted.
        }
    }

    @Test
    fun `recordPlaySession returns Failure when the server rejects the request`() {
        runBlocking {
            enqueueDeviceRegistered()
            server.enqueue(MockResponse().setResponseCode(500))

            val result = coordinator.recordPlaySession(
                PlaySessionRecordRequest(romId = 1L, slot = "autosave", startEpochMs = 0L, endEpochMs = 1_000L)
            )

            assertThat(result).isInstanceOf(PlaySessionRecordResult.Failure::class.java)
        }
    }

    @Test
    fun `recordPlaySession skips the network entirely when duration is zero or negative`() {
        runBlocking {
            // No requests enqueued: the coordinator must short-circuit before any network call
            // since the backend rejects end_time <= start_time.
            val result = coordinator.recordPlaySession(
                PlaySessionRecordRequest(romId = 1L, slot = "autosave", startEpochMs = 5_000L, endEpochMs = 5_000L)
            )

            assertThat(result).isEqualTo(PlaySessionRecordResult.Success(createdCount = 0, skippedCount = 0))
            assertThat(server.requestCount).isEqualTo(0)
        }
    }
}

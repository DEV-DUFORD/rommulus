package com.romm.desktop.sync

import com.romm.androidtv.emulation.model.SavePathPolicy
import com.romm.androidtv.romm.DeviceIdentity
import com.romm.androidtv.romm.PlaySessionIngestResult
import com.romm.androidtv.romm.RommApiError
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for the desktop [GameLaunchRecorder] (mirror of Android's
 * `com.romm.androidtv.romm.save.GameLaunchRecorder`): a 1ms autosave session ending at the
 * launch instant, reported through the [RommSyncGateway] seam, off the caller's thread, and
 * best-effort (every failure swallowed).
 */
@DisplayName("GameLaunchRecorder")
class GameLaunchRecorderTest {

    private companion object {
        const val ORIGIN = "https://romm.example"
        const val USERNAME = "zack"
        const val FIXED_NOW_MS = 1_700_000_000_000L
    }

    private fun recorder(
        gateway: FakeRommSyncGateway,
        session: SaveSyncSession?,
        identity: DeviceIdentity?,
        executor: Executor = Executor { it.run() },
    ) = GameLaunchRecorder(
        gateway = gateway,
        sessionReader = FakeSaveSyncSessionReader(session),
        deviceIdentityLoader = FakeDeviceIdentityLoader(identity),
        executor = executor,
        clock = { FIXED_NOW_MS },
    )

    @Test
    fun `records a 1ms autosave session ending at the launch instant`() {
        val gateway = FakeRommSyncGateway()
        recorder(
            gateway,
            SaveSyncSession(ORIGIN, USERNAME),
            DeviceIdentity("install-1", "device-1"),
        ).recordLaunch(42L)

        val (origin, request) = gateway.ingestPlaySessionsCalls.single()
        assertThat(origin).isEqualTo(ORIGIN)
        assertThat(request.deviceId).isEqualTo("device-1")
        val session = request.sessions.single()
        assertThat(session.romId).isEqualTo(42L)
        assertThat(session.saveSlot).isEqualTo(SavePathPolicy.AUTOSAVE_SLOT)
        assertThat(session.durationMs).isEqualTo(1L)
        assertThat(session.startTime.toEpochMilli()).isEqualTo(FIXED_NOW_MS - 1L)
        assertThat(session.endTime.toEpochMilli()).isEqualTo(FIXED_NOW_MS)
    }

    @Test
    fun `notifies after the server accepts the play session`() {
        val gateway = FakeRommSyncGateway()
        var recordedRomId: Long? = null
        val recorder = GameLaunchRecorder(
            gateway = gateway,
            sessionReader = FakeSaveSyncSessionReader(SaveSyncSession(ORIGIN, USERNAME)),
            deviceIdentityLoader = FakeDeviceIdentityLoader(DeviceIdentity("install-1", "device-1")),
            executor = Executor { it.run() },
            clock = { FIXED_NOW_MS },
            onRecorded = { recordedRomId = it },
        )

        recorder.recordLaunch(42L)

        assertThat(recordedRomId).isEqualTo(42L)
    }

    @Test
    fun `does not notify when the server rejects the play session`() {
        val gateway = FakeRommSyncGateway().apply {
            ingestPlaySessionsResult = PlaySessionIngestResult.Failure(RommApiError.NETWORK_ERROR)
        }
        var notified = false
        val recorder = GameLaunchRecorder(
            gateway = gateway,
            sessionReader = FakeSaveSyncSessionReader(SaveSyncSession(ORIGIN, USERNAME)),
            deviceIdentityLoader = FakeDeviceIdentityLoader(DeviceIdentity("install-1", "device-1")),
            executor = Executor { it.run() },
            clock = { FIXED_NOW_MS },
            onRecorded = { notified = true },
        )

        recorder.recordLaunch(42L)

        assertThat(notified).isFalse()
    }

    @Test
    fun `skips the report when there is no coherent session (kiosk or anonymous)`() {
        val gateway = FakeRommSyncGateway()
        recorder(gateway, session = null, identity = DeviceIdentity("i", "d")).recordLaunch(1L)
        assertThat(gateway.ingestPlaySessionsCalls).isEmpty()

        // A session with a null username is the anonymous/kiosk shape — also skipped.
        val gateway2 = FakeRommSyncGateway()
        recorder(gateway2, SaveSyncSession(ORIGIN, null), DeviceIdentity("i", "d")).recordLaunch(1L)
        assertThat(gateway2.ingestPlaySessionsCalls).isEmpty()
    }

    @Test
    fun `reports without a device ID when device identity cannot be resolved`() {
        val gateway = FakeRommSyncGateway()
        recorder(gateway, SaveSyncSession(ORIGIN, USERNAME), identity = null).recordLaunch(1L)

        val (_, request) = gateway.ingestPlaySessionsCalls.single()
        assertThat(request.deviceId).isNull()
        assertThat(request.sessions.single().romId).isEqualTo(1L)
    }

    @Test
    fun `gateway failures are swallowed and never propagate`() {
        val failing = FakeRommSyncGateway().apply {
            ingestPlaySessionsResult = PlaySessionIngestResult.Failure(RommApiError.NETWORK_ERROR)
        }
        recorder(failing, SaveSyncSession(ORIGIN, USERNAME), DeviceIdentity("i", "d")).recordLaunch(1L)
        assertThat(failing.ingestPlaySessionsCalls).hasSize(1)

        val throwing = object : FakeRommSyncGateway() {
            override fun ingestPlaySessions(
                origin: String,
                request: com.romm.androidtv.romm.PlaySessionIngestRequest,
            ): PlaySessionIngestResult = throw IllegalStateException("boom")
        }
        // Must not throw, even when the gateway itself blows up.
        recorder(throwing, SaveSyncSession(ORIGIN, USERNAME), DeviceIdentity("i", "d")).recordLaunch(1L)
    }

    @Test
    fun `defers the report to the executor so the caller never blocks on the network`() {
        val gateway = FakeRommSyncGateway()
        val deferred = AtomicReference<Runnable>()
        val neverRuns = Executor { deferred.set(it) }
        val recorder = recorder(gateway, SaveSyncSession(ORIGIN, USERNAME), DeviceIdentity("i", "d"), neverRuns)

        recorder.recordLaunch(9L)

        // recordLaunch returned without the report having run — the blocking HTTP call is
        // deferred to the background executor.
        assertThat(gateway.ingestPlaySessionsCalls).isEmpty()
        deferred.get()?.run()
        assertThat(gateway.ingestPlaySessionsCalls).hasSize(1)
    }
}

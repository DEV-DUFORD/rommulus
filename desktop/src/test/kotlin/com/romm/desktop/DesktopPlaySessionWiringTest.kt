package com.romm.desktop

import com.romm.androidtv.emulation.model.SavePathPolicy
import com.romm.androidtv.library.RomDetail
import com.romm.androidtv.romm.DeviceIdentity
import com.romm.androidtv.storage.AppPaths
import com.romm.androidtv.storage.TestAppPaths
import com.romm.androidtv.storage.fakes.InMemorySaveStateStore
import com.romm.desktop.player.FakePlayerProcessLauncher
import com.romm.desktop.player.FakeRomContentStager
import com.romm.desktop.player.JournalState
import com.romm.desktop.player.LaunchJournalSupervisor
import com.romm.desktop.player.LaunchOutcome
import com.romm.desktop.storage.secret.FakeSecretBackend
import com.romm.desktop.sync.FakeDeviceIdentityLoader
import com.romm.desktop.sync.FakeRommSyncGateway
import com.romm.desktop.sync.FakeSaveSyncSessionReader
import com.romm.desktop.sync.FileSaveContentGateway
import com.romm.desktop.sync.GameLaunchRecorder
import com.romm.desktop.sync.SaveSyncDrainExecutor
import com.romm.desktop.sync.SaveSyncSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Coordinator-level tests for play-session recording (Android parity — `GameLaunchRecorder`):
 * [DesktopAppCoordinator.launchPlayer] must report a one-second play session for the launched ROM as
 * soon as the session STARTS (not on exit), through the [com.romm.desktop.sync.RommSyncGateway]
 * seam, off the launch thread, without breaking the launch flow.
 *
 * The recorder is injected via the [DesktopAppCoordinator.gameLaunchRecorderOverride] seam over
 * a [FakeRommSyncGateway] (no network); the direct-executor keeps the assertion synchronous.
 */
@DisplayName("DesktopAppCoordinator — play-session recording")
class DesktopPlaySessionWiringTest {

    private companion object {
        const val ORIGIN = "https://demo.romm.app"
        const val USERNAME = "zack"
        const val ROM_ID = 7L
        const val FILE_NAME = "zelda.gb"
    }

    private data class Wired(
        val coordinator: DesktopAppCoordinator,
        val playerProcess: Process?,
        val supervisor: LaunchJournalSupervisor,
    ) {
        fun close() {
            val p = playerProcess ?: return
            runCatching { p.destroy() }
            runCatching { p.waitFor(2, TimeUnit.SECONDS) }
        }
    }

    private fun testRom(): RomDetail = RomDetail(
        id = ROM_ID,
        title = "Test Game",
        platformDisplayName = "Nintendo Game Boy",
        platformSlug = "gb",
        summary = null,
        coverUrl = null,
        screenshotUrls = emptyList(),
        genres = emptyList(),
        companies = emptyList(),
        gameModes = emptyList(),
        playerCount = null,
        firstReleaseDateEpochMillis = null,
        averageRating = null,
        regions = emptyList(),
        languages = emptyList(),
        fileSizeBytes = 1234L,
        lastPlayedIso = null,
        nowPlaying = false,
        fileName = FILE_NAME,
    )

    /** Installs `libgambatte.so` so the approved gb core resolves. */
    private fun installGambatte(paths: AppPaths) {
        val coresDir = paths.dataDir.resolve("cores")
        Files.createDirectories(coresDir)
        Files.write(coresDir.resolve("libgambatte.so"), byteArrayOf(0))
    }

    /**
     * Coordinator wired for play-session tests: fake launcher (long-lived external pid so the
     * exit watcher blocks and no exit/reconciliation happens during the test), fake stager, and
     * the recorder under test over [gateway]. [session] is what the recorder's session reader
     * sees (null == kiosk/anonymous); [detail] nulls the ROM detail to force a failed launch.
     */
    private fun wire(
        paths: AppPaths,
        gateway: FakeRommSyncGateway,
        session: SaveSyncSession?,
        detail: ((Long) -> RomDetail?)? = { testRom() },
    ): Wired {
        val playerProcess = try {
            ProcessBuilder("sleep", "60").start()
        } catch (e: Exception) {
            null // no sleep binary — the watcher reconciles immediately (still correct, racy)
        }
        val pid = playerProcess?.pid() ?: -1L
        val supervisor = LaunchJournalSupervisor(
            journalsRoot = paths.stateDir.resolve("journals"),
            launcher = FakePlayerProcessLauncher(
                outcomeFor = { LaunchOutcome.Started(pid = pid) },
            ),
        )
        val store = InMemorySaveStateStore()
        val c = DesktopAppCoordinator(
            paths = paths,
            secretBackend = FakeSecretBackend(),
            appVersion = "test",
            buildDefaultOrigin = ORIGIN,
            playerSupervisorOverride = supervisor,
            romDetailLookup = detail,
            romContentStagerOverride = FakeRomContentStager(),
            saveStateStoreOverride = store,
            saveSyncDrainExecutorOverride = SaveSyncDrainExecutor(
                pendingOperations = store,
                saveReplicas = store,
                content = FileSaveContentGateway(paths.dataDir.toFile()),
                sessionReader = FakeSaveSyncSessionReader(session),
                deviceIdentityLoader = FakeDeviceIdentityLoader(DeviceIdentity("install-1", "device-1")),
                sync = gateway,
            ),
            gameLaunchRecorderOverride = GameLaunchRecorder(
                gateway = gateway,
                sessionReader = FakeSaveSyncSessionReader(session),
                deviceIdentityLoader = FakeDeviceIdentityLoader(DeviceIdentity("install-1", "device-1")),
                executor = Executor { it.run() },
            ),
        )
        return Wired(c, playerProcess, supervisor)
    }

    /**
     * Blocks until the exit watcher has reconciled [sessionId] (INTERRUPTED — no result file).
     * Without this, the daemon watcher's journal writes race JUnit's @TempDir cleanup and the
     * test fails with IOException during temp-dir deletion.
     */
    private fun waitForReconciled(supervisor: LaunchJournalSupervisor, sessionId: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (supervisor.store.read(sessionId).getOrNull()?.state != JournalState.INTERRUPTED) {
            check(System.currentTimeMillis() < deadline) { "exit watcher did not reconcile $sessionId within 5s" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `launchPlayer records a play session for the ROM at launch start, before any exit`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        installGambatte(paths)
        val gateway = FakeRommSyncGateway()
        val wired = wire(paths, gateway, SaveSyncSession(ORIGIN, USERNAME))
        val launchedAt = System.currentTimeMillis()
        val started = wired.coordinator.launchPlayer(romId = ROM_ID)
        try {
            assertThat(started).isInstanceOf(PlayerLaunchResult.Started::class.java)

            // The report is already in flight (direct executor) — and it happened at launch
            // START: no exit has been driven, yet the gateway saw exactly one session.
            val (origin, request) = gateway.ingestPlaySessionsCalls.single()
            assertThat(origin).isEqualTo(ORIGIN)
            assertThat(request.deviceId).isEqualTo("device-1")
            val session = request.sessions.single()
            assertThat(session.romId).isEqualTo(ROM_ID)
            assertThat(session.saveSlot).isEqualTo(SavePathPolicy.AUTOSAVE_SLOT)
            assertThat(session.durationMs).isEqualTo(1_000L)
            assertThat(session.endTime.toEpochMilli() - session.startTime.toEpochMilli()).isEqualTo(1_000L)
            // The one-second window ends at the launch instant (wall clock, small tolerance).
            assertThat(session.endTime.toEpochMilli()).isBetween(launchedAt, System.currentTimeMillis() + 5_000)
        } finally {
            wired.close()
            (started as? PlayerLaunchResult.Started)?.let { waitForReconciled(wired.supervisor, it.sessionId) }
        }
    }

    @Test
    fun `a failed launch records no play session`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        installGambatte(paths)
        val gateway = FakeRommSyncGateway()
        val wired = wire(paths, gateway, SaveSyncSession(ORIGIN, USERNAME), detail = { null })
        try {
            val result = wired.coordinator.launchPlayer(romId = ROM_ID)

            assertThat(result).isInstanceOf(PlayerLaunchResult.Failed::class.java)
            assertThat(gateway.ingestPlaySessionsCalls).isEmpty()
        } finally {
            wired.close()
        }
    }

    @Test
    fun `a kiosk (no coherent session) launch records no play session`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        installGambatte(paths)
        val gateway = FakeRommSyncGateway()
        val wired = wire(paths, gateway, session = null)
        val started = wired.coordinator.launchPlayer(romId = ROM_ID)
        try {
            // The launch itself still succeeds (anonymous saves path) — only the telemetry is
            // skipped, mirroring Android's kiosk guard.
            assertThat(started).isInstanceOf(PlayerLaunchResult.Started::class.java)
            assertThat(gateway.ingestPlaySessionsCalls).isEmpty()
        } finally {
            wired.close()
            (started as? PlayerLaunchResult.Started)?.let { waitForReconciled(wired.supervisor, it.sessionId) }
        }
    }

    private fun Path.testRoot(): AppPaths = TestAppPaths(this)
}

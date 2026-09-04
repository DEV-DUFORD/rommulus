package com.romm.desktop

import com.romm.androidtv.emulation.model.SavePathPolicy
import com.romm.androidtv.emulation.model.sha256Hex
import com.romm.androidtv.library.RomDetail
import com.romm.androidtv.romm.DeviceIdentity
import com.romm.androidtv.romm.SaveDownloadResult
import com.romm.androidtv.romm.SyncAction
import com.romm.androidtv.romm.SyncNegotiateInfo
import com.romm.androidtv.romm.SyncNegotiateResult
import com.romm.androidtv.romm.SyncOperation
import com.romm.androidtv.storage.AppPaths
import com.romm.androidtv.storage.TestAppPaths
import com.romm.androidtv.storage.fakes.InMemorySaveStateStore
import com.romm.androidtv.storage.ports.SaveReplicaScope
import com.romm.androidtv.storage.ports.SettingsKeys
import com.romm.androidtv.storage.records.SaveReplicaRecord
import com.romm.androidtv.storage.records.SaveSyncStatus
import com.romm.desktop.player.FakePlayerProcessLauncher
import com.romm.desktop.player.FakeRomContentStager
import com.romm.desktop.player.JournalState
import com.romm.desktop.player.LaunchJournalSupervisor
import com.romm.desktop.storage.secret.FakeSecretBackend
import com.romm.desktop.sync.FakeDeviceIdentityLoader
import com.romm.desktop.sync.FakeRommSyncGateway
import com.romm.desktop.sync.FakeSaveSyncSessionReader
import com.romm.desktop.sync.FileSaveContentGateway
import com.romm.desktop.sync.GameLaunchRecorder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@DisplayName("DesktopAppCoordinator — pre-launch save sync")
class DesktopPreLaunchSaveSyncWiringTest {

    private companion object {
        const val ORIGIN = "https://demo.romm.app"
        const val USERNAME = "zack"
        const val ROM_ID = 7L
        const val SESSION_ID = 55L
        const val SAVE_ID = 99L
        val LOCAL_BYTES = "local-save".toByteArray()
        val SERVER_BYTES = "cloud-save".toByteArray()
        val ROM_HASH = sha256Hex("fake-rom-content".toByteArray())
        val SERVER_KEY = SavePathPolicy.sanitizeSegment(ORIGIN)
        val SLOT = SavePathPolicy.AUTOSAVE_SLOT
    }

    private data class Wired(
        val coordinator: DesktopAppCoordinator,
        val store: InMemorySaveStateStore,
        val sync: FakeRommSyncGateway,
        val launcher: FakePlayerProcessLauncher,
        val supervisor: LaunchJournalSupervisor,
        val paths: AppPaths,
    )

    private fun testRom() = RomDetail(
        id = ROM_ID,
        title = "Test Game",
        platformDisplayName = "Game Boy",
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
        fileName = "zelda.gb",
    )

    private fun wire(dir: Path): Wired {
        val paths = TestAppPaths(dir)
        val coresDir = paths.dataDir.resolve("cores")
        Files.createDirectories(coresDir)
        Files.write(coresDir.resolve("libgambatte.so"), byteArrayOf(0))
        val store = InMemorySaveStateStore()
        val sync = FakeRommSyncGateway()
        val identityLoader = FakeDeviceIdentityLoader(DeviceIdentity("install-1", "device-1"))
        val launcher = FakePlayerProcessLauncher()
        val supervisor = LaunchJournalSupervisor(paths.stateDir.resolve("journals"), launcher)
        val coordinator = DesktopAppCoordinator(
            paths = paths,
            secretBackend = FakeSecretBackend(),
            appVersion = "test",
            buildDefaultOrigin = ORIGIN,
            playerSupervisorOverride = supervisor,
            romDetailLookup = { testRom() },
            romContentStagerOverride = FakeRomContentStager(),
            saveStateStoreOverride = store,
            syncGatewayOverride = sync,
            saveSyncDeviceIdentityLoaderOverride = identityLoader,
            gameLaunchRecorderOverride = GameLaunchRecorder(
                gateway = sync,
                sessionReader = FakeSaveSyncSessionReader(null),
                deviceIdentityLoader = identityLoader,
            ),
        )
        coordinator.settingsStore.write(mapOf(SettingsKeys.ORIGIN to ORIGIN))
        check(coordinator.sessionStorage.save(ORIGIN, USERNAME, 123L, kioskMode = false))
        return Wired(coordinator, store, sync, launcher, supervisor, paths)
    }

    private fun operation(action: SyncAction, reason: String = "") = SyncOperation(
        action = action,
        romId = ROM_ID,
        saveId = SAVE_ID,
        fileName = "autosave.srm",
        slot = SLOT,
        emulator = "gambatte",
        reason = reason,
        serverUpdatedAt = null,
        serverContentHash = sha256Hex(SERVER_BYTES),
    )

    private fun seedLocal(wired: Wired) {
        val scope = SaveReplicaScope(SERVER_KEY, USERNAME, ROM_ID, ROM_HASH, SLOT)
        wired.store.upsert(
            SaveReplicaRecord(
                serverKey = scope.serverKey,
                userKey = scope.userKey,
                romId = scope.romId,
                romHash = scope.romHash,
                slot = scope.slot,
                coreId = "gambatte",
                coreBuildRevision = "old-revision",
                expectedSramSizeBytes = LOCAL_BYTES.size.toLong(),
                localHash = sha256Hex(LOCAL_BYTES),
                localSizeBytes = LOCAL_BYTES.size.toLong(),
                localWrittenAtEpochMs = 1_700_000_000_000L,
                syncStatus = SaveSyncStatus.SYNCED,
            ),
        ).getOrThrow()
        FileSaveContentGateway(wired.paths.dataDir.toFile()).writeLocalAtomically(
            scope.serverKey,
            scope.userKey,
            scope.romId,
            scope.romHash,
            scope.slot,
            LOCAL_BYTES,
        )
    }

    private fun waitForReconciled(wired: Wired, sessionId: String) {
        val deadline = System.currentTimeMillis() + 30_000
        while (wired.supervisor.store.read(sessionId).getOrNull()?.state != JournalState.INTERRUPTED) {
            check(System.currentTimeMillis() < deadline) { "exit watcher did not reconcile $sessionId" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `launchPlayer adopts the negotiated latest server save before spawning`(@TempDir dir: Path) {
        val wired = wire(dir)
        seedLocal(wired)
        wired.sync.negotiateResult = SyncNegotiateResult.Success(
            SyncNegotiateInfo(SESSION_ID, listOf(operation(SyncAction.DOWNLOAD))),
        )
        wired.sync.downloadResult = SaveDownloadResult.Success(SERVER_BYTES)

        val result = wired.coordinator.launchPlayer(ROM_ID)

        val started = result as PlayerLaunchResult.Started
        val request = wired.launcher.launches.single()
        assertThat(Files.readAllBytes(Path.of(request.savePath))).containsExactly(*SERVER_BYTES)
        assertThat(request.expectedSaveSize).isEqualTo(SERVER_BYTES.size.toLong())
        assertThat(wired.sync.negotiateCalls).hasSize(1)
        assertThat(wired.sync.downloadCalls).hasSize(1)
        assertThat(wired.sync.confirmCalls).hasSize(1)
        waitForReconciled(wired, started.sessionId)
    }

    @Test
    fun `launchPlayer lets the core validate a first-launch server save with unknown SRAM size`(@TempDir dir: Path) {
        val wired = wire(dir)
        wired.sync.negotiateResult = SyncNegotiateResult.Success(
            SyncNegotiateInfo(SESSION_ID, listOf(operation(SyncAction.DOWNLOAD))),
        )
        wired.sync.downloadResult = SaveDownloadResult.Success(SERVER_BYTES)

        val result = wired.coordinator.launchPlayer(ROM_ID)

        val started = result as PlayerLaunchResult.Started
        val request = wired.launcher.launches.single()
        assertThat(Files.readAllBytes(Path.of(request.savePath))).containsExactly(*SERVER_BYTES)
        assertThat(request.expectedSaveSize).isNull()
        assertThat(wired.store.findByScope(
            SaveReplicaScope(SERVER_KEY, USERNAME, ROM_ID, ROM_HASH, SLOT),
        )?.syncStatus).isEqualTo(SaveSyncStatus.AWAITING_CORE_VALIDATION)
        waitForReconciled(wired, started.sessionId)
    }

    @Test
    fun `launchPlayer stops on conflict and exposes the existing desktop resolution state`(@TempDir dir: Path) {
        val wired = wire(dir)
        seedLocal(wired)
        wired.sync.negotiateResult = SyncNegotiateResult.Success(
            SyncNegotiateInfo(SESSION_ID, listOf(operation(SyncAction.CONFLICT, "both changed"))),
        )

        val result = wired.coordinator.launchPlayer(ROM_ID)

        assertThat(result).isEqualTo(
            PlayerLaunchResult.Failed("Save conflict needs resolution before launch."),
        )
        assertThat(wired.launcher.launches).isEmpty()
        assertThat(Files.readAllBytes(
            Path.of(
                SavePathPolicy.autosaveSramPath(
                    wired.paths.dataDir.toFile(),
                    SERVER_KEY,
                    USERNAME,
                    ROM_ID,
                    ROM_HASH,
                ),
            ),
        )).containsExactly(*LOCAL_BYTES)
        assertThat(wired.coordinator.saveSyncStatusPresenter().uiState.value)
            .isEqualTo(
                com.romm.desktop.ui.screens.detail.SaveSyncUiState.Replica(
                    SaveSyncStatus.CONFLICT,
                    "both changed",
                ),
            )
    }
}

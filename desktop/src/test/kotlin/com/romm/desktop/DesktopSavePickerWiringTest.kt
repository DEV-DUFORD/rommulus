package com.romm.desktop

import com.romm.androidtv.emulation.model.SavePathPolicy
import com.romm.androidtv.emulation.model.sha256Hex
import com.romm.androidtv.library.RomDetail
import com.romm.androidtv.romm.DeviceIdentity
import com.romm.androidtv.romm.RommApiError
import com.romm.androidtv.romm.SaveDownloadResult
import com.romm.androidtv.romm.SaveListResult
import com.romm.androidtv.romm.ServerSaveInfo
import com.romm.androidtv.romm.save.SaveSyncOutcome
import com.romm.androidtv.storage.AppPaths
import com.romm.androidtv.storage.TestAppPaths
import com.romm.androidtv.storage.fakes.InMemorySaveStateStore
import com.romm.androidtv.storage.ports.SettingsKeys
import com.romm.desktop.player.FakePlayerProcessLauncher
import com.romm.desktop.player.FakeRomContentStager
import com.romm.desktop.player.JournalState
import com.romm.desktop.player.LaunchJournalSupervisor
import com.romm.desktop.storage.secret.FakeSecretBackend
import com.romm.desktop.sync.FakeDeviceIdentityLoader
import com.romm.desktop.sync.FakeRommSyncGateway
import com.romm.desktop.sync.PreLaunchSaveSynchronizer
import com.romm.desktop.ui.screens.detail.SavePickerEntryUiModel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Coordinator-level tests for the "Choose Save" flow (Android `adoptChosenSave` parity):
 * [DesktopAppCoordinator.listSavesForRom] routes through the injected [RommSyncGateway] seam, and
 * [DesktopAppCoordinator.chooseSaveForLaunch] re-scopes the NEXT [DesktopAppCoordinator.launchPlayer]
 * so the chosen server save is downloaded and adopted at the launch's autosave path (the identity
 * of the picked save flows into [com.romm.desktop.player.PlayerLaunchParams.savePath]'s content).
 *
 * The coordinator runs over an in-memory [InMemorySaveStateStore] with a fake-seamed sync gateway,
 * device-identity loader, stager and player launcher — no network, no real process.
 */
@DisplayName("DesktopAppCoordinator — save picker (Choose Save)")
class DesktopSavePickerWiringTest {

    private companion object {
        const val ORIGIN = "https://demo.romm.app"
        const val USERNAME = "zack"
        const val ROM_ID = 7L
        const val FILE_NAME = "zelda.gb"
        const val CHosen_SAVE_ID = 42L
        const val CHOSEN_ROMM_CONTENT_HASH = "6d3a0aaf65e2d1cf8b904f030edbeb50"

        val CHOSEN_BYTES = "chosen-save-bytes".toByteArray()
        /** FakeRomContentStager's deterministic content → the staged SHA-256 scoping the save path. */
        val STAGED_SHA = sha256Hex("fake-rom-content".toByteArray())
    }

    private data class Wired(
        val coordinator: DesktopAppCoordinator,
        val sync: FakeRommSyncGateway,
        val launcher: FakePlayerProcessLauncher,
        val stager: FakeRomContentStager,
        val supervisor: LaunchJournalSupervisor,
        val paths: AppPaths,
    )

    private fun testRom(): RomDetail = RomDetail(
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
        fileName = FILE_NAME,
    )

    /** Coordinator wired with fake-seamed sync gateway + identity loader + launch seams (no network). */
    private fun wire(dir: Path, defaultOrigin: String = ORIGIN): Wired {
        val paths = TestAppPaths(dir)
        // Install libgambatte.so so the approved gb core resolves to a real-content core.
        val coresDir = paths.dataDir.resolve("cores")
        Files.createDirectories(coresDir)
        Files.write(coresDir.resolve("libgambatte.so"), byteArrayOf(0))

        val sync = FakeRommSyncGateway()
        val identityLoader = FakeDeviceIdentityLoader(DeviceIdentity("install-1", "device-1"))
        val launcher = FakePlayerProcessLauncher()
        val supervisor = LaunchJournalSupervisor(
            journalsRoot = paths.stateDir.resolve("journals"),
            launcher = launcher,
        )
        val stager = FakeRomContentStager()

        val c = DesktopAppCoordinator(
            paths = paths,
            secretBackend = FakeSecretBackend(),
            appVersion = "test",
            buildDefaultOrigin = defaultOrigin,
            saveStateStoreOverride = InMemorySaveStateStore(),
            playerSupervisorOverride = supervisor,
            romDetailLookup = { testRom() },
            romContentStagerOverride = stager,
            syncGatewayOverride = sync,
            saveSyncDeviceIdentityLoaderOverride = identityLoader,
            preLaunchSaveSynchronizerOverride =
                PreLaunchSaveSynchronizer { SaveSyncOutcome.NoOpSynced(0L) },
        )
        return Wired(c, sync, launcher, stager, supervisor, paths)
    }

    /** Gives the coordinator a coherent (non-kiosk) session for [ORIGIN] + [USERNAME]. */
    private fun signIn(c: DesktopAppCoordinator, kioskMode: Boolean = false) {
        c.settingsStore.write(mapOf(SettingsKeys.ORIGIN to ORIGIN))
        check(c.sessionStorage.save(ORIGIN, USERNAME, 123L, kioskMode = kioskMode))
    }

    /** The canonical autosave path launchPlayer derives for this ROM (SavePathPolicy layout). */
    private fun expectedSavePath(paths: AppPaths): Path = Path.of(
        SavePathPolicy.autosaveSramPath(
            filesDir = paths.dataDir.toFile(),
            serverKey = ORIGIN,
            userKey = USERNAME,
            romId = ROM_ID,
            romHash = STAGED_SHA,
        ),
    )

    private fun chosenEntry(contentHash: String? = CHOSEN_ROMM_CONTENT_HASH) = SavePickerEntryUiModel(
        saveId = CHosen_SAVE_ID,
        fileName = "autosave [2026-07-31_00-55-06].srm",
        coreId = "gambatte",
        sizeText = "17 B",
        updatedAtText = "just now",
        isDefaultSelection = true,
        contentHash = contentHash,
    )

    /** Blocks until the exit watcher has reconciled [sessionId] (INTERRUPTED — no result file). */
    private fun waitForReconciled(supervisor: LaunchJournalSupervisor, sessionId: String) {
        val deadline = System.currentTimeMillis() + 30_000
        while (supervisor.store.read(sessionId).getOrNull()?.state != JournalState.INTERRUPTED) {
            check(System.currentTimeMillis() < deadline) { "exit watcher did not reconcile $sessionId within 30s" }
            Thread.sleep(10)
        }
    }

    private fun serverSave(saveId: Long, fileName: String = "autosave.srm") = ServerSaveInfo(
        saveId = saveId,
        romId = ROM_ID,
        fileName = fileName,
        slot = SavePathPolicy.AUTOSAVE_SLOT,
        emulator = "gambatte",
        contentHash = "hash-$saveId",
        updatedAt = null,
        fileSizeBytes = 12_345L,
    )

    // ── listSavesForRom ───────────────────────────────────────────────────────────

    @Test
    fun `listSavesForRom routes through the sync gateway with the device id`(@TempDir dir: Path) {
        val wired = wire(dir)
        signIn(wired.coordinator)
        wired.sync.listSavesResult = SaveListResult.Success(listOf(serverSave(1L), serverSave(2L)))

        val result = wired.coordinator.listSavesForRom(ROM_ID)

        assertThat(result).isInstanceOf(SaveListResult.Success::class.java)
        assertThat((result as SaveListResult.Success).saves.map { it.saveId }).containsExactly(1L, 2L)
        // The gateway was called for the right origin + ROM with this device's registered id.
        assertThat(wired.sync.listSavesCalls).containsExactly(ORIGIN to ROM_ID)
    }

    @Test
    fun `listSavesForRom fails without a signed-in session and kiosk exposes no saves`(@TempDir dir: Path) {
        // No origin configured at all (blank compiled-in default too).
        val noOrigin = wire(dir.resolve("no-origin"), defaultOrigin = "")
        assertThat((noOrigin.coordinator.listSavesForRom(ROM_ID) as SaveListResult.Failure).error)
            .isEqualTo(RommApiError.ORIGIN_NOT_CONFIGURED)
        assertThat(noOrigin.sync.listSavesCalls).isEmpty()

        // Origin set but no coherent session record.
        val noSession = wire(dir.resolve("no-session"))
        noSession.coordinator.settingsStore.write(mapOf(SettingsKeys.ORIGIN to ORIGIN))
        assertThat((noSession.coordinator.listSavesForRom(ROM_ID) as SaveListResult.Failure).error)
            .isEqualTo(RommApiError.AUTH_EXPIRED)
        assertThat(noSession.sync.listSavesCalls).isEmpty()

        // Kiosk sessions expose no server saves (Android parity) — empty success, no gateway call.
        val kiosk = wire(dir.resolve("kiosk"))
        signIn(kiosk.coordinator, kioskMode = true)
        assertThat((kiosk.coordinator.listSavesForRom(ROM_ID) as SaveListResult.Success).saves).isEmpty()
        assertThat(kiosk.sync.listSavesCalls).isEmpty()
    }

    // ── selection → launch (adoptChosenSave parity) ───────────────────────────────

    @Test
    fun `choosing a save makes the next launch adopt it at the autosave path`(@TempDir dir: Path) {
        val wired = wire(dir)
        signIn(wired.coordinator)
        wired.sync.downloadResult = SaveDownloadResult.Success(CHOSEN_BYTES)

        // The user picks an entry in the save picker.
        wired.coordinator.chooseSaveForLaunch(ROM_ID, chosenEntry())
        assertThat(wired.coordinator.chosenSaveForLaunch(ROM_ID)?.saveId).isEqualTo(CHosen_SAVE_ID)

        val started = wired.coordinator.launchPlayer(ROM_ID) as PlayerLaunchResult.Started // cast asserts Started

        // The launch's savePath is the canonical autosave path — and it now carries the CHOSEN
        // save's bytes (the picked save's identity flows into the launch's savePath content).
        val request = wired.launcher.launches.single()
        val expected = expectedSavePath(wired.paths)
        assertThat(Path.of(request.savePath)).isEqualTo(expected)
        assertThat(Files.readAllBytes(expected)).containsExactly(*CHOSEN_BYTES)

        // The chosen save was downloaded (by its server id, no negotiate session) and confirmed.
        val download = wired.sync.downloadCalls.single()
        assertThat(download.saveId).isEqualTo(CHosen_SAVE_ID)
        assertThat(download.deviceId).isEqualTo("device-1")
        assertThat(download.sessionId).isNull()
        assertThat(wired.sync.confirmCalls).hasSize(1)

        // One-shot: the selection was consumed by this launch.
        assertThat(wired.coordinator.chosenSaveForLaunch(ROM_ID)).isNull()
        waitForReconciled(wired.supervisor, started.sessionId)
    }

    @Test
    fun `launch without a chosen save never downloads and leaves no autosave file`(@TempDir dir: Path) {
        val wired = wire(dir)
        signIn(wired.coordinator)

        val started = wired.coordinator.launchPlayer(ROM_ID) as PlayerLaunchResult.Started // cast asserts Started

        val expected = expectedSavePath(wired.paths)
        assertThat(Path.of(wired.launcher.launches.single().savePath)).isEqualTo(expected)
        assertThat(Files.exists(expected)).isFalse() // the autosave file is only created on first play
        assertThat(wired.sync.downloadCalls).isEmpty()
        waitForReconciled(wired.supervisor, started.sessionId)
    }

    @Test
    fun `the chosen save is one-shot - a second launch does not re-download`(@TempDir dir: Path) {
        val wired = wire(dir)
        signIn(wired.coordinator)
        wired.sync.downloadResult = SaveDownloadResult.Success(CHOSEN_BYTES)
        wired.coordinator.chooseSaveForLaunch(ROM_ID, chosenEntry())

        val first = wired.coordinator.launchPlayer(ROM_ID) as PlayerLaunchResult.Started // cast asserts Started
        waitForReconciled(wired.supervisor, first.sessionId)

        // A plain Play: no selection pending — the adopted bytes stay in place, no re-download.
        val second = wired.coordinator.launchPlayer(ROM_ID) as PlayerLaunchResult.Started // cast asserts Started
        assertThat(wired.sync.downloadCalls).hasSize(1)
        assertThat(Files.readAllBytes(expectedSavePath(wired.paths))).containsExactly(*CHOSEN_BYTES)
        waitForReconciled(wired.supervisor, second.sessionId)
    }

    @Test
    fun `a chosen save whose hash mismatches fails the launch before spawning`(@TempDir dir: Path) {
        val wired = wire(dir)
        signIn(wired.coordinator)
        wired.sync.downloadResult = SaveDownloadResult.Success(CHOSEN_BYTES)
        // The listing reported a valid RomM fingerprint the downloaded bytes do not match.
        wired.coordinator.chooseSaveForLaunch(ROM_ID, chosenEntry(contentHash = "0".repeat(32)))

        val result = wired.coordinator.launchPlayer(ROM_ID)

        assertThat(result).isInstanceOf(PlayerLaunchResult.Failed::class.java)
        assertThat((result as PlayerLaunchResult.Failed).reason).contains("verification")
        assertThat(wired.launcher.launches).isEmpty()
        assertThat(Files.exists(expectedSavePath(wired.paths))).isFalse()
    }

    @Test
    fun `a failed chosen-save download fails the launch before spawning`(@TempDir dir: Path) {
        val wired = wire(dir)
        signIn(wired.coordinator)
        wired.sync.downloadResult = SaveDownloadResult.Failure(RommApiError.NETWORK_ERROR)
        wired.coordinator.chooseSaveForLaunch(ROM_ID, chosenEntry())

        val result = wired.coordinator.launchPlayer(ROM_ID)

        assertThat(result).isInstanceOf(PlayerLaunchResult.Failed::class.java)
        assertThat((result as PlayerLaunchResult.Failed).reason).contains("could not download")
        assertThat(wired.launcher.launches).isEmpty()
        assertThat(Files.exists(expectedSavePath(wired.paths))).isFalse()
    }
}

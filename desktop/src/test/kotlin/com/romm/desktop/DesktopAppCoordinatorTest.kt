package com.romm.desktop

import com.romm.androidtv.auth.SessionStorage
import com.romm.androidtv.library.RomDetail
import com.romm.androidtv.onboarding.OnboardingRoutingDecision.AppMode
import com.romm.androidtv.emulation.model.sha256Hex
import com.romm.androidtv.storage.AppPaths
import com.romm.androidtv.storage.TestAppPaths
import com.romm.androidtv.storage.fakes.InMemorySessionRecordStore
import com.romm.desktop.player.FakePlayerProcessLauncher
import com.romm.desktop.player.FakeRomContentStager
import com.romm.desktop.player.JournalState
import com.romm.desktop.player.LaunchJournalSupervisor
import com.romm.desktop.player.PlayerExitReport
import com.romm.desktop.player.RomContentStagingException
import com.romm.desktop.settings.DesktopSettingsAdapter
import com.romm.desktop.storage.DesktopSessionStorage
import com.romm.desktop.storage.secret.FakeSecretBackend
import com.romm.desktop.storage.settings.JsonSettingsStore
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@DisplayName("DesktopAppCoordinator — Phase 6 keystone")
class DesktopAppCoordinatorTest {

    private fun coordinator(paths: AppPaths) = DesktopAppCoordinator(
        paths = paths,
        secretBackend = FakeSecretBackend(),
        appVersion = "test",
        buildDefaultOrigin = "https://demo.romm.app",
    )

    private fun record(
        origin: String = "https://romm.example.com",
        username: String? = "zack",
        kioskMode: Boolean = false,
    ) = SessionStorage.Record(
        origin = origin,
        username = username,
        verifiedAtEpochMillis = 123L,
        kioskMode = kioskMode,
    )

    // ---------------------------------------------------------------- decideAppMode

    @Test
    fun `decideAppMode - no session - onboarding`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        assertThat(c.decideAppMode(null, "https://romm.example.com", true)).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `decideAppMode - coherent session matching origin plus token - main`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        assertThat(c.decideAppMode(record(), "https://romm.example.com", true)).isEqualTo(AppMode.MAIN)
    }

    @Test
    fun `decideAppMode - coherent session but no token - onboarding`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        assertThat(c.decideAppMode(record(), "https://romm.example.com", false)).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `decideAppMode - kiosk session no token - main`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        assertThat(c.decideAppMode(
            record(origin = "https://demo.romm.app", kioskMode = true),
            "https://demo.romm.app",
            false,
        )).isEqualTo(AppMode.MAIN)
    }

    @Test
    fun `decideAppMode - origin mismatch - onboarding`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        assertThat(c.decideAppMode(
            record(origin = "https://other.example.com"),
            "https://romm.example.com",
            true,
        )).isEqualTo(AppMode.ONBOARDING)
    }

    // ---------------------------------------------------------------- construction

    @Test
    fun `coordinator constructs with fake secret backend and temp paths`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        assertThat(c.database.schemaVersion).isGreaterThanOrEqualTo(1)
        assertThat(c.computeStartupAppMode()).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `appMode starts onboarding when no session`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        assertThat(c.appMode).isEqualTo(AppMode.ONBOARDING)
        assertThat(c.computeStartupAppMode()).isEqualTo(AppMode.ONBOARDING)
    }

    @Test
    fun `coordinator can enter main mode and route to home`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        c.enterMainMode()
        assertThat(c.appMode).isEqualTo(AppMode.MAIN)
        assertThat(c.currentScreen).isEqualTo(Screen.HOME)
    }

    // ---------------------------------------------------------------- navigation

    @Test
    fun `back from game detail returns to its parent`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        c.appMode = AppMode.MAIN
        c.openGameDetail(romId = 7L, parent = Screen.PLATFORM_DETAIL)
        assertThat(c.currentScreen).isEqualTo(Screen.GAME_DETAIL)
        c.onBack()
        assertThat(c.currentScreen).isEqualTo(Screen.PLATFORM_DETAIL)
        assertThat(c.exitRequested).isFalse()
    }

    @Test
    fun `back from platform detail returns home`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        c.appMode = AppMode.MAIN
        c.openPlatformDetail(1L)
        c.onBack()
        assertThat(c.currentScreen).isEqualTo(Screen.HOME)
    }

    @Test
    fun `back from platform detail returns to platforms when opened there`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        c.appMode = AppMode.MAIN
        c.navigate(Screen.PLATFORMS)
        c.openPlatformDetail(1L)

        c.onBack()

        assertThat(c.currentScreen).isEqualTo(Screen.PLATFORMS)
    }

    @Test
    fun `back at home stays home without requesting exit`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        c.appMode = AppMode.MAIN
        c.currentScreen = Screen.HOME
        c.onBack()
        assertThat(c.currentScreen).isEqualTo(Screen.HOME)
        assertThat(c.exitRequested).isFalse()
    }

    // ---------------------------------------------------------------- player launch (Phase 8)

    private fun testRom(platformSlug: String, fileName: String = "test-game.gb"): RomDetail = RomDetail(
        id = 7L,
        title = "Test Game",
        platformDisplayName = "Nintendo Wii",
        platformSlug = platformSlug,
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
        fileName = fileName,
    )

    @Test
    fun `launchPlayer rejects an unsupported platform without spawning test_core`(@TempDir dir: Path) {
        val paths = dir.testRoot()
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
            buildDefaultOrigin = "https://demo.romm.app",
            playerSupervisorOverride = supervisor,
            // No approved Linux core supports Wii.
            romDetailLookup = { testRom(platformSlug = "wii") },
            romContentStagerOverride = stager,
        )

        val result = c.launchPlayer(romId = 7L)

        assertThat(result).isEqualTo(PlayerLaunchResult.Failed("console is not supported on desktop"))
        assertThat(c.isPlatformPlayable("wii")).isFalse()
        assertThat(launcher.launches).isEmpty()
        assertThat(stager.calls).isEmpty()
    }

    @Test
    fun `launchPlayer fails when the rom detail is not loaded`(@TempDir dir: Path) {
        // No lookup seam: the presenter starts in Loading and can never reach Loaded in a unit
        // test (no network session), so launch must fail fast with "detail not loaded".
        val c = coordinator(dir.testRoot())

        val result = c.launchPlayer(romId = 7L)

        assertThat(result).isEqualTo(PlayerLaunchResult.Failed("detail not loaded"))
    }

    /**
     * Blocks until the exit watcher has reconciled [sessionId] (INTERRUPTED — no result file).
     * Without this, the daemon watcher's journal writes race JUnit's @TempDir cleanup and the
     * test fails intermittently with IOException during temp-dir deletion.
     */
    private fun waitForReconciled(supervisor: LaunchJournalSupervisor, sessionId: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (supervisor.store.read(sessionId).getOrNull()?.state != JournalState.INTERRUPTED) {
            check(System.currentTimeMillis() < deadline) { "exit watcher did not reconcile $sessionId within 5s" }
            Thread.sleep(10)
        }
    }

    /**
     * Coordinator wired to a fake launcher; [platformSlug] drives core resolution. [stager] is a
     * fake by default so real-core launches (which stage content) never touch the network.
     */
    private fun launchCoordinator(
        paths: AppPaths,
        platformSlug: String,
        supervisor: LaunchJournalSupervisor,
        stager: FakeRomContentStager = FakeRomContentStager(),
    ): DesktopAppCoordinator = DesktopAppCoordinator(
        paths = paths,
        secretBackend = FakeSecretBackend(),
        appVersion = "test",
        buildDefaultOrigin = "https://demo.romm.app",
        playerSupervisorOverride = supervisor,
        romDetailLookup = { testRom(platformSlug = platformSlug) },
        romContentStagerOverride = stager,
    )

    /** Installs `libgambatte.so` so the approved gb/gbc core resolves to a real-content core. */
    private fun installGambatte(paths: AppPaths) {
        val coresDir = paths.dataDir.resolve("cores")
        Files.createDirectories(coresDir)
        Files.write(coresDir.resolve("libgambatte.so"), byteArrayOf(0))
    }

    @Test
    fun `launchPlayer stages ROM content and pins path and hash on the request for a real core`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        installGambatte(paths)
        val launcher = FakePlayerProcessLauncher()
        val supervisor = LaunchJournalSupervisor(journalsRoot = paths.stateDir.resolve("journals"), launcher = launcher)
        val stager = FakeRomContentStager()
        val c = DesktopAppCoordinator(
            paths = paths,
            secretBackend = FakeSecretBackend(),
            appVersion = "test",
            buildDefaultOrigin = "https://demo.romm.app",
            playerSupervisorOverride = supervisor,
            romDetailLookup = { testRom(platformSlug = "gb", fileName = "zelda.gb") },
            romContentStagerOverride = stager,
        )

        val started = c.launchPlayer(romId = 7L) as PlayerLaunchResult.Started // cast asserts Started

        // The ROM was staged once with the detail's file name and server-declared size.
        assertThat(stager.calls).containsExactly(
            FakeRomContentStager.Call(
                romId = 7L,
                fileName = "zelda.gb",
                expectedSizeBytes = 1234L,
                supportedExtensions = setOf(".gb", ".gbc"),
            ),
        )
        val staged = stager.lastStaged!!

        // The request pins both the staged path and its SHA-256.
        val request = launcher.launches.single()
        assertThat(request.coreId).isEqualTo("gambatte")
        assertThat(request.contentPath).isEqualTo(staged.path.toAbsolutePath().normalize().toString())
        assertThat(request.contentHash).isEqualTo(sha256Hex("fake-rom-content".toByteArray()))
        waitForReconciled(supervisor, started.sessionId)
    }

    @Test
    fun `launchPlayer fails closed when ROM staging fails and never spawns the player`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        installGambatte(paths)
        val launcher = FakePlayerProcessLauncher()
        val supervisor = LaunchJournalSupervisor(journalsRoot = paths.stateDir.resolve("journals"), launcher = launcher)
        val stager = FakeRomContentStager()
        stager.failure = RomContentStagingException("ROM download failed: HTTP 500")
        val c = DesktopAppCoordinator(
            paths = paths,
            secretBackend = FakeSecretBackend(),
            appVersion = "test",
            buildDefaultOrigin = "https://demo.romm.app",
            playerSupervisorOverride = supervisor,
            romDetailLookup = { testRom(platformSlug = "gb", fileName = "zelda.gb") },
            romContentStagerOverride = stager,
        )

        val result = c.launchPlayer(romId = 7L)

        assertThat(result).isEqualTo(PlayerLaunchResult.Failed("failed to stage ROM content: ROM download failed: HTTP 500"))
        // Never launched without content.
        assertThat(launcher.launches).isEmpty()
    }

    @Test
    fun `savePath is stable across launches of the same rom`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        installGambatte(paths)
        val launcher = FakePlayerProcessLauncher()
        val supervisor = LaunchJournalSupervisor(journalsRoot = paths.stateDir.resolve("journals"), launcher = launcher)
        val stager = FakeRomContentStager()
        val c = launchCoordinator(paths, platformSlug = "gb", supervisor = supervisor, stager = stager)

        val first = c.launchPlayer(romId = 7L) as PlayerLaunchResult.Started
        waitForReconciled(supervisor, first.sessionId)
        val second = c.launchPlayer(romId = 7L) as PlayerLaunchResult.Started
        waitForReconciled(supervisor, second.sessionId)

        val (firstSave, secondSave) = launcher.launches.map { it.savePath }
        // Same ROM → same stable save path (the player's restore-on-launch finds the previous SRAM).
        assertThat(secondSave).isEqualTo(firstSave)
        // Follows the shared SavePathPolicy layout under the data root and is scoped by the
        // verified content hash.
        val savesRoot = paths.dataDir.toAbsolutePath().normalize().toString() + java.io.File.separator + "saves"
        assertThat(firstSave).startsWith(savesRoot)
        assertThat(firstSave).contains(stager.lastStaged!!.sha256)
        assertThat(firstSave).endsWith("autosave" + java.io.File.separator + "srm.srm")
    }

    @Test
    fun `savePath differs across different roms`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        installGambatte(paths)
        val launcher = FakePlayerProcessLauncher()
        val supervisor = LaunchJournalSupervisor(journalsRoot = paths.stateDir.resolve("journals"), launcher = launcher)
        val stager = FakeRomContentStager()
        // One coordinator, two ROMs: the lookup is keyed by id so both resolve to real cores.
        val c = DesktopAppCoordinator(
            paths = paths,
            secretBackend = FakeSecretBackend(),
            appVersion = "test",
            buildDefaultOrigin = "https://demo.romm.app",
            playerSupervisorOverride = supervisor,
            romDetailLookup = { id -> testRom(platformSlug = "gb", fileName = when (id) { 7L -> "a.gb"; else -> "b.gb" }) },
            romContentStagerOverride = stager,
        )

        val first = c.launchPlayer(romId = 7L) as PlayerLaunchResult.Started
        waitForReconciled(supervisor, first.sessionId)
        val second = c.launchPlayer(romId = 8L) as PlayerLaunchResult.Started
        waitForReconciled(supervisor, second.sessionId)

        val (firstSave, secondSave) = launcher.launches.map { it.savePath }
        // Same staged bytes (same hash segment), but the romId segment differs.
        assertThat(secondSave).isNotEqualTo(firstSave)
    }

    @Test
    fun `launchPlayer selects the installed approved platform core`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        // gambatte is the approved core for gb/gbc; install its shared library in the cores root.
        val coresDir = paths.dataDir.resolve("cores")
        Files.createDirectories(coresDir)
        Files.write(coresDir.resolve("libgambatte.so"), byteArrayOf(0))
        val launcher = FakePlayerProcessLauncher()
        val supervisor = LaunchJournalSupervisor(journalsRoot = paths.stateDir.resolve("journals"), launcher = launcher)
        val c = launchCoordinator(paths, platformSlug = "gb", supervisor = supervisor)

        val started = c.launchPlayer(romId = 7L) as PlayerLaunchResult.Started // cast asserts Started

        assertThat(launcher.launches.single().coreId).isEqualTo("gambatte")
        waitForReconciled(supervisor, started.sessionId)
    }

    @Test
    fun `launchPlayer rejects an approved core that is not installed`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        // No libgambatte.so in the cores root.
        val launcher = FakePlayerProcessLauncher()
        val supervisor = LaunchJournalSupervisor(journalsRoot = paths.stateDir.resolve("journals"), launcher = launcher)
        val c = launchCoordinator(paths, platformSlug = "gb", supervisor = supervisor)

        assertThat(c.isPlatformPlayable("gb")).isFalse()
        assertThat(c.launchPlayer(romId = 7L))
            .isEqualTo(PlayerLaunchResult.Failed("console is not supported on desktop"))
        assertThat(launcher.launches).isEmpty()
    }

    @Test
    fun `launchPlayer selects gambatte when its CMake-suffixed library is installed`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        // The Linux player build names the core target `gambatte_core` → libgambatte_core.so.
        val coresDir = paths.dataDir.resolve("cores")
        Files.createDirectories(coresDir)
        Files.write(coresDir.resolve("libgambatte_core.so"), byteArrayOf(0))
        val launcher = FakePlayerProcessLauncher()
        val supervisor = LaunchJournalSupervisor(journalsRoot = paths.stateDir.resolve("journals"), launcher = launcher)
        val c = launchCoordinator(paths, platformSlug = "gb", supervisor = supervisor)

        assertThat(c.isPlatformPlayable("gb")).isTrue()
        val started = c.launchPlayer(romId = 7L) as PlayerLaunchResult.Started // cast asserts Started

        assertThat(launcher.launches.single().coreId).isEqualTo("gambatte")
        waitForReconciled(supervisor, started.sessionId)
    }

    @Test
    fun `launchPlayer rejects a platform core approved only for ARM ABIs`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        // mupen64plus_next is approved for n64 but ARM-only: even with its library installed it must
        // NOT be selected on the Linux desktop (the derived allowlist would reject it anyway).
        val coresDir = paths.dataDir.resolve("cores")
        Files.createDirectories(coresDir)
        Files.write(coresDir.resolve("libmupen64plus_next.so"), byteArrayOf(0))
        Files.write(coresDir.resolve("libtest_core.so"), byteArrayOf(0))
        val launcher = FakePlayerProcessLauncher()
        val supervisor = LaunchJournalSupervisor(journalsRoot = paths.stateDir.resolve("journals"), launcher = launcher)
        val c = launchCoordinator(paths, platformSlug = "n64", supervisor = supervisor)

        assertThat(c.isPlatformPlayable("n64")).isFalse()
        assertThat(c.launchPlayer(romId = 7L))
            .isEqualTo(PlayerLaunchResult.Failed("console is not supported on desktop"))
        assertThat(launcher.launches).isEmpty()
    }

    @Test
    fun `playerSessionEvents emits Ended after the fake player process exits`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        installGambatte(paths)
        val launcher = FakePlayerProcessLauncher()
        val supervisor = LaunchJournalSupervisor(journalsRoot = paths.stateDir.resolve("journals"), launcher = launcher)
        val c = launchCoordinator(paths, platformSlug = "gb", supervisor = supervisor)

        assertThat(c.playerSessionEvents.value).isNull()
        assertThat(c.launchPlayer(romId = 7L)).isInstanceOf(PlayerLaunchResult.Started::class.java)

        // The fake pid does not exist, so the exit watcher reconciles immediately (no result
        // file → CrashInterrupted) and publishes the report to the UI flow. Waiting also
        // guarantees no watcher writes race with JUnit's @TempDir cleanup.
        val deadline = System.currentTimeMillis() + 5_000
        while (c.playerSessionEvents.value !is PlayerSessionEvent.Ended) {
            check(System.currentTimeMillis() < deadline) { "playerSessionEvents did not emit Ended within 5s" }
            Thread.sleep(10)
        }
        val event = c.playerSessionEvents.value as PlayerSessionEvent.Ended
        assertThat(event.report).isInstanceOf(PlayerExitReport.CrashInterrupted::class.java)
        assertThat(c.activePlayerSessionId.value).isNull()

        // A new launch resets the flow to null (a stale Ended must not clear a fresh status).
        val second = c.launchPlayer(romId = 7L) as PlayerLaunchResult.Started
        assertThat(c.playerSessionEvents.value).isNull()
        waitForReconciled(supervisor, second.sessionId)
    }

    @Test
    fun `romDetailPresenter is memoized per rom id and cleared on back from game detail`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())

        val first = c.romDetailPresenter(7L)
        assertThat(c.romDetailPresenter(7L)).isSameAs(first)
        // A different ROM gets its own presenter.
        assertThat(c.romDetailPresenter(8L)).isNotSameAs(first)

        // Leaving GAME_DETAIL clears the memoized presenters (visited-ROM state does not accumulate).
        c.appMode = AppMode.MAIN
        c.openGameDetail(romId = 7L, parent = Screen.HOME)
        assertThat(c.currentScreen).isEqualTo(Screen.GAME_DETAIL)
        c.onBack()

        assertThat(c.romDetailPresenter(7L)).isNotSameAs(first)
    }

    // ---------------------------------------------------------------- settings adapter

    private fun adapter(dir: Path): DesktopSettingsAdapter {
        val store = JsonSettingsStore(dir.resolve("settings.json"))
        val session = DesktopSessionStorage(InMemorySessionRecordStore())
        return DesktopSettingsAdapter(store, session, buildDefaultOrigin = "https://demo.romm.app")
    }

    @Test
    fun `settings adapter origin defaults from buildDefaultOrigin`(@TempDir dir: Path) {
        assertThat(adapter(dir).currentProfile().origin).isEqualTo("https://demo.romm.app")
    }

    @Test
    fun `settings adapter persistValidatedOrigin writes through to the store`(@TempDir dir: Path) {
        // Block body (not `= runBlocking { ... }`): the expression body made the compiler infer a
        // non-void return type (AssertJ's isEqualTo returns SELF), and JUnit 5 silently skips
        // @Test methods that don't return void — so this test never executed.
        runBlocking {
            val a = adapter(dir)
            assertThat(a.persistValidatedOrigin("https://romm.example.com")).isTrue()
            assertThat(a.currentProfile().origin).isEqualTo("https://romm.example.com")
            // Invalid origin is rejected and does not overwrite the stored value. (Spaces make it
            // genuinely unparseable — "not-a-valid-url" is actually a valid hostname, so it parses.)
            assertThat(a.persistValidatedOrigin("not a valid url")).isFalse()
            assertThat(a.currentProfile().origin).isEqualTo("https://romm.example.com")
        }
    }

    @Test
    fun `settings adapter hideUnsupportedSystems round-trips`(@TempDir dir: Path) {
        val a = adapter(dir)
        assertThat(a.hideUnsupportedSystems()).isTrue() // default true
        a.setHideUnsupportedSystems(false)
        assertThat(a.hideUnsupportedSystems()).isFalse()
        a.setHideUnsupportedSystems(true)
        assertThat(a.hideUnsupportedSystems()).isTrue()
    }

    private fun Path.testRoot(): AppPaths = TestAppPaths(this)
}

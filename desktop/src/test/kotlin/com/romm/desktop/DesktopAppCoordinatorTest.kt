package com.romm.desktop

import com.romm.androidtv.auth.SessionStorage
import com.romm.androidtv.library.RomDetail
import com.romm.androidtv.onboarding.OnboardingRoutingDecision.AppMode
import com.romm.androidtv.emulation.model.sha256Hex
import com.romm.androidtv.romm.FirmwareStagingOutcome
import com.romm.androidtv.storage.AppPaths
import com.romm.androidtv.storage.TestAppPaths
import com.romm.androidtv.storage.firmwareDir
import com.romm.androidtv.storage.fakes.InMemorySessionRecordStore
import com.romm.androidtv.storage.ports.SettingsKeys
import com.romm.desktop.library.StubServer
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

    /** Installs `libgenesis_plus_gx.so` so the approved segacd core resolves to a real-content core. */
    private fun installGenesisPlusGx(paths: AppPaths) {
        val coresDir = paths.dataDir.resolve("cores")
        Files.createDirectories(coresDir)
        Files.write(coresDir.resolve("libgenesis_plus_gx.so"), byteArrayOf(0))
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
    fun `savePath is stable and isolated for Sega CD CHD launches`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        installGenesisPlusGx(paths)
        val server = StubServer().apply { start() }
        try {
            server.platformsJson(5L, "segacd")
            // Pre-stage the selected BIOS file so prepareForLaunch succeeds without a download
            // (the staged file on disk is the selection source of truth).
            val firmwareDir = paths.firmwareDir()
            Files.createDirectories(firmwareDir)
            val biosBytes = "fake-sega-cd-bios".toByteArray()
            Files.write(firmwareDir.resolve("41_bios_CD_USA.bin"), biosBytes)
            server.firmwareJson(
                """{"id": 41, "file_name": "bios_CD_USA.bin", "file_size_bytes": ${biosBytes.size}, """ +
                    """"sha1_hash": "0000000000000000000000000000000000000000", "is_verified": true}""",
            )
            // CHD-shaped content: the save path must be scoped by this staged content's hash.
            val chdBytes = "MComprHD".toByteArray() + ByteArray(32) { it.toByte() }
            val stager = FakeRomContentStager(contentBytes = chdBytes)
            val launcher = FakePlayerProcessLauncher()
            val supervisor = LaunchJournalSupervisor(journalsRoot = paths.stateDir.resolve("journals"), launcher = launcher)
            val c = DesktopAppCoordinator(
                paths = paths,
                secretBackend = FakeSecretBackend(),
                appVersion = "test",
                buildDefaultOrigin = "https://demo.romm.app",
                playerSupervisorOverride = supervisor,
                romDetailLookup = { testRom(platformSlug = "segacd", fileName = "Sonic CD (USA)") },
                romContentStagerOverride = stager,
            )
            c.settingsStore.write(mapOf(SettingsKeys.ORIGIN to server.origin))

            val first = c.launchPlayer(romId = 7L) as PlayerLaunchResult.Started // cast asserts Started
            waitForReconciled(supervisor, first.sessionId)
            val second = c.launchPlayer(romId = 7L) as PlayerLaunchResult.Started
            waitForReconciled(supervisor, second.sessionId)
            val other = c.launchPlayer(romId = 8L) as PlayerLaunchResult.Started
            waitForReconciled(supervisor, other.sessionId)

            val (firstSave, secondSave, otherSave) = launcher.launches.map { it.savePath }
            // Same Sega CD ROM + same staged CHD bytes → the identical stable save path across launches.
            assertThat(secondSave).isEqualTo(firstSave)
            // A different ROM never shares a save, even for CHD content.
            assertThat(otherSave).isNotEqualTo(firstSave)
            // Follows the shared SavePathPolicy layout under the data root, scoped by the staged
            // CHD content's SHA-256 and the server origin.
            val savesRoot = paths.dataDir.toAbsolutePath().normalize().toString() + java.io.File.separator + "saves"
            assertThat(firstSave).startsWith(savesRoot)
            // Server segment sanitized exactly like SavePathPolicy (only '/' and '\' → '_').
            assertThat(firstSave).contains(server.origin.replace('/', '_').replace('\\', '_'))
            assertThat(firstSave).contains(sha256Hex(chdBytes))
            assertThat(firstSave).endsWith("autosave" + java.io.File.separator + "srm.srm")
        } finally {
            server.close()
        }
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
    fun `launchPlayer selects mupen64plus_next for N64 on Linux`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        val coresDir = paths.dataDir.resolve("cores")
        Files.createDirectories(coresDir)
        Files.write(coresDir.resolve("libmupen64plus_next.so"), byteArrayOf(0))
        val launcher = FakePlayerProcessLauncher()
        val supervisor = LaunchJournalSupervisor(journalsRoot = paths.stateDir.resolve("journals"), launcher = launcher)
        val c = launchCoordinator(paths, platformSlug = "n64", supervisor = supervisor)

        assertThat(c.isPlatformPlayable("n64")).isTrue()
        val started = c.launchPlayer(romId = 7L) as PlayerLaunchResult.Started

        assertThat(launcher.launches.single().coreId).isEqualTo("mupen64plus_next")
        waitForReconciled(supervisor, started.sessionId)
    }

    // ---------------------------------------------------------------- BIOS launch failures (Phase 11 work item 6)

    @Test
    fun `firmwareLaunchFailureReason distinguishes missing, corrupted, and network BIOS failures`() {
        assertThat(firmwareLaunchFailureReason(
            FirmwareStagingOutcome.Missing(listOf("bios_CD_U.bin", "bios_CD_E.bin", "bios_CD_J.bin")),
            platformSlug = "segacd",
        )).isEqualTo(
            "SEGA CD requires a BIOS (bios_CD_U.bin, bios_CD_E.bin, bios_CD_J.bin). Configure it in System Settings.",
        )
        assertThat(firmwareLaunchFailureReason(
            FirmwareStagingOutcome.CorruptedDownload("scph5500.bin", "SHA-1 mismatch"),
            platformSlug = "psx",
        )).isEqualTo("The configured BIOS failed verification (SHA-1 mismatch).")
        assertThat(firmwareLaunchFailureReason(
            FirmwareStagingOutcome.NetworkError("HTTP 503"),
            platformSlug = "segacd",
        )).isEqualTo("Could not download the BIOS: HTTP 503.")
    }

    @Test
    fun `firmwareLaunchFailureReason covers auth expiry, insufficient space, and unknown slugs`() {
        assertThat(firmwareLaunchFailureReason(FirmwareStagingOutcome.AuthExpired, platformSlug = "psx"))
            .isEqualTo("Session expired; log in again to configure the PlayStation BIOS.")
        assertThat(firmwareLaunchFailureReason(
            FirmwareStagingOutcome.InsufficientSpace(requiredBytes = 1024L, availableBytes = 10L),
            platformSlug = "segacd",
        )).isEqualTo("Not enough storage to download the SEGA CD BIOS.")
        assertThat(firmwareLaunchFailureReason(
            FirmwareStagingOutcome.Missing(listOf("bios_CD_U.bin")),
            platformSlug = "dreamcast",
        )).isEqualTo("Dreamcast requires a BIOS (bios_CD_U.bin). Configure it in System Settings.")
    }

    @Test
    fun `launchPlayer surfaces the focused missing-BIOS message when the server has no Sega CD firmware`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        installGenesisPlusGx(paths)
        val server = StubServer().apply { start() }
        try {
            server.platformsJson(5L, "segacd")
            // Default firmwareBody is "[]" — the platform exists but offers no BIOS files.
            val launcher = FakePlayerProcessLauncher()
            val supervisor = LaunchJournalSupervisor(
                journalsRoot = paths.stateDir.resolve("journals"),
                launcher = launcher,
            )
            val c = launchCoordinator(paths, platformSlug = "segacd", supervisor = supervisor)
            c.settingsStore.write(mapOf(SettingsKeys.ORIGIN to server.origin))

            val result = c.launchPlayer(romId = 7L)

            assertThat(result).isEqualTo(
                PlayerLaunchResult.Failed(
                    "SEGA CD requires a BIOS (bios_CD_U.bin, bios_CD_E.bin, bios_CD_J.bin). Configure it in System Settings.",
                ),
            )
            assertThat(launcher.launches).isEmpty() // fail-closed: no player without a verified BIOS
        } finally {
            server.close()
        }
    }

    @Test
    fun `launchPlayer surfaces the focused corrupted-BIOS message when the download fails SHA-1 verification`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        installGenesisPlusGx(paths)
        val server = StubServer().apply { start() }
        try {
            server.platformsJson(5L, "segacd")
            // The entry matches the preferred US SHA-1 (so launch preparation selects it), but the
            // served bytes do not hash to it — size is declared correctly so only the hash check fails.
            val contents = "NOT-A-REAL-BIOS".toByteArray()
            server.firmwareJson(
                """{"id": 41, "file_name": "bios_CD_USA.bin", "file_size_bytes": ${contents.size}, """ +
                    """"sha1_hash": "f4f315adcef9b8feb0364c21ab7f0eaf5457f3ed", "is_verified": true}""",
            )
            server.content(contents)
            val launcher = FakePlayerProcessLauncher()
            val supervisor = LaunchJournalSupervisor(
                journalsRoot = paths.stateDir.resolve("journals"),
                launcher = launcher,
            )
            val c = launchCoordinator(paths, platformSlug = "segacd", supervisor = supervisor)
            c.settingsStore.write(mapOf(SettingsKeys.ORIGIN to server.origin))

            val result = c.launchPlayer(romId = 7L)

            assertThat(result).isEqualTo(
                PlayerLaunchResult.Failed("The configured BIOS failed verification (SHA-1 mismatch)."),
            )
            assertThat(launcher.launches).isEmpty() // fail-closed: no player without a verified BIOS
        } finally {
            server.close()
        }
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

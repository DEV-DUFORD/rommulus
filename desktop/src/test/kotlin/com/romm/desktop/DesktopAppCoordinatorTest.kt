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
import com.romm.desktop.player.ControllerBindingIdentity
import com.romm.desktop.player.FakePlayerProcessLauncher
import com.romm.desktop.player.FakeRomContentStager
import com.romm.desktop.player.JournalState
import com.romm.desktop.player.LaunchJournalSupervisor
import com.romm.desktop.player.PlayerBindingType
import com.romm.desktop.player.PlayerExitReport
import com.romm.desktop.player.PlayerSlotBinding
import com.romm.desktop.player.RomContentStagingException
import com.romm.desktop.player.RomContentStagingFailure
import com.romm.desktop.player.VideoSettings
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

    // ---------------------------------------------------------------- controller settings (E2)

    @Test
    fun `openControllerSettings navigates to the controller console list`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        c.appMode = AppMode.MAIN
        c.currentScreen = Screen.SETTINGS
        c.openControllerSettings()
        assertThat(c.currentScreen).isEqualTo(Screen.CONTROLLER_LIST)
    }

    @Test
    fun `openControllerConfig sets the core selection and navigates to controller config`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        c.appMode = AppMode.MAIN
        c.openControllerSettings()
        c.openControllerConfig("snes9x")
        assertThat(c.currentScreen).isEqualTo(Screen.CONTROLLER_CONFIG)
        assertThat(c.selectedControllerCoreId).isEqualTo("snes9x")
    }

    @Test
    fun `back from controller config returns to the controller list`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        c.appMode = AppMode.MAIN
        c.openControllerConfig("fceumm")
        assertThat(c.currentScreen).isEqualTo(Screen.CONTROLLER_CONFIG)
        c.onBack()
        assertThat(c.currentScreen).isEqualTo(Screen.CONTROLLER_LIST)
    }

    @Test
    fun `back from controller list returns to settings`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        c.appMode = AppMode.MAIN
        c.openControllerSettings()
        assertThat(c.currentScreen).isEqualTo(Screen.CONTROLLER_LIST)
        c.onBack()
        assertThat(c.currentScreen).isEqualTo(Screen.SETTINGS)
    }

    @Test
    fun `controller config exposes the store-backed repository and shared input source`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        // The settings screens read merged configs through the same durable store the player
        // sidecar ingest writes to.
        assertThat(c.controllerConfigRepository).isNotNull()
        assertThat(c.controllerInputSource).isNotNull()
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

    /** Installs `libpcsx_rearmed.so` so the approved psx core resolves to a real-content core. */
    private fun installPcsxRearmed(paths: AppPaths) {
        val coresDir = paths.dataDir.resolve("cores")
        Files.createDirectories(coresDir)
        Files.write(coresDir.resolve("libpcsx_rearmed.so"), byteArrayOf(0))
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
    fun `launchPlayer passes persisted video options from the settings store into the request`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        installGambatte(paths)
        val launcher = FakePlayerProcessLauncher()
        val supervisor = LaunchJournalSupervisor(journalsRoot = paths.stateDir.resolve("journals"), launcher = launcher)
        val c = DesktopAppCoordinator(
            paths = paths,
            secretBackend = FakeSecretBackend(),
            appVersion = "test",
            buildDefaultOrigin = "https://demo.romm.app",
            playerSupervisorOverride = supervisor,
            romDetailLookup = { testRom(platformSlug = "gb") },
            romContentStagerOverride = FakeRomContentStager(),
        )

        // Persist the user's Video Options choices (the same keys Android writes via
        // SettingsRepository) BEFORE launching: the request must carry them.
        c.settingsStore.write(
            mapOf(
                SettingsKeys.SCANLINES_ENABLED to "true",
                SettingsKeys.INTEGER_SCALING_ENABLED to "true",
                SettingsKeys.SHARP_FILTER_ENABLED to "true",
            ),
        )

        val started = c.launchPlayer(romId = 7L) as PlayerLaunchResult.Started // cast asserts Started

        val request = launcher.launches.single()
        assertThat(request.video).isEqualTo(
            VideoSettings(fullscreen = false, integerScaling = true, scanlines = true, sharpFilter = true),
        )
        waitForReconciled(supervisor, started.sessionId)
    }

    @Test
    fun `launchPlayer defaults video options to off when none are persisted`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        installGambatte(paths)
        val launcher = FakePlayerProcessLauncher()
        val supervisor = LaunchJournalSupervisor(journalsRoot = paths.stateDir.resolve("journals"), launcher = launcher)
        val c = DesktopAppCoordinator(
            paths = paths,
            secretBackend = FakeSecretBackend(),
            appVersion = "test",
            buildDefaultOrigin = "https://demo.romm.app",
            playerSupervisorOverride = supervisor,
            romDetailLookup = { testRom(platformSlug = "gb") },
            romContentStagerOverride = FakeRomContentStager(),
        )

        val started = c.launchPlayer(romId = 7L) as PlayerLaunchResult.Started // cast asserts Started

        assertThat(launcher.launches.single().video).isEqualTo(VideoSettings())
        waitForReconciled(supervisor, started.sessionId)
    }

    // ---------------------------------------------------------------- controller bindings (Phase 9, §11.9)

    /** A sidecar the fake player writes at shutdown: one USB pad with a non-default table. */
    private val fakeBindingSidecar = """
        {
          "protocolVersion": 1,
          "devices": [
            {
              "guid": "036d04ca010000000000000000000000",
              "identity": {"vendorId": 1133, "productId": 458, "descriptor": "vid:046d-pid:01ca"},
              "bindings": [
                {"slot": "a", "type": "button", "button": "south"},
                {"slot": "b", "type": "axis_direction", "axis": "left_x", "polarity": -1},
                {"slot": "x", "type": "button", "button": "west"},
                {"slot": "y", "type": "unbound"},
                {"slot": "select", "type": "button", "button": "back"},
                {"slot": "start", "type": "button", "button": "start"},
                {"slot": "left_shoulder", "type": "axis_direction", "axis": "left_trigger", "polarity": 1},
                {"slot": "right_shoulder", "type": "button", "button": "right_shoulder"},
                {"slot": "dpad_up", "type": "button", "button": "dpad_up"},
                {"slot": "dpad_down", "type": "button", "button": "dpad_down"},
                {"slot": "dpad_left", "type": "axis_direction", "axis": "left_x", "polarity": -1},
                {"slot": "dpad_right", "type": "button", "button": "dpad_right"}
              ]
            }
          ]
        }
    """.trimIndent()

    /** The 12-slot table the fake sidecar above carries, in wire order. */
    private val expectedSidecarSlots = listOf(
        PlayerSlotBinding("a", PlayerBindingType.BUTTON, button = "south"),
        PlayerSlotBinding("b", PlayerBindingType.AXIS_DIRECTION, axis = "left_x", polarity = -1),
        PlayerSlotBinding("x", PlayerBindingType.BUTTON, button = "west"),
        PlayerSlotBinding("y", PlayerBindingType.UNBOUND),
        PlayerSlotBinding("select", PlayerBindingType.BUTTON, button = "back"),
        PlayerSlotBinding("start", PlayerBindingType.BUTTON, button = "start"),
        PlayerSlotBinding("left_shoulder", PlayerBindingType.AXIS_DIRECTION, axis = "left_trigger", polarity = 1),
        PlayerSlotBinding("right_shoulder", PlayerBindingType.BUTTON, button = "right_shoulder"),
        PlayerSlotBinding("dpad_up", PlayerBindingType.BUTTON, button = "dpad_up"),
        PlayerSlotBinding("dpad_down", PlayerBindingType.BUTTON, button = "dpad_down"),
        PlayerSlotBinding("dpad_left", PlayerBindingType.AXIS_DIRECTION, axis = "left_x", polarity = -1),
        PlayerSlotBinding("dpad_right", PlayerBindingType.BUTTON, button = "dpad_right"),
    )

    /**
     * Coordinator wired the way production wires it: the supervisor ingests the session's
     * controller-binding sidecar into the coordinator's binding store. The `lateinit` dance is
     * because the ingestor lambda references the coordinator that owns this very supervisor.
     */
    private fun bindingWiredCoordinator(
        paths: AppPaths,
        platformSlug: String,
        launcher: FakePlayerProcessLauncher,
    ): Pair<DesktopAppCoordinator, LaunchJournalSupervisor> {
        lateinit var c: DesktopAppCoordinator
        val supervisor = LaunchJournalSupervisor(
            journalsRoot = paths.stateDir.resolve("journals"),
            launcher = launcher,
            bindingSidecarIngestor = { sessionDir -> c.ingestControllerBindingSidecar(sessionDir) },
        )
        c = DesktopAppCoordinator(
            paths = paths,
            secretBackend = FakeSecretBackend(),
            appVersion = "test",
            buildDefaultOrigin = "https://demo.romm.app",
            playerSupervisorOverride = supervisor,
            romDetailLookup = { testRom(platformSlug = platformSlug) },
            romContentStagerOverride = FakeRomContentStager(),
        )
        return c to supervisor
    }

    @Test
    fun `player sidecar is ingested into the binding store and deleted on exit`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        installGambatte(paths)
        // Simulate what the real player does at shutdown: write a non-default binding sidecar
        // into the session dir (synchronously, before the exit watcher can run).
        val launcher = FakePlayerProcessLauncher(onLaunch = { request ->
            Files.writeString(
                Path.of(request.resultPath).parent.resolve("controller-bindings.json"),
                fakeBindingSidecar,
            )
        })
        val (c, supervisor) = bindingWiredCoordinator(paths, "gb", launcher)

        val started = c.launchPlayer(romId = 7L) as PlayerLaunchResult.Started // cast asserts Started
        waitForReconciled(supervisor, started.sessionId)

        // All 12 slots were stored for the session's core under player index 0 / PRIMARY.
        val records = c.controllerBindingStore.loadForCore("gambatte")
        assertThat(records).hasSize(12)
        assertThat(records).allSatisfy { record ->
            assertThat(record.playerIndex).isZero()
            assertThat(record.bindingSlot).isZero()
        }
        fun row(controlId: String) = records.first { it.controlId == controlId }
        // slot a (button_a): KEY with the PadButton ordinal of "south" (0).
        assertThat(row("button_a").bindingType).isEqualTo("KEY")
        assertThat(row("button_a").inputCode).isZero()
        assertThat(row("button_a").polarity).isNull()
        // slot b (button_b): AXIS_DIRECTION left_x (ordinal 0), polarity -1.
        assertThat(row("button_b").bindingType).isEqualTo("AXIS_DIRECTION")
        assertThat(row("button_b").inputCode).isZero()
        assertThat(row("button_b").polarity).isEqualTo(-1)
        // slot y (button_y): unbound -> UNMAPPED row.
        assertThat(row("button_y").bindingType).isEqualTo("UNMAPPED")
        // left_shoulder (l1): AXIS_DIRECTION left_trigger (ordinal 4), polarity +1.
        assertThat(row("l1").bindingType).isEqualTo("AXIS_DIRECTION")
        assertThat(row("l1").inputCode).isEqualTo(4)
        assertThat(row("l1").polarity).isEqualTo(1)
        // dpad_up (d_pad_up): KEY with the PadButton ordinal of "dpad_up" (8).
        assertThat(row("d_pad_up").bindingType).isEqualTo("KEY")
        assertThat(row("d_pad_up").inputCode).isEqualTo(8)

        // The sidecar is a session artifact: deleted after successful ingestion.
        val sessionDir = supervisor.store.sessionDir(started.sessionId)
        assertThat(Files.exists(sessionDir.resolve("controller-bindings.json"))).isFalse()
    }

    @Test
    fun `stored bindings are serialized into the v2 request and omitted when none exist`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        installGambatte(paths)
        val launcher = FakePlayerProcessLauncher(onLaunch = { request ->
            Files.writeString(
                Path.of(request.resultPath).parent.resolve("controller-bindings.json"),
                fakeBindingSidecar,
            )
        })
        val (c, supervisor) = bindingWiredCoordinator(paths, "gb", launcher)

        // First launch: nothing stored yet -> the v2 field is OMITTED (player uses defaults).
        val first = c.launchPlayer(romId = 7L) as PlayerLaunchResult.Started
        assertThat(launcher.launches[0].controllerBindings).isNull()
        waitForReconciled(supervisor, first.sessionId)

        // Second launch: the ingested table is serialized into controllerBindings so the player
        // applies it from the first frame — one "all controllers" device (empty guid/identity)
        // carrying the stored 12-slot table.
        val second = c.launchPlayer(romId = 7L) as PlayerLaunchResult.Started
        waitForReconciled(supervisor, second.sessionId)

        val bindings = launcher.launches[1].controllerBindings
            ?: throw AssertionError("second launch must carry controllerBindings")
        assertThat(bindings.devices).hasSize(1)
        assertThat(bindings.devices[0].guid).isEmpty()
        assertThat(bindings.devices[0].identity).isEqualTo(ControllerBindingIdentity(null, null, ""))
        assertThat(bindings.devices[0].bindings).containsExactlyElementsOf(expectedSidecarSlots)
    }

    @Test
    fun `malformed sidecar is preserved and never breaks reconciliation`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        installGambatte(paths)
        val launcher = FakePlayerProcessLauncher(onLaunch = { request ->
            Files.writeString(
                Path.of(request.resultPath).parent.resolve("controller-bindings.json"),
                """{"protocolVersion": 1, "devices": [{"guid": 42}]}""",
            )
        })
        val (c, supervisor) = bindingWiredCoordinator(paths, "gb", launcher)

        val started = c.launchPlayer(romId = 7L) as PlayerLaunchResult.Started
        waitForReconciled(supervisor, started.sessionId)

        // Nothing was ingested...
        assertThat(c.controllerBindingStore.loadForCore("gambatte")).isEmpty()
        // ...and the malformed sidecar is preserved for forensics (fail-closed).
        val sessionDir = supervisor.store.sessionDir(started.sessionId)
        assertThat(Files.exists(sessionDir.resolve("controller-bindings.json"))).isTrue()
    }

    @Test
    fun `launchPlayer fails closed when ROM staging fails and never spawns the player`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        installGambatte(paths)
        val launcher = FakePlayerProcessLauncher()
        val supervisor = LaunchJournalSupervisor(journalsRoot = paths.stateDir.resolve("journals"), launcher = launcher)
        val stager = FakeRomContentStager()
        stager.failure = RomContentStagingException(
            "HTTP 500 for 'zelda.gb'",
            failure = RomContentStagingFailure.DownloadFailed,
        )
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

        // Focused user-facing state (Phase 11 work item 6), not the raw exception message.
        assertThat(result).isEqualTo(PlayerLaunchResult.Failed("Could not download the ROM content: HTTP 500 for 'zelda.gb'."))
        // Never launched without content.
        assertThat(launcher.launches).isEmpty()
    }

    @Test
    fun `launchPlayer surfaces a focused reason when staged content is not a valid CHD`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        installGambatte(paths)
        val launcher = FakePlayerProcessLauncher()
        val supervisor = LaunchJournalSupervisor(journalsRoot = paths.stateDir.resolve("journals"), launcher = launcher)
        val stager = FakeRomContentStager()
        stager.failure = RomContentStagingException(
            "ROM content 'Sonic CD (USA).chd' is not a valid CHD file (missing MComprHD signature)",
            failure = RomContentStagingFailure.InvalidChdSignature,
        )
        val c = DesktopAppCoordinator(
            paths = paths,
            secretBackend = FakeSecretBackend(),
            appVersion = "test",
            buildDefaultOrigin = "https://demo.romm.app",
            playerSupervisorOverride = supervisor,
            romDetailLookup = { testRom(platformSlug = "gb", fileName = "Sonic CD (USA).chd") },
            romContentStagerOverride = stager,
        )

        val result = c.launchPlayer(romId = 7L)

        assertThat(result).isEqualTo(
            PlayerLaunchResult.Failed(
                "Content verification failed: 'Sonic CD (USA).chd' is not a valid CHD file " +
                    "(missing MComprHD signature). Re-upload this ROM in your RomM library.",
            ),
        )
        // Never launched with unverified content.
        assertThat(launcher.launches).isEmpty()
    }

    @Test
    fun `launchPlayer surfaces a focused reason when the staged ROM size does not match`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        installGambatte(paths)
        val launcher = FakePlayerProcessLauncher()
        val supervisor = LaunchJournalSupervisor(journalsRoot = paths.stateDir.resolve("journals"), launcher = launcher)
        val stager = FakeRomContentStager()
        stager.failure = RomContentStagingException(
            "ROM size mismatch for 'zelda.gb': expected 1234 bytes, got 5",
            failure = RomContentStagingFailure.SizeMismatch,
        )
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

        assertThat(result).isEqualTo(
            PlayerLaunchResult.Failed(
                "Content verification failed: 'zelda.gb' is incomplete or does not match its expected size. " +
                    "The download may be corrupt — try launching again, or re-upload this ROM in your RomM library.",
            ),
        )
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
    fun `savePath is stable and isolated for PlayStation CHD launches`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        installPcsxRearmed(paths)
        val server = StubServer().apply { start() }
        try {
            server.platformsJson(5L, "psx")
            // Pre-stage the selected BIOS file (the preferred US scph5500.bin) so prepareForLaunch
            // succeeds without a download (the staged file on disk is the selection source of truth).
            val firmwareDir = paths.firmwareDir()
            Files.createDirectories(firmwareDir)
            val biosBytes = "fake-playstation-bios".toByteArray()
            Files.write(firmwareDir.resolve("41_scph5500.bin"), biosBytes)
            server.firmwareJson(
                """{"id": 41, "file_name": "scph5500.bin", "file_size_bytes": ${biosBytes.size}, """ +
                    """"sha1_hash": "0555c6fae8906f3f09baf5988f00e55f88e9f30b", "is_verified": true}""",
            )
            // CHD-shaped content (MComprHD signature): the save path must be scoped by this staged
            // content's hash.
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
                romDetailLookup = { testRom(platformSlug = "psx", fileName = "Final Fantasy VII (USA).chd") },
                romContentStagerOverride = stager,
            )
            c.settingsStore.write(mapOf(SettingsKeys.ORIGIN to server.origin))

            val first = c.launchPlayer(romId = 7L) as PlayerLaunchResult.Started // cast asserts Started
            val stagedFirst = stager.lastStaged!!
            waitForReconciled(supervisor, first.sessionId)
            val second = c.launchPlayer(romId = 7L) as PlayerLaunchResult.Started
            waitForReconciled(supervisor, second.sessionId)
            val other = c.launchPlayer(romId = 8L) as PlayerLaunchResult.Started
            val stagedOther = stager.lastStaged!!
            waitForReconciled(supervisor, other.sessionId)

            // pcsx_rearmed is the approved core for psx on Linux.
            assertThat(launcher.launches.map { it.coreId }).containsOnly("pcsx_rearmed")
            // Each launch pins the CHD-staged path of its own ROM; both pin the same staged bytes.
            assertThat(launcher.launches[0].contentPath)
                .isEqualTo(stagedFirst.path.toAbsolutePath().normalize().toString())
            assertThat(launcher.launches[1].contentPath)
                .isEqualTo(stagedFirst.path.toAbsolutePath().normalize().toString())
            assertThat(launcher.launches[2].contentPath)
                .isEqualTo(stagedOther.path.toAbsolutePath().normalize().toString())
            assertThat(stagedOther.path).isNotEqualTo(stagedFirst.path)
            assertThat(launcher.launches.map { it.contentHash }).containsOnly(sha256Hex(chdBytes))

            val (firstSave, secondSave, otherSave) = launcher.launches.map { it.savePath }
            // Same PlayStation ROM + same staged CHD bytes → the identical stable save path across launches.
            assertThat(secondSave).isEqualTo(firstSave)
            // A different ROM never shares a save, even for CHD content.
            assertThat(otherSave).isNotEqualTo(firstSave)
            // Follows the shared SavePathPolicy layout under the data root, scoped by the staged
            // CHD content's SHA-256 and the server origin.
            val savesRoot = paths.dataDir.toAbsolutePath().normalize().toString() + java.io.File.separator + "saves"
            assertThat(firstSave).startsWith(savesRoot)
            // Server segment sanitized exactly like SavePathPolicy (only '/' and '\' → '_').
            assertThat(firstSave).contains(server.origin.replace('/', '_').replace('\\', '_'))
            // The romId segment scopes the save per ROM.
            assertThat(firstSave).contains(java.io.File.separator + "7" + java.io.File.separator)
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
    fun `known BIOS hashes are ready before a manual desktop selection`(@TempDir dir: Path) = runBlocking {
        val paths = dir.testRoot()
        val server = StubServer().apply { start() }
        try {
            val c = coordinator(paths)
            c.settingsStore.write(mapOf(SettingsKeys.ORIGIN to server.origin))

            server.platformsJson(5L, "segacd")
            server.firmwareJson(
                """{"id": 41, "file_name": "custom-sega.bin", "file_size_bytes": 1, """ +
                    """"sha1_hash": "f4f315adcef9b8feb0364c21ab7f0eaf5457f3ed", "is_verified": true}""",
            )
            assertThat(c.checkRequiredBiosAvailability("segacd")).isEqualTo(RequiredBiosState.Ready)

            server.platformsJson(6L, "psx")
            server.firmwareJson(
                """{"id": 42, "file_name": "custom-psx.bin", "file_size_bytes": 1, """ +
                    """"sha1_hash": "0555c6fae8906f3f09baf5988f00e55f88e9f30b", "is_verified": true}""",
            )
            assertThat(c.checkRequiredBiosAvailability("psx")).isEqualTo(RequiredBiosState.Ready)
        } finally {
            server.close()
        }
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

    @Test
    fun `settings adapter video options persist globally`(@TempDir dir: Path) {
        val first = adapter(dir)
        first.setVideoOptions(scanlines = true, integerScaling = true, sharpFilter = false)

        val reloaded = adapter(dir)
        assertThat(reloaded.scanlinesEnabled()).isTrue()
        assertThat(reloaded.integerScalingEnabled()).isTrue()
        assertThat(reloaded.sharpFilterEnabled()).isFalse()
    }

    private fun Path.testRoot(): AppPaths = TestAppPaths(this)
}

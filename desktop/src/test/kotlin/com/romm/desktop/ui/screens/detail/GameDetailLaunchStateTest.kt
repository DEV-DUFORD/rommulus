package com.romm.desktop.ui.screens.detail

import com.romm.androidtv.library.RomDetail
import com.romm.androidtv.onboarding.OnboardingRoutingDecision.AppMode
import com.romm.androidtv.storage.AppPaths
import com.romm.androidtv.storage.TestAppPaths
import com.romm.androidtv.storage.ports.SettingsKeys
import com.romm.desktop.DesktopAppCoordinator
import com.romm.desktop.PlayerLaunchResult
import com.romm.desktop.RequiredBiosState
import com.romm.desktop.launchFailureIsAuthExpired
import com.romm.desktop.library.StubServer
import com.romm.desktop.player.FakePlayerProcessLauncher
import com.romm.desktop.player.FakeRomContentStager
import com.romm.desktop.player.LaunchJournalSupervisor
import com.romm.desktop.storage.secret.FakeSecretBackend
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tests for the game-detail launch states (Android `SavePreLaunchState` / pre-launch parity):
 *  - the auth-expired state ("Session expired" + Log in) — [launchFailureIsAuthExpired] mapping,
 *    the coordinator's begin/finish/dismiss state machine, and an end-to-end SEGA CD launch
 *    against an expired session (401 BIOS catalog fetch);
 *  - the staging state ("Preparing…" disabled Play button) — duplicate-entry guard + clearing;
 *  - the required-BIOS inline states — [requiredBiosUnavailableMessage] messaging.
 */
@DisplayName("GameDetailScreen — launch states (auth expired / staging / required BIOS)")
class GameDetailLaunchStateTest {

    private companion object {
        const val ROM_ID = 7L
    }

    // ── auth-expired failure classification ──────────────────────────────────────────────

    @Test
    fun `auth-expired launch failure reasons are recognized`() {
        assertThat(
            launchFailureIsAuthExpired("Session expired; log in again to configure the SEGA CD BIOS."),
        ).isTrue()
        assertThat(
            launchFailureIsAuthExpired("Session expired; log in again to configure the PlayStation BIOS."),
        ).isTrue()
        assertThat(launchFailureIsAuthExpired("no active session — log in again to use a saved game")).isTrue()
    }

    @Test
    fun `non-auth failures are not classified as auth-expired`() {
        assertThat(
            launchFailureIsAuthExpired(
                "SEGA CD requires a BIOS (bios_CD_U.bin, bios_CD_E.bin, bios_CD_J.bin). Configure it in System Settings.",
            ),
        ).isFalse()
        assertThat(launchFailureIsAuthExpired("Could not download the ROM content: HTTP 503.")).isFalse()
        assertThat(launchFailureIsAuthExpired("detail not loaded")).isFalse()
    }

    // ── required-BIOS messaging (Android parity) ─────────────────────────────────────────

    @Test
    fun `requiredBiosUnavailableMessage maps every state`() {
        assertThat(requiredBiosUnavailableMessage(RequiredBiosState.Checking))
            .isEqualTo("Checking for required BIOS files…")
        assertThat(requiredBiosUnavailableMessage(RequiredBiosState.Missing))
            .isEqualTo("Missing BIOS files on server. Please contact your RomM administrator.")
        assertThat(requiredBiosUnavailableMessage(RequiredBiosState.UnverifiedAvailable))
            .isEqualTo("No verified BIOS file found. Please choose one in Settings.")
        assertThat(requiredBiosUnavailableMessage(RequiredBiosState.Error("bios lookup timed out")))
            .isEqualTo("bios lookup timed out")
        assertThat(requiredBiosUnavailableMessage(RequiredBiosState.Ready)).isEmpty()
    }

    // ── coordinator launch-state machine (Android SavePreLaunchState parity) ─────────────

    @Test
    fun `beginGameLaunch publishes staging and rejects duplicate entry`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        assertThat(c.gameLaunchState.value).isNull()

        assertThat(c.beginGameLaunch(ROM_ID)).isTrue()
        val staging = c.gameLaunchState.value!!
        assertThat(staging.romId).isEqualTo(ROM_ID)
        assertThat(staging.isStaging).isTrue()
        assertThat(staging.isAuthExpired).isFalse()

        // Duplicate-entry guard (Android nativeLibraryOnPlay parity): repeated Play clicks
        // cannot restart the pipeline or reset the state.
        assertThat(c.beginGameLaunch(ROM_ID)).isFalse()
        assertThat(c.gameLaunchState.value).isEqualTo(staging)
    }

    @Test
    fun `finishGameLaunch clears staging on a successful launch`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        c.beginGameLaunch(ROM_ID)

        c.finishGameLaunch(ROM_ID, PlayerLaunchResult.Started("session-1"))

        assertThat(c.gameLaunchState.value).isNull()
    }

    @Test
    fun `finishGameLaunch surfaces auth expiry and clears other failures`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())

        // Auth-expired failure → the "Session expired" + Log in state (staging cleared).
        c.beginGameLaunch(ROM_ID)
        c.finishGameLaunch(
            ROM_ID,
            PlayerLaunchResult.Failed("Session expired; log in again to configure the SEGA CD BIOS."),
        )
        val authExpired = c.gameLaunchState.value!!
        assertThat(authExpired.isAuthExpired).isTrue()
        assertThat(authExpired.isStaging).isFalse()

        // "Dismiss" clears it.
        c.dismissGameLaunchState()
        assertThat(c.gameLaunchState.value).isNull()

        // A non-auth failure clears the state entirely (the screen shows the reason via
        // PlayerLaunchResult.Failed under the Play button instead).
        c.beginGameLaunch(ROM_ID)
        c.finishGameLaunch(
            ROM_ID,
            PlayerLaunchResult.Failed("SEGA CD requires a BIOS (bios_CD_U.bin). Configure it in System Settings."),
        )
        assertThat(c.gameLaunchState.value).isNull()
    }

    @Test
    fun `launch state is scoped by rom id (matchesScope)`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        c.beginGameLaunch(ROM_ID)
        val state = c.gameLaunchState.value!!

        assertThat(state.matchesScope(ROM_ID)).isTrue()
        assertThat(state.matchesScope(ROM_ID + 1)).isFalse()

        // A finish for a different ROM must not clear this ROM's staging state.
        c.finishGameLaunch(ROM_ID + 1, PlayerLaunchResult.Started("other"))
        assertThat(c.gameLaunchState.value).isEqualTo(state)
    }

    @Test
    fun `openLogin clears the launch state and routes to onboarding`(@TempDir dir: Path) {
        val c = coordinator(dir.testRoot())
        c.appMode = AppMode.MAIN
        c.beginGameLaunch(ROM_ID)

        c.openLogin()

        assertThat(c.gameLaunchState.value).isNull()
        assertThat(c.appMode).isEqualTo(AppMode.ONBOARDING)
    }

    // ── end-to-end: expired session → auth-expired state (SEGA CD BIOS path) ─────────────

    @Test
    fun `a segacd launch against an expired session ends in the auth-expired state`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        installGenesisPlusGx(paths)
        val server = StubServer().apply { start() }
        try {
            // 401 on the platform lookup → the BIOS catalog fetch reports AuthExpired.
            server.platformsStatus = 401
            val launcher = FakePlayerProcessLauncher()
            val supervisor = LaunchJournalSupervisor(
                journalsRoot = paths.stateDir.resolve("journals"),
                launcher = launcher,
            )
            val c = DesktopAppCoordinator(
                paths = paths,
                secretBackend = FakeSecretBackend(),
                appVersion = "test",
                buildDefaultOrigin = server.origin,
                playerSupervisorOverride = supervisor,
                romDetailLookup = { testRom("segacd") },
                romContentStagerOverride = FakeRomContentStager(),
            )
            c.settingsStore.write(mapOf(SettingsKeys.ORIGIN to server.origin))

            // The detail screen's Play flow: begin (staging) → launchPlayer → finish.
            assertThat(c.beginGameLaunch(ROM_ID)).isTrue()
            val result = c.launchPlayer(romId = ROM_ID)
            c.finishGameLaunch(ROM_ID, result)

            assertThat(result).isEqualTo(
                PlayerLaunchResult.Failed("Session expired; log in again to configure the SEGA CD BIOS."),
            )
            // The screen now renders the "Session expired" + Log in state.
            val state = c.gameLaunchState.value!!
            assertThat(state.matchesScope(ROM_ID)).isTrue()
            assertThat(state.isAuthExpired).isTrue()
            assertThat(state.isStaging).isFalse()
            assertThat(launcher.launches).isEmpty() // fail-closed: no player without a valid session
        } finally {
            server.close()
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────

    private fun testRom(platformSlug: String, fileName: String = "game.chd"): RomDetail = RomDetail(
        id = ROM_ID,
        title = "Test Game",
        platformDisplayName = "Sega CD",
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

    /** Installs `libgenesis_plus_gx.so` so the approved segacd core resolves to a real-content core. */
    private fun installGenesisPlusGx(paths: AppPaths) {
        val coresDir = paths.dataDir.resolve("cores")
        Files.createDirectories(coresDir)
        Files.write(coresDir.resolve("libgenesis_plus_gx.so"), byteArrayOf(0))
    }

    /** Minimal coordinator for the state-machine tests (no launch happens in most of them). */
    private fun coordinator(paths: AppPaths): DesktopAppCoordinator = DesktopAppCoordinator(
        paths = paths,
        secretBackend = FakeSecretBackend(),
        appVersion = "test",
        buildDefaultOrigin = "https://demo.romm.app",
    )

    private fun Path.testRoot(): AppPaths = TestAppPaths(this)
}

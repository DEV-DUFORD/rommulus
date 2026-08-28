package com.romm.desktop.ui.screens.detail

import com.romm.androidtv.library.RomDetail
import com.romm.androidtv.library.SiblingRomInfo
import com.romm.androidtv.onboarding.OnboardingRoutingDecision.AppMode
import com.romm.androidtv.storage.AppPaths
import com.romm.androidtv.storage.TestAppPaths
import com.romm.desktop.DesktopAppCoordinator
import com.romm.desktop.Screen
import com.romm.desktop.storage.secret.FakeSecretBackend
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Focused tests for the desktop version picker ("Choose File" — Android parity): the pure
 * entry-building logic (current-version check, default badge, per-file names), the button's
 * sibling gating, and the selection re-scope through [DesktopAppCoordinator.openGameDetail]
 * (picking a row re-scopes the detail screen to that ROM; no auto-launch).
 */
@DisplayName("VersionPicker — Choose Game File")
class GameDetailVersionPickerTest {

    private fun rom(
        id: Long = 10L,
        fileName: String = "Ape Escape",
        siblings: List<SiblingRomInfo> = emptyList(),
    ) = RomDetail(
        id = id,
        title = "Ape Escape",
        platformDisplayName = "PlayStation",
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
        fileSizeBytes = 1024L,
        lastPlayedIso = null,
        nowPlaying = false,
        fileName = fileName,
        siblingRoms = siblings,
    )

    private fun sibling(id: Long, fileName: String, isMainSibling: Boolean = false) = SiblingRomInfo(
        id = id,
        title = "Ape Escape",
        fileName = fileName,
        isMainSibling = isMainSibling,
    )

    @Nested
    inner class BuildVersionPickerEntries {

        @Test
        fun `current rom leads and is checked, siblings follow unchecked`() {
            val entries = buildVersionPickerEntries(
                rom(siblings = listOf(sibling(20L, "Ape Escape (Disc 1)"), sibling(30L, "Ape Escape (Disc 2)"))),
            )
            assertThat(entries.map { it.romId }).containsExactly(10L, 20L, 30L)
            assertThat(entries.map { it.isCurrentVersion }).containsExactly(true, false, false)
        }

        @Test
        fun `per-file names are kept so tags like (Disc 1) distinguish versions`() {
            val entries = buildVersionPickerEntries(rom(siblings = listOf(sibling(20L, "Ape Escape (Disc 1)"))))
            assertThat(entries.map { it.fileName }).containsExactly("Ape Escape", "Ape Escape (Disc 1)")
        }

        @Test
        fun `blank file names fall back to the shared title`() {
            val entries = buildVersionPickerEntries(rom(fileName = "", siblings = listOf(sibling(20L, ""))))
            assertThat(entries.map { it.fileName }).containsExactly("Ape Escape", "Ape Escape")
        }

        @Test
        fun `default badge stays on the marked main sibling`() {
            val entries = buildVersionPickerEntries(
                rom(siblings = listOf(
                    sibling(20L, "Ape Escape (Disc 1)"),
                    sibling(30L, "Ape Escape (Disc 2)", isMainSibling = true),
                )),
            )
            assertThat(entries.map { it.isMainSibling }).containsExactly(false, false, true)
        }

        @Test
        fun `current rom takes the default badge when no sibling is marked`() {
            val entries = buildVersionPickerEntries(rom(siblings = listOf(sibling(20L, "Ape Escape (Disc 1)"))))
            assertThat(entries.map { it.isMainSibling }).containsExactly(true, false)
        }

        @Test
        fun `no siblings - single checked entry that is also the default`() {
            val entries = buildVersionPickerEntries(rom())
            assertThat(entries).hasSize(1)
            assertThat(entries[0].isCurrentVersion).isTrue()
            assertThat(entries[0].isMainSibling).isTrue()
        }
    }

    @Nested
    inner class ChooseFileButtonGating {

        @Test
        fun `hidden when the rom has no sibling versions`() {
            assertThat(shouldShowChooseFileButton(rom())).isFalse()
        }

        @Test
        fun `shown when the rom has at least one sibling version`() {
            val rom = rom(siblings = listOf(sibling(20L, "Ape Escape (Disc 1)")))
            assertThat(shouldShowChooseFileButton(rom)).isTrue()
        }
    }

    @Nested
    inner class SelectionRescopesDetail {

        private fun coordinator(dir: Path) = DesktopAppCoordinator(
            paths = dir.testRoot(),
            secretBackend = FakeSecretBackend(),
            appVersion = "test",
            buildDefaultOrigin = "https://demo.romm.app",
        )

        @Test
        fun `picking a sibling re-scopes detail to that rom and keeps the parent`(@TempDir dir: Path) {
            val c = coordinator(dir)
            c.appMode = AppMode.MAIN
            c.openGameDetail(romId = 10L, parent = Screen.PLATFORM_DETAIL)

            // The overlay wires each row's onSelect to openGameDetail(entry.romId, gameDetailParent).
            val entries = buildVersionPickerEntries(
                rom(siblings = listOf(sibling(20L, "Ape Escape (Disc 1)"))),
            )
            val picked = entries.first { it.romId == 20L }
            c.openGameDetail(picked.romId, c.gameDetailParent)

            assertThat(c.selectedRomId).isEqualTo(20L)
            assertThat(c.currentScreen).isEqualTo(Screen.GAME_DETAIL)
            assertThat(c.gameDetailParent).isEqualTo(Screen.PLATFORM_DETAIL)
        }

        @Test
        fun `back after a re-scope still returns to the original parent`(@TempDir dir: Path) {
            val c = coordinator(dir)
            c.appMode = AppMode.MAIN
            c.openGameDetail(romId = 10L, parent = Screen.PLATFORM_DETAIL)

            val picked = buildVersionPickerEntries(
                rom(siblings = listOf(sibling(20L, "Ape Escape (Disc 1)"))),
            ).first { it.romId == 20L }
            c.openGameDetail(picked.romId, c.gameDetailParent)
            c.onBack()

            assertThat(c.currentScreen).isEqualTo(Screen.PLATFORM_DETAIL)
        }
    }

    private fun Path.testRoot(): AppPaths = TestAppPaths(this)
}

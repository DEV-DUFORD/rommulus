package com.romm.desktop.ui.screens.library

import com.romm.androidtv.library.LibraryRom
import com.romm.androidtv.library.PlatformSummary
import com.romm.androidtv.library.RomQuery
import com.romm.androidtv.library.SearchUiState
import com.romm.androidtv.library.SectionState
import com.romm.androidtv.romm.RommApiError
import com.romm.desktop.Screen
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure, framework-free logic shared by the desktop Home /
 * RomGrid / Search screens (Phase 6). Composables themselves are UI-only and
 * covered by integration tests later.
 */
class LibraryScreenLogicTest {

    private fun rom(id: Long) = LibraryRom(
        id = id,
        title = "Game $id",
        platformDisplayName = "NES",
        coverUrl = null,
        lastPlayedIso = null,
        nowPlaying = false,
    )

    @Nested
    inner class SectionDisplayStateMapping {

        @Test
        fun `loading maps to LOADING`() {
            val state: SectionState<List<LibraryRom>> = SectionState.Loading
            assertThat(sectionDisplayState(state)).isEqualTo(SectionDisplayState.LOADING)
        }

        @Test
        fun `error maps to ERROR`() {
            val state: SectionState<List<LibraryRom>> = SectionState.Error(RommApiError.NETWORK_ERROR)
            assertThat(sectionDisplayState(state)).isEqualTo(SectionDisplayState.ERROR)
        }

        @Test
        fun `loaded with items maps to CONTENT`() {
            val state: SectionState<List<LibraryRom>> = SectionState.Loaded(listOf(rom(1L), rom(2L)))
            assertThat(sectionDisplayState(state)).isEqualTo(SectionDisplayState.CONTENT)
        }

        @Test
        fun `loaded empty maps to EMPTY so the shelf is omitted`() {
            val state: SectionState<List<LibraryRom>> = SectionState.Loaded(emptyList())
            assertThat(sectionDisplayState(state)).isEqualTo(SectionDisplayState.EMPTY)
        }
    }

    @Nested
    inner class ShouldLoadMoreOnScrollEnd {

        @Test
        fun `never triggers when nothing is loaded`() {
            assertThat(shouldLoadMoreOnScrollEnd(lastVisibleIndex = 0, itemCount = 0)).isFalse()
        }

        @Test
        fun `triggers once the last visible index reaches itemCount minus threshold`() {
            // Default threshold is 6: with 10 items, index 4 (10 - 6) triggers.
            assertThat(shouldLoadMoreOnScrollEnd(lastVisibleIndex = 4, itemCount = 10)).isTrue()
        }

        @Test
        fun `does not trigger before the threshold window`() {
            assertThat(shouldLoadMoreOnScrollEnd(lastVisibleIndex = 3, itemCount = 10)).isFalse()
        }

        @Test
        fun `triggers immediately when the list fits on one screen`() {
            // 5 items with default threshold 6: lastVisible 0 >= 5 - 6 = -1.
            assertThat(shouldLoadMoreOnScrollEnd(lastVisibleIndex = 0, itemCount = 5)).isTrue()
        }

        @Test
        fun `triggers at the exact boundary for large lists`() {
            // 40 items: 40 - 6 = 34 is the first triggering index.
            assertThat(shouldLoadMoreOnScrollEnd(lastVisibleIndex = 34, itemCount = 40)).isTrue()
            assertThat(shouldLoadMoreOnScrollEnd(lastVisibleIndex = 33, itemCount = 40)).isFalse()
        }

        @Test
        fun `threshold of one triggers only on the last item`() {
            assertThat(shouldLoadMoreOnScrollEnd(lastVisibleIndex = 9, itemCount = 10, threshold = 1)).isTrue()
            assertThat(shouldLoadMoreOnScrollEnd(lastVisibleIndex = 8, itemCount = 10, threshold = 1)).isFalse()
        }

        @Test
        fun `threshold of zero is an empty window and never triggers`() {
            assertThat(shouldLoadMoreOnScrollEnd(lastVisibleIndex = 9, itemCount = 10, threshold = 0)).isFalse()
        }
    }

    @Nested
    inner class RomQueryForSelection {

        @Test
        fun `platform selection builds ByPlatform`() {
            assertThat(romQueryForSelection(platformId = 7L, collectionId = null))
                .isEqualTo(RomQuery.ByPlatform(7L))
        }

        @Test
        fun `collection selection builds ByCollection`() {
            assertThat(romQueryForSelection(platformId = null, collectionId = 9L))
                .isEqualTo(RomQuery.ByCollection(9L))
        }

        @Test
        fun `platform wins when both are selected`() {
            assertThat(romQueryForSelection(platformId = 7L, collectionId = 9L))
                .isEqualTo(RomQuery.ByPlatform(7L))
        }

        @Test
        fun `no selection returns null`() {
            assertThat(romQueryForSelection(platformId = null, collectionId = null)).isNull()
        }
    }

    @Nested
    inner class GridParentScreen {

        @Test
        fun `ByPlatform returns PLATFORM_DETAIL`() {
            assertThat(gridParentScreen(RomQuery.ByPlatform(1L))).isEqualTo(Screen.PLATFORM_DETAIL)
        }

        @Test
        fun `ByCollection returns COLLECTION_DETAIL`() {
            assertThat(gridParentScreen(RomQuery.ByCollection(2L))).isEqualTo(Screen.COLLECTION_DETAIL)
        }

        @Test
        fun `non-grid queries fall back to HOME`() {
            assertThat(gridParentScreen(RomQuery.Search("zelda"))).isEqualTo(Screen.HOME)
            assertThat(gridParentScreen(RomQuery.RecentlyAdded)).isEqualTo(Screen.HOME)
            assertThat(gridParentScreen(RomQuery.ContinuePlaying)).isEqualTo(Screen.HOME)
            assertThat(gridParentScreen(RomQuery.Favorites)).isEqualTo(Screen.HOME)
        }
    }

    @Nested
    inner class PlatformTileImageUrl {

        @Test
        fun `prefers the first bundled glyph candidate`() {
            val platform = PlatformSummary(
                id = 1L,
                displayName = "NES",
                romCount = 10,
                logoUrl = "https://igdb.example/logo.png",
                iconUrl = "https://romm.example/assets/platforms/nestv.svg",
                iconUrlCandidates = listOf(
                    "https://romm.example/assets/platforms/nestv.svg",
                    "https://romm.example/assets/platforms/nestv.ico",
                ),
                slug = "nestv",
            )
            assertThat(platformTileImageUrl(platform))
                .isEqualTo("https://romm.example/assets/platforms/nestv.svg")
        }

        @Test
        fun `skips blank candidates and falls back to the logo`() {
            val platform = PlatformSummary(
                id = 2L,
                displayName = "Sega CD",
                romCount = 5,
                logoUrl = "https://igdb.example/sega-cd.png",
                iconUrl = null,
                iconUrlCandidates = listOf("   "),
                slug = "sega_cd",
            )
            assertThat(platformTileImageUrl(platform)).isEqualTo("https://igdb.example/sega-cd.png")
        }

        @Test
        fun `returns null when no candidate and no logo`() {
            val platform = PlatformSummary(
                id = 3L,
                displayName = "Mystery",
                romCount = 1,
                logoUrl = null,
                iconUrl = null,
                iconUrlCandidates = emptyList(),
                slug = "",
            )
            assertThat(platformTileImageUrl(platform)).isNull()
        }
    }

    @Nested
    inner class SearchResultCount {

        @Test
        fun `hide-unsupported on shows the visible count`() {
            val state = SearchUiState(
                roms = listOf(rom(1L)),
                total = 42,
                hideUnsupportedSystems = true,
            )
            assertThat(searchResultCount(state)).isEqualTo(1)
        }

        @Test
        fun `hide-unsupported off shows the server total`() {
            val state = SearchUiState(
                roms = listOf(rom(1L)),
                total = 42,
                hideUnsupportedSystems = false,
            )
            assertThat(searchResultCount(state)).isEqualTo(42)
        }
    }

    @Nested
    inner class Labels {

        @Test
        fun `result count label is singular for one`() {
            assertThat(resultCountLabel(1)).isEqualTo("1 result")
        }

        @Test
        fun `result count label is plural otherwise`() {
            assertThat(resultCountLabel(0)).isEqualTo("0 results")
            assertThat(resultCountLabel(42)).isEqualTo("42 results")
        }

        @Test
        fun `error message lowercases and spaces the enum name`() {
            assertThat(errorMessage(RommApiError.SERVER_ERROR)).isEqualTo("server error")
            assertThat(errorMessage(RommApiError.AUTH_EXPIRED)).isEqualTo("auth expired")
            assertThat(errorMessage(RommApiError.NETWORK_ERROR)).isEqualTo("network error")
        }
    }
}

package com.romm.androidtv.library

import com.romm.androidtv.romm.RommApiError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomePresenterDuplicateItemsTest {

    private val rom = LibraryRom(
        id = 7,
        title = "Duplicate",
        platformDisplayName = "Game Boy",
        coverUrl = null,
        lastPlayedIso = null,
        nowPlaying = false,
    )
    private val platform = PlatformSummary(
        id = 8,
        displayName = "Game Boy",
        romCount = 2,
        logoUrl = null,
    )
    private val collection = CollectionSummary(
        id = 9,
        name = "Favorites",
        romCount = 2,
        coverUrl = null,
    )

    @Test
    fun `duplicate server rows are removed before Home renders keyed lists`() {
        val viewModel = HomePresenter(
            scope = TestScope(UnconfinedTestDispatcher()),
            repository = DuplicateRepository(),
            hideUnsupportedSystems = { false },
        )

        val state = viewModel.uiState.value
        assertThat((state.continuePlaying as SectionState.Loaded).data).containsExactly(rom)
        assertThat((state.recentlyAdded as SectionState.Loaded).data).containsExactly(rom)
        assertThat((state.favorites as SectionState.Loaded).data).containsExactly(rom)
        assertThat((state.platforms as SectionState.Loaded).data).containsExactly(platform)
        assertThat((state.collections as SectionState.Loaded).data).containsExactly(collection)
    }

    @Test
    fun `section retry also removes duplicate server rows`() {
        val viewModel = HomePresenter(
            scope = TestScope(UnconfinedTestDispatcher()),
            repository = DuplicateRepository(),
            hideUnsupportedSystems = { false },
        )

        viewModel.retryRecentlyAdded()

        val state = viewModel.uiState.value
        assertThat((state.recentlyAdded as SectionState.Loaded).data).containsExactly(rom)
    }

    private inner class DuplicateRepository : LibraryRepository {
        override suspend fun fetchRecentlyAdded(limit: Int) = LibraryResult.Success(listOf(rom, rom))
        override suspend fun fetchContinuePlaying(limit: Int) = LibraryResult.Success(listOf(rom, rom))
        override suspend fun fetchFavorites(limit: Int) = LibraryResult.Success(listOf(rom, rom))
        override suspend fun fetchPlatforms() = LibraryResult.Success(listOf(platform, platform))
        override suspend fun fetchCollections() = LibraryResult.Success(listOf(collection, collection))
        override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int) =
            LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchRomDetail(romId: Long) =
            LibraryResult.Failure(RommApiError.NETWORK_ERROR)
    }
}

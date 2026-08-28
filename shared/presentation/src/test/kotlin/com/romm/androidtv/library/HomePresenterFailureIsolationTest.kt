package com.romm.androidtv.library

import com.romm.androidtv.romm.RommApiError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomePresenterFailureIsolationTest {

    @Test
    fun `unexpected section exception becomes error without cancelling siblings`() {
        val presenter = HomePresenter(
            scope = TestScope(UnconfinedTestDispatcher()),
            repository = ThrowingRecentlyAddedRepository(),
        )

        assertThat(presenter.uiState.value.recentlyAdded)
            .isEqualTo(SectionState.Error(RommApiError.SERVER_ERROR))
        assertThat(presenter.uiState.value.continuePlaying).isInstanceOf(SectionState.Loaded::class.java)
        assertThat(presenter.uiState.value.favorites).isInstanceOf(SectionState.Loaded::class.java)
        assertThat(presenter.uiState.value.platforms).isInstanceOf(SectionState.Loaded::class.java)
        assertThat(presenter.uiState.value.collections).isInstanceOf(SectionState.Loaded::class.java)
    }

    @Test
    fun `unexpected retry exception becomes that section error`() {
        val repository = ThrowingRecentlyAddedRepository()
        val presenter = HomePresenter(
            scope = TestScope(UnconfinedTestDispatcher()),
            repository = repository,
        )

        presenter.retryRecentlyAdded()

        assertThat(presenter.uiState.value.recentlyAdded)
            .isEqualTo(SectionState.Error(RommApiError.SERVER_ERROR))
    }

    private class ThrowingRecentlyAddedRepository : LibraryRepository {
        override suspend fun fetchRecentlyAdded(limit: Int): LibraryResult<List<LibraryRom>> =
            error("unexpected")

        override suspend fun fetchContinuePlaying(limit: Int) =
            LibraryResult.Success(emptyList<LibraryRom>())

        override suspend fun fetchFavorites(limit: Int) =
            LibraryResult.Success(emptyList<LibraryRom>())

        override suspend fun fetchPlatforms() =
            LibraryResult.Success(emptyList<PlatformSummary>())

        override suspend fun fetchCollections() =
            LibraryResult.Success(emptyList<CollectionSummary>())

        override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int) =
            LibraryResult.Failure(RommApiError.NETWORK_ERROR)

        override suspend fun fetchRomDetail(romId: Long) =
            LibraryResult.Failure(RommApiError.NETWORK_ERROR)
    }
}

package com.romm.androidtv.library

import com.romm.androidtv.romm.RommApiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * JVM unit tests for [RomGridViewModel] reactive refresh driven by the
 * hideUnsupportedSystems preference flow. Verifies that toggling the setting
 * from Settings causes platform/collection grids to re-fetch immediately.
 */
@DisplayName("RomGridViewModel — toggle-driven reactive refresh")
class RomGridViewModelToggleRefreshTest {

    private lateinit var testJob: Job
    private lateinit var testScope: CoroutineScope

    @BeforeEach
    fun setUp() {
        testJob = Job()
        testScope = CoroutineScope(Dispatchers.Unconfined + testJob)
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        testJob.cancel()
        Dispatchers.resetMain()
    }

    private class CountingMockRepository : LibraryRepository {
        var fetchCount = 0
        private val pageResults: MutableList<RomPage> = mutableListOf()

        fun enqueue(page: RomPage) = pageResults.add(page)

        override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int): LibraryResult<RomPage> {
            fetchCount++
            return if (pageResults.isNotEmpty()) {
                LibraryResult.Success(pageResults.removeAt(0))
            } else {
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            }
        }

        override suspend fun fetchRecentlyAdded(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchContinuePlaying(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchFavorites(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchPlatforms() = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchCollections() = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchRomDetail(romId: Long) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
    }

    private fun makeSupportedRom(id: Long): LibraryRom =
        LibraryRom(id = id, title = "Game $id", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false)

    private fun makeUnsupportedRom(id: Long): LibraryRom =
        LibraryRom(id = id, title = "Game $id", platformDisplayName = "PSP", platformSlug = "psp", coverUrl = null, lastPlayedIso = null, nowPlaying = false)

    @Test
    fun `OFF to ON toggle triggers grid refresh`() {
        val repo = CountingMockRepository()
        val preferenceFlow = MutableStateFlow(false)

        // Enqueue enough pages for initial + 2 toggles.
        repeat(3) {
            repo.enqueue(RomPage(listOf(makeSupportedRom(1), makeUnsupportedRom(2)), total = 10))
        }

        val vm = RomGridViewModel(
            repository = repo,
            query = RomQuery.ByPlatform(42L),
            hideUnsupportedSystems = { preferenceFlow.value },
            hideUnsupportedSystemsFlow = preferenceFlow,
        )

        // After init: 1 fetch.
        assertThat(repo.fetchCount).isEqualTo(1)
        val beforeToggle = repo.fetchCount

        // Toggle ON.
        preferenceFlow.value = true

        // Should trigger another fetch.
        assertThat(repo.fetchCount).isEqualTo(beforeToggle + 1)
    }

    @Test
    fun `ON to OFF toggle triggers grid refresh`() {
        val repo = CountingMockRepository()
        val preferenceFlow = MutableStateFlow(true)

        repeat(3) {
            repo.enqueue(RomPage(listOf(makeSupportedRom(1), makeUnsupportedRom(2)), total = 10))
        }

        val vm = RomGridViewModel(
            repository = repo,
            query = RomQuery.ByPlatform(42L),
            hideUnsupportedSystems = { preferenceFlow.value },
            hideUnsupportedSystemsFlow = preferenceFlow,
        )

        assertThat(repo.fetchCount).isEqualTo(1)
        val beforeToggle = repo.fetchCount

        // Toggle OFF.
        preferenceFlow.value = false

        assertThat(repo.fetchCount).isEqualTo(beforeToggle + 1)
    }

    @Test
    fun `refresh applies current filter to newly fetched data`() {
        val repo = CountingMockRepository()
        val preferenceFlow = MutableStateFlow(false)

        // Initial fetch: hide=false, return both supported and unsupported.
        repo.enqueue(RomPage(listOf(makeSupportedRom(1), makeUnsupportedRom(2)), total = 2))
        // After toggle ON: refetch with hide=true, return filtered data (simulating server-side filter).
        repo.enqueue(RomPage(listOf(makeSupportedRom(1)), total = 2))
        // After toggle OFF: refetch with hide=false, return all again.
        repo.enqueue(RomPage(listOf(makeSupportedRom(1), makeUnsupportedRom(2)), total = 2))

        val vm = RomGridViewModel(
            repository = repo,
            query = RomQuery.ByPlatform(42L),
            hideUnsupportedSystems = { preferenceFlow.value },
            hideUnsupportedSystemsFlow = preferenceFlow,
        )

        // Initial: hide=false → both items visible.
        var state = vm.uiState.value
        val initialLoaded = state.section as SectionState.Loaded
        assertThat(initialLoaded.data).hasSize(2)

        // Toggle ON: refetch + filter removes unsupported roms.
        preferenceFlow.value = true
        state = vm.uiState.value
        val filteredLoaded = state.section as SectionState.Loaded
        assertThat(filteredLoaded.data).hasSize(1)
        assertThat(filteredLoaded.data[0].title).isEqualTo("Game 1")

        // Toggle OFF: refetch + all items visible again.
        preferenceFlow.value = false
        state = vm.uiState.value
        val restoredLoaded = state.section as SectionState.Loaded
        assertThat(restoredLoaded.data).hasSize(2)
    }

    @Test
    fun `null flow does not break initialization`() {
        val repo = CountingMockRepository()
        repo.enqueue(RomPage(listOf(makeSupportedRom(1)), total = 1))

        val vm = RomGridViewModel(
            repository = repo,
            query = RomQuery.ByPlatform(42L),
            hideUnsupportedSystems = { false },
            hideUnsupportedSystemsFlow = null,
        )

        assertThat(repo.fetchCount).isEqualTo(1)
    }
}

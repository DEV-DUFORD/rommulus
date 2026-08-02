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
 * JVM unit tests for [SearchViewModel] reactive refresh driven by the
 * hideUnsupportedSystems preference flow. Verifies that toggling the setting
 * from Settings causes Search to re-execute its active query immediately,
 * and that idle (no active query) searches are unaffected.
 */
@DisplayName("SearchViewModel — toggle-driven reactive refresh")
class SearchViewModelToggleRefreshTest {

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

    /** Mock repository that records every query term and offset passed to fetchRomsPage. */
    private class RecordingMockRepository : LibraryRepository {
        val queries = mutableListOf<Pair<String, Int>>()
        private val pageResults: MutableList<RomPage> = mutableListOf()

        fun enqueue(page: RomPage) = pageResults.add(page)

        override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int): LibraryResult<RomPage> {
            if (query is RomQuery.Search) {
                queries.add(query.term to offset)
            }
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
    fun `OFF to ON toggle re-executes active search with new filter`() {
        val repo = RecordingMockRepository()
        val preferenceFlow = MutableStateFlow(false)

        // Enqueue pages for initial search + refresh.
        repo.enqueue(RomPage(listOf(makeSupportedRom(1), makeUnsupportedRom(2)), total = 2))
        repo.enqueue(RomPage(listOf(makeSupportedRom(1)), total = 2))

        val vm = SearchViewModel(
            repository = repo,
            testScope = testScope,
            hideUnsupportedSystems = { preferenceFlow.value },
            hideUnsupportedSystemsFlow = preferenceFlow,
        )

        // Execute a search using onQueryChanged + submitQuery to bypass debounce.
        vm.onQueryChanged("test")
        vm.submitQuery()
        assertThat(repo.queries).containsExactly("test" to 0)
        val beforeToggle = repo.queries.size

        // Toggle ON from Settings while search results are displayed.
        preferenceFlow.value = true

        // Should re-execute the same query from offset 0.
        assertThat(repo.queries.size).isEqualTo(beforeToggle + 1)
        assertThat(repo.queries.last()).isEqualTo("test" to 0)
    }

    @Test
    fun `ON to OFF toggle re-executes active search`() {
        val repo = RecordingMockRepository()
        val preferenceFlow = MutableStateFlow(true)

        repo.enqueue(RomPage(listOf(makeSupportedRom(1)), total = 2))
        repo.enqueue(RomPage(listOf(makeSupportedRom(1), makeUnsupportedRom(2)), total = 2))

        val vm = SearchViewModel(
            repository = repo,
            testScope = testScope,
            hideUnsupportedSystems = { preferenceFlow.value },
            hideUnsupportedSystemsFlow = preferenceFlow,
        )

        vm.onQueryChanged("test")
        vm.submitQuery()
        assertThat(repo.queries).containsExactly("test" to 0)
        val beforeToggle = repo.queries.size

        // Toggle OFF from Settings.
        preferenceFlow.value = false

        assertThat(repo.queries.size).isEqualTo(beforeToggle + 1)
        assertThat(repo.queries.last()).isEqualTo("test" to 0)
    }

    @Test
    fun `toggle on idle search (no active query) is a no-op`() {
        val repo = RecordingMockRepository()
        val preferenceFlow = MutableStateFlow(false)

        val vm = SearchViewModel(
            repository = repo,
            testScope = testScope,
            hideUnsupportedSystems = { preferenceFlow.value },
            hideUnsupportedSystemsFlow = preferenceFlow,
        )

        // No search has been executed yet.
        assertThat(repo.queries).isEmpty()

        // Toggle ON → should NOT trigger a search (no active query).
        preferenceFlow.value = true
        assertThat(repo.queries).isEmpty()

        // Toggle OFF → still no-op.
        preferenceFlow.value = false
        assertThat(repo.queries).isEmpty()
    }

    @Test
    fun `refresh after toggle applies current filter to results`() {
        val repo = RecordingMockRepository()
        val preferenceFlow = MutableStateFlow(false)

        // Initial: hide=false, return both.
        repo.enqueue(RomPage(listOf(makeSupportedRom(1), makeUnsupportedRom(2)), total = 2))
        // After toggle ON: refetch with hide=true.
        repo.enqueue(RomPage(listOf(makeSupportedRom(1)), total = 2))

        val vm = SearchViewModel(
            repository = repo,
            testScope = testScope,
            hideUnsupportedSystems = { preferenceFlow.value },
            hideUnsupportedSystemsFlow = preferenceFlow,
        )

        // Execute search using onQueryChanged + submitQuery to bypass debounce.
        vm.onQueryChanged("test")
        vm.submitQuery()

        // Initial: both items visible.
        var state = vm.uiState.value
        assertThat(state.roms).hasSize(2)

        // Toggle ON: refetch + filter removes unsupported.
        preferenceFlow.value = true
        state = vm.uiState.value
        assertThat(state.roms).hasSize(1)
        assertThat(state.roms[0].title).isEqualTo("Game 1")
    }

    @Test
    fun `null flow does not break initialization`() {
        val repo = RecordingMockRepository()

        val vm = SearchViewModel(
            repository = repo,
            testScope = testScope,
            hideUnsupportedSystems = { false },
            hideUnsupportedSystemsFlow = null,
        )

        // Should be constructable without errors.
        assertThat(vm.uiState.value.query).isEmpty()
    }
}

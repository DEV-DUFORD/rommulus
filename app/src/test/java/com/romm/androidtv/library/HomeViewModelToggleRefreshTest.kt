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
 * JVM unit tests for [HomeViewModel] reactive refresh driven by the
 * hideUnsupportedSystems preference flow. Verifies that toggling the setting
 * from Settings causes Home to re-fetch all sections immediately, without
 * requiring navigation or app restart.
 */
@DisplayName("HomeViewModel — toggle-driven reactive refresh")
class HomeViewModelToggleRefreshTest {

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

    /** Mock repository that counts total fetch invocations. */
    private class CountingMockRepository : LibraryRepository {
        var fetchCount = 0

        override suspend fun fetchRecentlyAdded(limit: Int): LibraryResult<List<LibraryRom>> {
            fetchCount++
            return LibraryResult.Success(emptyList())
        }

        override suspend fun fetchContinuePlaying(limit: Int): LibraryResult<List<LibraryRom>> {
            fetchCount++
            return LibraryResult.Success(emptyList())
        }

        override suspend fun fetchFavorites(limit: Int): LibraryResult<List<LibraryRom>> {
            fetchCount++
            return LibraryResult.Success(emptyList())
        }

        override suspend fun fetchPlatforms(): LibraryResult<List<PlatformSummary>> {
            fetchCount++
            return LibraryResult.Success(emptyList())
        }

        override suspend fun fetchCollections(): LibraryResult<List<CollectionSummary>> {
            fetchCount++
            return LibraryResult.Success(emptyList())
        }

        override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int): LibraryResult<RomPage> {
            fetchCount++
            return LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        }

        override suspend fun fetchRomDetail(romId: Long): LibraryResult<RomDetail> {
            fetchCount++
            return LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        }
    }

    @Test
    fun `OFF to ON toggle triggers full refresh of all sections`() {
        val repo = CountingMockRepository()
        val preferenceFlow = MutableStateFlow(false)

        val vm = HomeViewModel(
            repository = repo,
            hideUnsupportedSystems = { preferenceFlow.value },
            hideUnsupportedSystemsFlow = preferenceFlow,
        )

        // After init: 5 fetches (one per section).
        assertThat(repo.fetchCount).isEqualTo(5)
        val beforeToggle = repo.fetchCount

        // Toggle ON from Settings.
        preferenceFlow.value = true

        // Should trigger another full refresh: +5 fetches.
        assertThat(repo.fetchCount).isEqualTo(beforeToggle + 5)
    }

    @Test
    fun `ON to OFF toggle triggers full refresh of all sections`() {
        val repo = CountingMockRepository()
        val preferenceFlow = MutableStateFlow(true)

        val vm = HomeViewModel(
            repository = repo,
            hideUnsupportedSystems = { preferenceFlow.value },
            hideUnsupportedSystemsFlow = preferenceFlow,
        )

        assertThat(repo.fetchCount).isEqualTo(5)
        val beforeToggle = repo.fetchCount

        // Toggle OFF from Settings.
        preferenceFlow.value = false

        // Should trigger another full refresh: +5 fetches.
        assertThat(repo.fetchCount).isEqualTo(beforeToggle + 5)
    }

    @Test
    fun `rapid toggles each produce independent refreshes`() {
        val repo = CountingMockRepository()
        val preferenceFlow = MutableStateFlow(false)

        val vm = HomeViewModel(
            repository = repo,
            hideUnsupportedSystems = { preferenceFlow.value },
            hideUnsupportedSystemsFlow = preferenceFlow,
        )

        // Initial: 5 fetches.
        assertThat(repo.fetchCount).isEqualTo(5)

        // Toggle ON → OFF → ON (3 emissions after initial).
        preferenceFlow.value = true
        preferenceFlow.value = false
        preferenceFlow.value = true

        // Each toggle produces a full refresh: 5 + 3*5 = 20.
        assertThat(repo.fetchCount).isEqualTo(20)
    }

    @Test
    fun `refresh uses the current preference value at fetch time`() {
        val supportedRom = LibraryRom(
            id = 1, title = "Pokemon", platformDisplayName = "Game Boy",
            platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false,
        )
        val unsupportedRom = LibraryRom(
            id = 2, title = "Chrono Trigger", platformDisplayName = "N64",
            platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false,
        )

        val mockRepo = object : LibraryRepository {
            override suspend fun fetchRecentlyAdded(limit: Int) = LibraryResult.Success(listOf(supportedRom, unsupportedRom))
            override suspend fun fetchContinuePlaying(limit: Int) = LibraryResult.Success(listOf(supportedRom, unsupportedRom))
            override suspend fun fetchFavorites(limit: Int) = LibraryResult.Success(emptyList<LibraryRom>())
            override suspend fun fetchPlatforms() = LibraryResult.Success(emptyList<PlatformSummary>())
            override suspend fun fetchCollections() = LibraryResult.Success(emptyList<CollectionSummary>())
            override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchRomDetail(romId: Long) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        }

        val preferenceFlow = MutableStateFlow(false)

        val vm = HomeViewModel(
            repository = mockRepo,
            hideUnsupportedSystems = { preferenceFlow.value },
            hideUnsupportedSystemsFlow = preferenceFlow,
        )

        // Initial: hide=false → all items visible.
        var state = vm.uiState.value
        val cp1 = state.continuePlaying as SectionState.Loaded<List<LibraryRom>>
        assertThat(cp1.data).hasSize(2)

        // Toggle ON: refetch + filter removes unsupported.
        preferenceFlow.value = true
        state = vm.uiState.value
        val cp2 = state.continuePlaying as SectionState.Loaded<List<LibraryRom>>
        assertThat(cp2.data).hasSize(1)
        assertThat(cp2.data[0].title).isEqualTo("Pokemon")

        // Toggle OFF: refetch + no filter → all items visible again.
        preferenceFlow.value = false
        state = vm.uiState.value
        val cp3 = state.continuePlaying as SectionState.Loaded<List<LibraryRom>>
        assertThat(cp3.data).hasSize(2)
    }

    @Test
    fun `null flow does not break initialization`() {
        val repo = CountingMockRepository()

        val vm = HomeViewModel(
            repository = repo,
            hideUnsupportedSystems = { false },
            hideUnsupportedSystemsFlow = null,
        )

        // Should still fetch once (5 sections).
        assertThat(repo.fetchCount).isEqualTo(5)
    }
}

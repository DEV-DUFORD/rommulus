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
 * JVM unit tests for platform-card filtering in [HomeViewModel] driven by the
 * hideUnsupportedSystems preference. Verifies that unsupported platforms are
 * hidden from the Platforms grid when the toggle is ON, and restored when OFF.
 */
@DisplayName("HomeViewModel — platform filtering by native support")
class HomeViewModelPlatformFilteringTest {

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

    private val supportedPlatform = PlatformSummary(
        id = 1, displayName = "Game Boy", romCount = 50, logoUrl = null, slug = "gb",
    )
    private val unsupportedPlatform = PlatformSummary(
        id = 2, displayName = "PSP", romCount = 30, logoUrl = null, slug = "psp",
    )
    private val blankSlugPlatform = PlatformSummary(
        id = 3, displayName = "Unknown", romCount = 5, logoUrl = null, slug = "",
    )

    private fun emptyMockRepo(platforms: List<PlatformSummary>): LibraryRepository = object : LibraryRepository {
        override suspend fun fetchContinuePlaying(limit: Int) = LibraryResult.Success(emptyList<LibraryRom>())
        override suspend fun fetchRecentlyAdded(limit: Int) = LibraryResult.Success(emptyList<LibraryRom>())
        override suspend fun fetchFavorites(limit: Int) = LibraryResult.Success(emptyList<LibraryRom>())
        override suspend fun fetchPlatforms() = LibraryResult.Success(platforms)
        override suspend fun fetchCollections() = LibraryResult.Success(emptyList<CollectionSummary>())
        override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int) =
            LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchRomDetail(romId: Long) =
            LibraryResult.Failure(RommApiError.NETWORK_ERROR)
    }

    @Test
    fun `initial load with hide OFF shows all platforms`() {
        val repo = emptyMockRepo(listOf(supportedPlatform, unsupportedPlatform))
        val preferenceFlow = MutableStateFlow(false)

        val vm = HomeViewModel(
            repository = repo,
            hideUnsupportedSystems = { preferenceFlow.value },
            hideUnsupportedSystemsFlow = preferenceFlow,
        )

        val state = vm.uiState.value
        val platforms = state.platforms as SectionState.Loaded<List<PlatformSummary>>
        assertThat(platforms.data).hasSize(2)
        assertThat(platforms.data.map { it.displayName }).containsExactly("Game Boy", "PSP")
    }

    @Test
    fun `initial load with hide ON filters unsupported platforms`() {
        val repo = emptyMockRepo(listOf(supportedPlatform, unsupportedPlatform))
        val preferenceFlow = MutableStateFlow(true)

        val vm = HomeViewModel(
            repository = repo,
            hideUnsupportedSystems = { preferenceFlow.value },
            hideUnsupportedSystemsFlow = preferenceFlow,
        )

        val state = vm.uiState.value
        val platforms = state.platforms as SectionState.Loaded<List<PlatformSummary>>
        assertThat(platforms.data).hasSize(1)
        assertThat(platforms.data[0].displayName).isEqualTo("Game Boy")
    }

    @Test
    fun `initial load with blank slug platform is treated as unsupported`() {
        val repo = emptyMockRepo(listOf(supportedPlatform, blankSlugPlatform))
        val preferenceFlow = MutableStateFlow(true)

        val vm = HomeViewModel(
            repository = repo,
            hideUnsupportedSystems = { preferenceFlow.value },
            hideUnsupportedSystemsFlow = preferenceFlow,
        )

        val state = vm.uiState.value
        val platforms = state.platforms as SectionState.Loaded<List<PlatformSummary>>
        assertThat(platforms.data).hasSize(1)
        assertThat(platforms.data[0].displayName).isEqualTo("Game Boy")
    }

    @Test
    fun `OFF to ON toggle filters out unsupported platforms`() {
        val repo = emptyMockRepo(listOf(supportedPlatform, unsupportedPlatform))
        val preferenceFlow = MutableStateFlow(false)

        val vm = HomeViewModel(
            repository = repo,
            hideUnsupportedSystems = { preferenceFlow.value },
            hideUnsupportedSystemsFlow = preferenceFlow,
        )

        // Initial: both visible.
        var state = vm.uiState.value
        var platforms = state.platforms as SectionState.Loaded<List<PlatformSummary>>
        assertThat(platforms.data).hasSize(2)

        // Toggle ON: unsupported hidden.
        preferenceFlow.value = true
        state = vm.uiState.value
        platforms = state.platforms as SectionState.Loaded<List<PlatformSummary>>
        assertThat(platforms.data).hasSize(1)
        assertThat(platforms.data[0].slug).isEqualTo("gb")
    }

    @Test
    fun `ON to OFF toggle restores unsupported platforms`() {
        val repo = emptyMockRepo(listOf(supportedPlatform, unsupportedPlatform))
        val preferenceFlow = MutableStateFlow(true)

        val vm = HomeViewModel(
            repository = repo,
            hideUnsupportedSystems = { preferenceFlow.value },
            hideUnsupportedSystemsFlow = preferenceFlow,
        )

        // Initial: only supported visible.
        var state = vm.uiState.value
        var platforms = state.platforms as SectionState.Loaded<List<PlatformSummary>>
        assertThat(platforms.data).hasSize(1)

        // Toggle OFF: all restored.
        preferenceFlow.value = false
        state = vm.uiState.value
        platforms = state.platforms as SectionState.Loaded<List<PlatformSummary>>
        assertThat(platforms.data).hasSize(2)
        assertThat(platforms.data.map { it.displayName }).containsExactly("Game Boy", "PSP")
    }

    @Test
    fun `supported platform with zero games is retained when hide ON`() {
        val emptySupportedPlatform = PlatformSummary(
            id = 4, displayName = "GB Color", romCount = 0, logoUrl = null, slug = "gbc",
        )
        val repo = emptyMockRepo(listOf(supportedPlatform, emptySupportedPlatform, unsupportedPlatform))
        val preferenceFlow = MutableStateFlow(true)

        val vm = HomeViewModel(
            repository = repo,
            hideUnsupportedSystems = { preferenceFlow.value },
            hideUnsupportedSystemsFlow = preferenceFlow,
        )

        val state = vm.uiState.value
        val platforms = state.platforms as SectionState.Loaded<List<PlatformSummary>>
        // Both gb and gbc are supported; snes is filtered. Zero romCount does NOT filter.
        assertThat(platforms.data).hasSize(2)
        assertThat(platforms.data.map { it.slug }).containsExactly("gb", "gbc")
    }

    @Test
    fun `platform ordering is preserved after filtering`() {
        val gb = PlatformSummary(id = 1, displayName = "GB", romCount = 10, logoUrl = null, slug = "gb")
        val psp = PlatformSummary(id = 2, displayName = "PSP", romCount = 5, logoUrl = null, slug = "psp")
        val gbc = PlatformSummary(id = 3, displayName = "GBC", romCount = 8, logoUrl = null, slug = "gbc")

        val repo = emptyMockRepo(listOf(psp, gb, gbc))
        val preferenceFlow = MutableStateFlow(true)

        val vm = HomeViewModel(
            repository = repo,
            hideUnsupportedSystems = { preferenceFlow.value },
            hideUnsupportedSystemsFlow = preferenceFlow,
        )

        val state = vm.uiState.value
        val platforms = state.platforms as SectionState.Loaded<List<PlatformSummary>>
        // PSP filtered out; gb and gbc remain in their original relative order.
        assertThat(platforms.data).hasSize(2)
        assertThat(platforms.data.map { it.slug }).containsExactly("gb", "gbc")
    }

    @Test
    fun `retryPlatforms applies current filter value`() {
        val repo = emptyMockRepo(listOf(supportedPlatform, unsupportedPlatform))
        val preferenceFlow = MutableStateFlow(false)

        val vm = HomeViewModel(
            repository = repo,
            hideUnsupportedSystems = { preferenceFlow.value },
            hideUnsupportedSystemsFlow = preferenceFlow,
        )

        // Initial: both visible.
        var state = vm.uiState.value
        var platforms = state.platforms as SectionState.Loaded<List<PlatformSummary>>
        assertThat(platforms.data).hasSize(2)

        // Toggle ON via flow (triggers refresh).
        preferenceFlow.value = true
        state = vm.uiState.value
        platforms = state.platforms as SectionState.Loaded<List<PlatformSummary>>
        assertThat(platforms.data).hasSize(1)

        // Explicit retry also respects current filter.
        vm.retryPlatforms()
        state = vm.uiState.value
        platforms = state.platforms as SectionState.Loaded<List<PlatformSummary>>
        assertThat(platforms.data).hasSize(1)
    }
}

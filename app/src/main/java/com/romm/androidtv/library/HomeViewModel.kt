package com.romm.androidtv.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.romm.androidtv.romm.RommApiError
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Loading/success/error/empty state for one independent section of the Home screen. */
sealed interface SectionState<out T> {
    object Loading : SectionState<Nothing>
    data class Loaded<T>(val data: T) : SectionState<T>
    data class Error(val error: RommApiError) : SectionState<Nothing>
}

data class HomeUiState(
    val continuePlaying: SectionState<List<LibraryRom>> = SectionState.Loading,
    val recentlyAdded: SectionState<List<LibraryRom>> = SectionState.Loading,
    val favorites: SectionState<List<LibraryRom>> = SectionState.Loading,
    val platforms: SectionState<List<PlatformSummary>> = SectionState.Loading,
    val collections: SectionState<List<CollectionSummary>> = SectionState.Loading,
)

/**
 * Drives the native Home screen (UI_REFACTOR.md). Each section is fetched
 * independently so a slow or failed section (e.g. Favorites on a server with
 * none configured) never blocks the others from rendering.
 *
 * **Latest-refresh-wins semantics**: [generation] is incremented on every
 * [refresh]. Every fetch coroutine captures its generation at launch time and
 * checks it before writing to [_uiState]. A non-cooperative repository that
 * returns stale data after cancellation cannot overwrite newer state because
 * the generation check rejects the write. Individual retry methods also capture
 * the current generation; they succeed only if no newer full refresh has begun.
 */
class HomeViewModel(
    private val repository: LibraryRepository,
    private val hideUnsupportedSystems: () -> Boolean = { false },
    hideUnsupportedSystemsFlow: Flow<Boolean>? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Monotonically increasing token; bumped on every new refresh so that stale
     * in-flight responses for an older generation are discarded. */
    @Volatile private var generation: Int = 0

    /** Tracks the current bulk-refresh so it can be cancelled when a new refresh begins. */
    private var refreshJob: Job? = null

    init {
        refresh()
        // React to preference changes from Settings: re-fetch all sections when the
        // hide-unsupported-systems toggle flips. The initial emission is dropped because
        // we already called refresh() above with the current value.
        hideUnsupportedSystemsFlow?.let { flow ->
            viewModelScope.launch {
                flow.drop(1).collect { refresh() }
            }
        }
    }

    /** Re-fetches every section from scratch (e.g. pull-to-refresh, or retry-all). */
    fun refresh() {
        // Cancel any in-flight bulk refresh so stale responses cannot overwrite newer state.
        refreshJob?.cancel()
        generation++
        val capturedGeneration = generation
        _uiState.value = HomeUiState()
        refreshJob = viewModelScope.launch {
            coroutineScope {
                launch { loadContinuePlayingInternal(capturedGeneration) }
                launch { loadRecentlyAddedInternal(capturedGeneration) }
                launch { loadFavoritesInternal(capturedGeneration) }
                launch { loadPlatformsInternal(capturedGeneration) }
                launch { loadCollectionsInternal(capturedGeneration) }
            }
        }
    }

    fun retryContinuePlaying() = loadContinuePlayingRetry()
    fun retryRecentlyAdded() = loadRecentlyAddedRetry()
    fun retryFavorites() = loadFavoritesRetry()
    fun retryPlatforms() = loadPlatformsRetry()
    fun retryCollections() = loadCollectionsRetry()

    // ---- Retry methods: top-level launches (not children of refreshJob) so they survive
    //     a subsequent refresh() cancellation. Each captures the current generation; if a
    //     newer refresh bumps it, this retry's result is silently discarded. This ensures
    //     game-close retryContinuePlaying() still works when no newer full refresh supersedes it.

    private fun loadContinuePlayingRetry() {
        val gen = generation
        viewModelScope.launch {
            _uiState.update { it.copy(continuePlaying = SectionState.Loading) }
            val hideFlag = hideUnsupportedSystems()
            val state = when (val result = repository.fetchContinuePlaying()) {
                is LibraryResult.Success -> SectionState.Loaded(result.data.filterUnsupportedIfHidden(hideFlag))
                is LibraryResult.Failure -> SectionState.Error(result.error)
            }
            if (generation == gen) {
                _uiState.update { it.copy(continuePlaying = state) }
            }
        }
    }

    private fun loadRecentlyAddedRetry() {
        val gen = generation
        viewModelScope.launch {
            _uiState.update { it.copy(recentlyAdded = SectionState.Loading) }
            val hideFlag = hideUnsupportedSystems()
            val state = when (val result = repository.fetchRecentlyAdded()) {
                is LibraryResult.Success -> SectionState.Loaded(result.data.filterUnsupportedIfHidden(hideFlag))
                is LibraryResult.Failure -> SectionState.Error(result.error)
            }
            if (generation == gen) {
                _uiState.update { it.copy(recentlyAdded = state) }
            }
        }
    }

    private fun loadFavoritesRetry() {
        val gen = generation
        viewModelScope.launch {
            _uiState.update { it.copy(favorites = SectionState.Loading) }
            val hideFlag = hideUnsupportedSystems()
            val state = when (val result = repository.fetchFavorites()) {
                is LibraryResult.Success -> SectionState.Loaded(result.data.filterUnsupportedIfHidden(hideFlag))
                is LibraryResult.Failure -> SectionState.Error(result.error)
            }
            if (generation == gen) {
                _uiState.update { it.copy(favorites = state) }
            }
        }
    }

    private fun loadPlatformsRetry() {
        val gen = generation
        viewModelScope.launch {
            _uiState.update { it.copy(platforms = SectionState.Loading) }
            val state = when (val result = repository.fetchPlatforms()) {
                is LibraryResult.Success -> SectionState.Loaded(result.data)
                is LibraryResult.Failure -> SectionState.Error(result.error)
            }
            if (generation == gen) {
                _uiState.update { it.copy(platforms = state) }
            }
        }
    }

    private fun loadCollectionsRetry() {
        val gen = generation
        viewModelScope.launch {
            _uiState.update { it.copy(collections = SectionState.Loading) }
            val state = when (val result = repository.fetchCollections()) {
                is LibraryResult.Success -> SectionState.Loaded(result.data)
                is LibraryResult.Failure -> SectionState.Error(result.error)
            }
            if (generation == gen) {
                _uiState.update { it.copy(collections = state) }
            }
        }
    }

    // ---- Internal section loaders: launched as children of refreshJob so they are
    //     cancelled together. Each captures generation and checks before write, providing
    //     defense-in-depth against non-cooperative repos that return after cancellation.

    private suspend fun loadContinuePlayingInternal(gen: Int) {
        _uiState.update { it.copy(continuePlaying = SectionState.Loading) }
        val hideFlag = hideUnsupportedSystems()
        val state = when (val result = repository.fetchContinuePlaying()) {
            is LibraryResult.Success -> SectionState.Loaded(result.data.filterUnsupportedIfHidden(hideFlag))
            is LibraryResult.Failure -> SectionState.Error(result.error)
        }
        if (generation == gen) {
            _uiState.update { it.copy(continuePlaying = state) }
        }
    }

    private suspend fun loadRecentlyAddedInternal(gen: Int) {
        _uiState.update { it.copy(recentlyAdded = SectionState.Loading) }
        val hideFlag = hideUnsupportedSystems()
        val state = when (val result = repository.fetchRecentlyAdded()) {
            is LibraryResult.Success -> SectionState.Loaded(result.data.filterUnsupportedIfHidden(hideFlag))
            is LibraryResult.Failure -> SectionState.Error(result.error)
        }
        if (generation == gen) {
            _uiState.update { it.copy(recentlyAdded = state) }
        }
    }

    private suspend fun loadFavoritesInternal(gen: Int) {
        _uiState.update { it.copy(favorites = SectionState.Loading) }
        val hideFlag = hideUnsupportedSystems()
        val state = when (val result = repository.fetchFavorites()) {
            is LibraryResult.Success -> SectionState.Loaded(result.data.filterUnsupportedIfHidden(hideFlag))
            is LibraryResult.Failure -> SectionState.Error(result.error)
        }
        if (generation == gen) {
            _uiState.update { it.copy(favorites = state) }
        }
    }

    private suspend fun loadPlatformsInternal(gen: Int) {
        _uiState.update { it.copy(platforms = SectionState.Loading) }
        val state = when (val result = repository.fetchPlatforms()) {
            is LibraryResult.Success -> SectionState.Loaded(result.data)
            is LibraryResult.Failure -> SectionState.Error(result.error)
        }
        if (generation == gen) {
            _uiState.update { it.copy(platforms = state) }
        }
    }

    private suspend fun loadCollectionsInternal(gen: Int) {
        _uiState.update { it.copy(collections = SectionState.Loading) }
        val state = when (val result = repository.fetchCollections()) {
            is LibraryResult.Success -> SectionState.Loaded(result.data)
            is LibraryResult.Failure -> SectionState.Error(result.error)
        }
        if (generation == gen) {
            _uiState.update { it.copy(collections = state) }
        }
    }

    private inline fun MutableStateFlow<HomeUiState>.update(transform: (HomeUiState) -> HomeUiState) {
        value = transform(value)
    }

    /** Simple factory since this app doesn't yet use a DI framework. */
    class Factory(
        private val repository: LibraryRepository,
        private val hideUnsupportedSystems: () -> Boolean = { false },
        private val hideUnsupportedSystemsFlow: Flow<Boolean>? = null,
    ) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(repository, hideUnsupportedSystems, hideUnsupportedSystemsFlow) as T
        }
    }
}

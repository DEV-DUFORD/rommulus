package com.romm.androidtv.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.romm.androidtv.romm.RommApiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 */
class HomeViewModel(
    private val repository: LibraryRepository,
    private val hideUnsupportedSystems: () -> Boolean = { false },
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** Re-fetches every section from scratch (e.g. pull-to-refresh, or retry-all). */
    fun refresh() {
        _uiState.value = HomeUiState()
        loadContinuePlaying()
        loadRecentlyAdded()
        loadFavorites()
        loadPlatforms()
        loadCollections()
    }

    fun retryContinuePlaying() = loadContinuePlaying()
    fun retryRecentlyAdded() = loadRecentlyAdded()
    fun retryFavorites() = loadFavorites()
    fun retryPlatforms() = loadPlatforms()
    fun retryCollections() = loadCollections()

    private fun loadContinuePlaying() {
        viewModelScope.launch {
            _uiState.update { it.copy(continuePlaying = SectionState.Loading) }
            val state = when (val result = repository.fetchContinuePlaying()) {
                is LibraryResult.Success -> SectionState.Loaded(result.data.filterUnsupportedIfHidden(hideUnsupportedSystems()))
                is LibraryResult.Failure -> SectionState.Error(result.error)
            }
            _uiState.update { it.copy(continuePlaying = state) }
        }
    }

    private fun loadRecentlyAdded() {
        viewModelScope.launch {
            _uiState.update { it.copy(recentlyAdded = SectionState.Loading) }
            val state = when (val result = repository.fetchRecentlyAdded()) {
                is LibraryResult.Success -> SectionState.Loaded(result.data.filterUnsupportedIfHidden(hideUnsupportedSystems()))
                is LibraryResult.Failure -> SectionState.Error(result.error)
            }
            _uiState.update { it.copy(recentlyAdded = state) }
        }
    }

    private fun loadFavorites() {
        viewModelScope.launch {
            _uiState.update { it.copy(favorites = SectionState.Loading) }
            val state = when (val result = repository.fetchFavorites()) {
                is LibraryResult.Success -> SectionState.Loaded(result.data.filterUnsupportedIfHidden(hideUnsupportedSystems()))
                is LibraryResult.Failure -> SectionState.Error(result.error)
            }
            _uiState.update { it.copy(favorites = state) }
        }
    }

    private fun loadPlatforms() {
        viewModelScope.launch {
            _uiState.update { it.copy(platforms = SectionState.Loading) }
            val state = when (val result = repository.fetchPlatforms()) {
                is LibraryResult.Success -> SectionState.Loaded(result.data)
                is LibraryResult.Failure -> SectionState.Error(result.error)
            }
            _uiState.update { it.copy(platforms = state) }
        }
    }

    private fun loadCollections() {
        viewModelScope.launch {
            _uiState.update { it.copy(collections = SectionState.Loading) }
            val state = when (val result = repository.fetchCollections()) {
                is LibraryResult.Success -> SectionState.Loaded(result.data)
                is LibraryResult.Failure -> SectionState.Error(result.error)
            }
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
    ) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(repository, hideUnsupportedSystems) as T
        }
    }
}

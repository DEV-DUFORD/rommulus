package com.romm.androidtv.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 40

/** Drives a paginated ROM grid (`PlatformDetailScreen`/`CollectionDetailScreen`, UI_REFACTOR.md section 7). */
data class RomGridUiState(
    val section: SectionState<List<LibraryRom>> = SectionState.Loading,
    val total: Int = 0,
    val isLoadingMore: Boolean = false,
)

/**
 * Generic paginated ROM grid, parameterized by a [RomQuery] (platform or
 * collection filter). Loads a first page on init; [loadMore] appends the
 * next page once the grid nears its end. A platform/collection can have
 * hundreds of ROMs (e.g. 343 confirmed live for one platform), so a single
 * fixed-size page is not sufficient.
 */
class RomGridViewModel(
    private val repository: LibraryRepository,
    private val query: RomQuery,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RomGridUiState())
    val uiState: StateFlow<RomGridUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.value = RomGridUiState()
        viewModelScope.launch {
            val state = when (val result = repository.fetchRomsPage(query, PAGE_SIZE, offset = 0)) {
                is LibraryResult.Success -> RomGridUiState(
                    section = SectionState.Loaded(result.data.roms),
                    total = result.data.total,
                )
                is LibraryResult.Failure -> RomGridUiState(section = SectionState.Error(result.error))
            }
            _uiState.value = state
        }
    }

    /** Fetches the next page and appends it, unless already loaded in full or a load is already in flight. */
    fun loadMore() {
        val current = _uiState.value
        val loadedRoms = (current.section as? SectionState.Loaded)?.data ?: return
        if (current.isLoadingMore || loadedRoms.size >= current.total) return

        _uiState.value = current.copy(isLoadingMore = true)
        viewModelScope.launch {
            when (val result = repository.fetchRomsPage(query, PAGE_SIZE, offset = loadedRoms.size)) {
                is LibraryResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        section = SectionState.Loaded(loadedRoms + result.data.roms),
                        total = result.data.total,
                        isLoadingMore = false,
                    )
                }
                is LibraryResult.Failure -> {
                    // Keep whatever loaded successfully so far; just stop the loading-more spinner.
                    _uiState.value = _uiState.value.copy(isLoadingMore = false)
                }
            }
        }
    }

    /** Simple factory since this app doesn't yet use a DI framework. */
    class Factory(
        private val repository: LibraryRepository,
        private val query: RomQuery,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RomGridViewModel(repository, query) as T
        }
    }
}

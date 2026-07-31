package com.romm.androidtv.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
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
 *
 * **Latest-refresh-wins semantics**: [generation] is incremented on every
 * [refresh]. Both [refresh] and [loadMore] capture the generation at launch
 * time and check it before writing to [_uiState]. A non-cooperative repository
 * that returns stale data after cancellation cannot overwrite newer state.
 * loadMore belongs to its captured generation and cannot append after refresh.
 * isLoadingMore is only cleared when the capturing generation still matches,
 * preventing an old job's finally block from corrupting a newer state's flag.
 */
class RomGridViewModel(
    private val repository: LibraryRepository,
    private val query: RomQuery,
    private val hideUnsupportedSystems: () -> Boolean = { false },
    hideUnsupportedSystemsFlow: Flow<Boolean>? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RomGridUiState())
    val uiState: StateFlow<RomGridUiState> = _uiState.asStateFlow()

    /** Monotonically increasing token; bumped on every new refresh so that stale
     * in-flight responses for an older generation are discarded. */
    @Volatile private var generation: Int = 0

    /** Tracks the current refresh so it can be cancelled when a new refresh begins. */
    private var refreshJob: Job? = null
    /** Tracks an in-flight loadMore so it can be cancelled by a subsequent refresh. */
    private var loadMoreJob: Job? = null

    init {
        refresh()
        // React to preference changes from Settings: re-fetch the grid when the
        // hide-unsupported-systems toggle flips. The initial emission is dropped
        // because we already called refresh() above with the current value.
        hideUnsupportedSystemsFlow?.let { flow ->
            viewModelScope.launch {
                flow.drop(1).collect { refresh() }
            }
        }
    }

    fun refresh() {
        // Cancel any in-flight refresh and loadMore so stale responses cannot overwrite newer state.
        refreshJob?.cancel()
        loadMoreJob?.cancel()
        generation++
        val capturedGeneration = generation
        _uiState.value = RomGridUiState()
        refreshJob = viewModelScope.launch {
            val hideFlag = hideUnsupportedSystems()
            val state = when (val result = repository.fetchRomsPage(query, PAGE_SIZE, offset = 0)) {
                is LibraryResult.Success -> RomGridUiState(
                    section = SectionState.Loaded(result.data.roms.filterUnsupportedIfHidden(hideFlag)),
                    total = result.data.total,
                )
                is LibraryResult.Failure -> RomGridUiState(section = SectionState.Error(result.error))
            }
            // Only write if no newer refresh has begun since we started fetching.
            if (generation == capturedGeneration) {
                _uiState.value = state
            }
        }
    }

    /** Fetches the next page and appends it, unless already loaded in full or a load is already in flight. */
    fun loadMore() {
        val current = _uiState.value
        val loadedRoms = (current.section as? SectionState.Loaded)?.data ?: return
        if (current.isLoadingMore || loadedRoms.size >= current.total) return

        // Cancel any stale loadMore from a prior cycle.
        loadMoreJob?.cancel()

        // Capture the generation at call time so a stale response from an older
        // generation is dropped when a newer refresh occurs mid-pagination.
        val capturedGeneration = generation

        _uiState.value = current.copy(isLoadingMore = true)
        loadMoreJob = viewModelScope.launch {
            val hideFlag = hideUnsupportedSystems()
            when (val result = repository.fetchRomsPage(query, PAGE_SIZE, offset = loadedRoms.size)) {
                is LibraryResult.Success -> {
                    // Only append if no newer refresh has begun since we started.
                    if (generation == capturedGeneration) {
                        _uiState.value = _uiState.value.copy(
                            section = SectionState.Loaded(loadedRoms + result.data.roms.filterUnsupportedIfHidden(hideFlag)),
                            total = result.data.total,
                            isLoadingMore = false,
                        )
                    }
                    // Else: a newer refresh owns the UI state; do not touch it.
                }
                is LibraryResult.Failure -> {
                    // Only clear isLoadingMore if we're still the current generation;
                    // a newer refresh may have set its own loading state.
                    if (generation == capturedGeneration) {
                        _uiState.value = _uiState.value.copy(isLoadingMore = false)
                    }
                }
            }
        }
    }

    /** Simple factory since this app doesn't yet use a DI framework. */
    class Factory(
        private val repository: LibraryRepository,
        private val query: RomQuery,
        private val hideUnsupportedSystems: () -> Boolean = { false },
        private val hideUnsupportedSystemsFlow: Flow<Boolean>? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RomGridViewModel(repository, query, hideUnsupportedSystems, hideUnsupportedSystemsFlow) as T
        }
    }
}

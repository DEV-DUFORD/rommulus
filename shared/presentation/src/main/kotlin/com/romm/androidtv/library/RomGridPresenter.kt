package com.romm.androidtv.library

import com.romm.androidtv.emulation.model.ANDROID_CORE_ABIS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 40

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
 *
 * Platform-neutral: all async work runs in the injected [scope] so the whole
 * presenter is exercisable by plain JVM unit tests (Linux port Phase 4).
 */
class RomGridPresenter(
    private val scope: CoroutineScope,
    private val repository: LibraryRepository,
    private val query: RomQuery,
    private val hideUnsupportedSystems: () -> Boolean = { true },
    private val supportedCoreAbis: Set<String> = ANDROID_CORE_ABIS,
    hideUnsupportedSystemsFlow: Flow<Boolean>? = null,
    refreshEvents: Flow<Unit>? = null,
) {

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
            scope.launch {
                flow.drop(1).collect { refresh() }
            }
        }
        refreshEvents?.let { events ->
            scope.launch {
                events.collect { refresh() }
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
        refreshJob = scope.launch {
            val hideFlag = hideUnsupportedSystems()
            val state = when (val result = repository.fetchRomsPage(query, PAGE_SIZE, offset = 0)) {
                is LibraryResult.Success -> RomGridUiState(
                    section = SectionState.Loaded(
                        result.data.roms.filterUnsupportedIfHidden(hideFlag, supportedCoreAbis),
                    ),
                    total = result.data.total,
                    rawFetchedCount = result.data.roms.size,
                )
                is LibraryResult.Failure -> RomGridUiState(section = SectionState.Error(result.error))
            }
            // Only write if no newer refresh has begun since we started fetching.
            if (generation == capturedGeneration) {
                _uiState.value = state
            }
        }
    }

    /**
     * Fetches the next page and appends it, unless already loaded in full or a load is already
     * in flight. The next page's `offset` is [RomGridUiState.rawFetchedCount] (raw server count),
     * not `loadedRoms.size` — the appended result is de-duplicated by [LibraryRom.id] below since
     * `group_by_meta_id` can shift which sibling rom represents a group across a page boundary,
     * occasionally returning the same id on two consecutive pages. Without the de-dup, the grid's
     * `items(..., key = { it.id })` would crash with a duplicate-key exception the moment the
     * overlapping page renders (i.e. right when the user scrolls near the end of a collection).
     */
    fun loadMore() {
        val current = _uiState.value
        val loadedRoms = (current.section as? SectionState.Loaded)?.data ?: return
        if (current.isLoadingMore || current.rawFetchedCount >= current.total) return

        // Cancel any stale loadMore from a prior cycle.
        loadMoreJob?.cancel()

        // Capture the generation at call time so a stale response from an older
        // generation is dropped when a newer refresh occurs mid-pagination.
        val capturedGeneration = generation

        _uiState.value = current.copy(isLoadingMore = true)
        loadMoreJob = scope.launch {
            val hideFlag = hideUnsupportedSystems()
            when (val result = repository.fetchRomsPage(query, PAGE_SIZE, offset = current.rawFetchedCount)) {
                is LibraryResult.Success -> {
                    // Only append if no newer refresh has begun since we started.
                    if (generation == capturedGeneration) {
                        val merged = (
                            loadedRoms +
                                result.data.roms.filterUnsupportedIfHidden(hideFlag, supportedCoreAbis)
                            )
                            .distinctBy { it.id }
                        _uiState.value = _uiState.value.copy(
                            section = SectionState.Loaded(merged),
                            total = result.data.total,
                            rawFetchedCount = current.rawFetchedCount + result.data.roms.size,
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
}

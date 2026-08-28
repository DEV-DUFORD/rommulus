package com.romm.androidtv.library

import com.romm.androidtv.emulation.model.ANDROID_CORE_ABIS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Page size for search pagination. */
private const val SEARCH_PAGE_SIZE = 40
/** Debounce interval in milliseconds before auto-firing a search. */
private const val DEBOUNCE_MS = 300L

/**
 * Drives the native Search screen. Accepts free-text queries, debounces rapid
 * input ([DEBOUNCE_MS] ms), and paginates results through [LibraryRepository.fetchRomsPage].
 * Each new query cancels any in-flight request from a prior query.
 *
 * Platform-neutral: all async work runs in the injected [scope] so the whole
 * presenter is exercisable by plain JVM unit tests (Linux port Phase 4).
 */
class SearchPresenter(
    private val scope: CoroutineScope,
    private val repository: LibraryRepository,
    private val hideUnsupportedSystems: () -> Boolean = { true },
    private val supportedCoreAbis: Set<String> = ANDROID_CORE_ABIS,
    hideUnsupportedSystemsFlow: Flow<Boolean>? = null,
    refreshEvents: Flow<Unit>? = null,
) {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var debounceJob: Job? = null
    /** Monotonically increasing token; bumped on every new search or blank-query reset so that
     * stale in-flight pagination responses for an older generation are discarded. */
    @Volatile private var generation: Int = 0

    init {
        // React to preference changes from Settings: re-execute the current search
        // when the hide-unsupported-systems toggle flips. The initial emission is
        // dropped because there's no active query at construction time.
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

    /** Re-execute the current search from scratch, resetting pagination state.
     * No-op if there is no active query (idle state). */
    fun refresh() {
        val activeTerm = _uiState.value.activeQuery ?: return
        val raw = _uiState.value.query
        executeSearch(raw, activeTerm)
    }

    /** Update the search query text (e.g. from TextField [onValueChange]).
     * Auto-fires a debounced search after [DEBOUNCE_MS] ms of inactivity. */
    fun onQueryChanged(newQuery: String) {
        _uiState.value = _uiState.value.copy(query = newQuery)

        debounceJob?.cancel()
        debounceJob = null

        if (newQuery.isNotBlank()) {
            debounceJob = scope.launch {
                delay(DEBOUNCE_MS)
                executeSearch(newQuery, newQuery.trim())
            }
        } else {
            // Cancel any pending/in-flight search so stale results cannot overwrite idle state.
            searchJob?.cancel()
            searchJob = null
            generation++
            _uiState.value = SearchUiState(query = "")
        }
    }

    /** Explicitly submit the current query now (bypasses debounce). */
    fun submitQuery() {
        debounceJob?.cancel()
        val raw = _uiState.value.query
        val term = raw.trim()
        if (term.isNotBlank()) {
            executeSearch(raw, term)
        }
    }

    /** Load the next page of results for the current query. */
    fun loadMore() {
        val current = _uiState.value
        if (current.isLoading || current.rawFetchedCount >= current.total) return

        val activeTerm = current.activeQuery ?: return

        // Capture the generation at call time so a stale response from an older generation is dropped.
        val capturedGeneration = generation

        searchJob = scope.launch {
            val isHidingUnsupported = hideUnsupportedSystems() // Snapshot once for this operation.
            _uiState.value = current.copy(isLoading = true, hideUnsupportedSystems = isHidingUnsupported)
            when (val result = repository.fetchRomsPage(
                RomQuery.Search(activeTerm),
                SEARCH_PAGE_SIZE,
                offset = current.rawFetchedCount,
            )) {
                is LibraryResult.Success -> {
                    // Discard if the generation changed while we were fetching (query changed or cleared).
                    if (generation == capturedGeneration) {
                        // De-duped by id: group_by_meta_id can shift which sibling rom represents
                        // a group across a page boundary, occasionally repeating an id across two
                        // consecutive pages. Undeduped, SearchScreen's `items(..., key = { it.id })`
                        // would crash with a duplicate-key exception as soon as that page renders.
                        _uiState.value = _uiState.value.copy(
                            roms = (
                                current.roms +
                                    result.data.roms.filterUnsupportedIfHidden(
                                        isHidingUnsupported,
                                        supportedCoreAbis,
                                    )
                                )
                                .distinctBy { it.id },
                            rawFetchedCount = current.rawFetchedCount + result.data.roms.size,
                            total = result.data.total,
                            isLoading = false,
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(isLoading = false)
                    }
                }
                is LibraryResult.Failure -> {
                    if (generation == capturedGeneration) {
                        _uiState.value = _uiState.value.copy(
                            error = result.error,
                            isLoading = false,
                        )
                    } else {
                        // A newer generation owns the loading state; do not corrupt it.
                    }
                }
            }
        }
    }

    /** Retry the last failed search (re-uses current active query). */
    fun retry() {
        val activeTerm = _uiState.value.activeQuery ?: return
        val raw = _uiState.value.query
        executeSearch(raw, activeTerm)
    }

    // ---- Internal ----

    /** @param rawQuery  Exact user input for the TextField (preserves leading/trailing whitespace).
     *  @param normalizedTerm Trimmed term sent to the API. */
    private fun executeSearch(rawQuery: String, normalizedTerm: String) {
        searchJob?.cancel()
        debounceJob?.cancel()
        debounceJob = null
        generation++ // New generation invalidates any stale in-flight loadMore.
        val capturedGeneration = generation

        searchJob = scope.launch {
            val isHidingUnsupported = hideUnsupportedSystems() // Snapshot once for this operation.
            _uiState.value = SearchUiState(
                query = rawQuery,
                activeQuery = normalizedTerm,
                isLoading = true,
                hideUnsupportedSystems = isHidingUnsupported,
            )
            when (val result = repository.fetchRomsPage(
                RomQuery.Search(normalizedTerm),
                SEARCH_PAGE_SIZE,
                offset = 0,
            )) {
                is LibraryResult.Success -> {
                    // Guard against non-cooperative repos that return after cancellation.
                    if (generation == capturedGeneration) {
                        _uiState.value = _uiState.value.copy(
                            roms = result.data.roms.filterUnsupportedIfHidden(
                                isHidingUnsupported,
                                supportedCoreAbis,
                            ),
                            rawFetchedCount = result.data.roms.size,
                            total = result.data.total,
                            isLoading = false,
                        )
                    } else {
                        // A newer search has begun; clear loading state to avoid stale spinner.
                        _uiState.value = _uiState.value.copy(isLoading = false)
                    }
                }
                is LibraryResult.Failure -> {
                    if (generation == capturedGeneration) {
                        _uiState.value = _uiState.value.copy(
                            error = result.error,
                            isLoading = false,
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(isLoading = false)
                    }
                }
            }
        }
    }
}

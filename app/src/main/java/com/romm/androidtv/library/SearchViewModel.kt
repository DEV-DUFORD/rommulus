package com.romm.androidtv.library

import android.content.Context
import androidx.lifecycle.ViewModel
import com.romm.androidtv.BuildConfig
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.romm.androidtv.config.SettingsRepository
import com.romm.androidtv.network.RommOkHttpClient
import com.romm.androidtv.romm.RommApiError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Page size for search pagination. */
private const val SEARCH_PAGE_SIZE = 40
/** Debounce interval in milliseconds before auto-firing a search. */
private const val DEBOUNCE_MS = 300L

/** UI state emitted by [SearchViewModel]. */
data class SearchUiState(
    /** The current query text shown in the input field — always preserves exactly what the user typed, including trailing/leading spaces. */
    val query: String = "",
    /** Whether a network request is currently in flight. */
    val isLoading: Boolean = false,
    /** Accumulated search results across pages. */
    val roms: List<LibraryRom> = emptyList(),
    /** Total number of matching ROMs on the server (0 until first page loads). */
    val total: Int = 0,
    /** Error from the last failed request, or null if no error occurred. */
    val error: RommApiError? = null,
    /** Normalized (trimmed) term used for API calls and pagination. Null when idle. Decouples display text from request term so leading/trailing spaces are never lost in the TextField. */
    val activeQuery: String? = null,
)

/**
 * Drives the native Search screen. Accepts free-text queries, debounces rapid
 * input ([DEBOUNCE_MS] ms), and paginates results through [LibraryRepository.fetchRomsPage].
 * Each new query cancels any in-flight request from a prior query.
 *
 * @param testScope Optional [CoroutineScope] for JVM unit tests (avoids
 *   Dispatchers.Main dependency). Production code should pass null to use
 *   the standard [viewModelScope].
 */
class SearchViewModel(
    private val repository: LibraryRepository,
    testScope: CoroutineScope? = null,
) : ViewModel() {

    /** Internal scope: uses injected [testScope] in tests, [viewModelScope] in production. */
    private val scope: CoroutineScope = testScope ?: viewModelScope

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var debounceJob: Job? = null
    /** Monotonically increasing token; bumped on every new search or blank-query reset so that
     * stale in-flight pagination responses for an older generation are discarded. */
    @Volatile private var generation: Int = 0

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
        if (current.isLoading || current.roms.size >= current.total) return

        val activeTerm = current.activeQuery ?: return

        // Capture the generation at call time so a stale response from an older generation is dropped.
        val capturedGeneration = generation

        searchJob = scope.launch {
            _uiState.value = current.copy(isLoading = true)
            when (val result = repository.fetchRomsPage(
                RomQuery.Search(activeTerm),
                SEARCH_PAGE_SIZE,
                offset = current.roms.size,
            )) {
                is LibraryResult.Success -> {
                    // Discard if the generation changed while we were fetching (query changed or cleared).
                    if (generation == capturedGeneration) {
                        _uiState.value = _uiState.value.copy(
                            roms = current.roms + result.data.roms,
                            total = result.data.total,
                            isLoading = false,
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(isLoading = false)
                    }
                }
                is LibraryResult.Failure -> {
                    if (generation == capturedGeneration) {
                        _uiState.value = _uiState.value.copy(isLoading = false)
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

        searchJob = scope.launch {
            _uiState.value = SearchUiState(
                query = rawQuery,
                activeQuery = normalizedTerm,
                isLoading = true,
            )
            when (val result = repository.fetchRomsPage(
                RomQuery.Search(normalizedTerm),
                SEARCH_PAGE_SIZE,
                offset = 0,
            )) {
                is LibraryResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        roms = result.data.roms,
                        total = result.data.total,
                        isLoading = false,
                    )
                }
                is LibraryResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        error = result.error,
                        isLoading = false,
                    )
                }
            }
        }
    }

    /** Factory — used by [com.romm.androidtv.library.ui.SearchScreen] to construct the ViewModel. */
    class Factory(
        private val context: Context,
        private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val prefs = context.getSharedPreferences(SettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
            val settings = SettingsRepository(prefs, BuildConfig.ROMM_ORIGIN)
            val originProvider: () -> String = { settings.currentProfile().origin }
            val repository = LibraryRepositoryImpl(RommOkHttpClient.build(), originProvider)
            return SearchViewModel(repository) as T
        }
    }
}

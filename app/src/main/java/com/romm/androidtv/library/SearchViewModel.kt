package com.romm.androidtv.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin lifecycle wrapper around the platform-neutral [SearchPresenter]
 * (Linux port Phase 4). All state-machine behavior lives in
 * `:shared:presentation`; this class only binds it to the lifecycle owner's
 * scope and forwards the public API so existing call sites (factory,
 * MainActivity, SearchScreen) compile unchanged.
 *
 * @param testScope Optional [CoroutineScope] for JVM unit tests (avoids
 *   Dispatchers.Main dependency). Production code should pass null to use
 *   the standard [viewModelScope].
 */
class SearchViewModel(
    repository: LibraryRepository,
    testScope: CoroutineScope? = null,
    hideUnsupportedSystems: () -> Boolean = { true },
    hideUnsupportedSystemsFlow: Flow<Boolean>? = null,
    refreshEvents: Flow<Unit>? = null,
) : ViewModel() {

    private val presenter = SearchPresenter(
        scope = testScope ?: viewModelScope,
        repository = repository,
        hideUnsupportedSystems = hideUnsupportedSystems,
        hideUnsupportedSystemsFlow = hideUnsupportedSystemsFlow,
        refreshEvents = refreshEvents,
    )

    val uiState: StateFlow<SearchUiState> = presenter.uiState

    fun onQueryChanged(newQuery: String) {
        presenter.onQueryChanged(newQuery)
    }

    fun submitQuery() {
        presenter.submitQuery()
    }

    fun refresh() {
        presenter.refresh()
    }

    fun loadMore() {
        presenter.loadMore()
    }

    fun retry() {
        presenter.retry()
    }

    /** Factory — used by [com.romm.androidtv.library.ui.SearchScreen] to construct the ViewModel. */
    class Factory(
        private val repository: LibraryRepository,
        private val hideUnsupportedSystems: () -> Boolean = { true },
        private val hideUnsupportedSystemsFlow: Flow<Boolean>? = null,
        private val refreshEvents: Flow<Unit>? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SearchViewModel(
                repository,
                null,
                hideUnsupportedSystems,
                hideUnsupportedSystemsFlow,
                refreshEvents,
            ) as T
        }
    }
}

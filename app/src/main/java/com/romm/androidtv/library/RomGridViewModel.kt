package com.romm.androidtv.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin lifecycle wrapper around the platform-neutral [RomGridPresenter]
 * (Linux port Phase 4). All pagination/stale-response behavior lives in
 * `:shared:presentation`; this class only binds it to the lifecycle owner's
 * scope and forwards the public API so existing call sites (factory,
 * MainActivity, RomGridScreen) compile unchanged.
 */
class RomGridViewModel(
    repository: LibraryRepository,
    query: RomQuery,
    hideUnsupportedSystems: () -> Boolean = { true },
    hideUnsupportedSystemsFlow: Flow<Boolean>? = null,
    refreshEvents: Flow<Unit>? = null,
) : ViewModel() {

    private val presenter = RomGridPresenter(
        scope = viewModelScope,
        repository = repository,
        query = query,
        hideUnsupportedSystems = hideUnsupportedSystems,
        hideUnsupportedSystemsFlow = hideUnsupportedSystemsFlow,
        refreshEvents = refreshEvents,
    )

    val uiState: StateFlow<RomGridUiState> = presenter.uiState

    fun refresh() {
        presenter.refresh()
    }

    fun loadMore() {
        presenter.loadMore()
    }

    /** Simple factory since this app doesn't yet use a DI framework. */
    class Factory(
        private val repository: LibraryRepository,
        private val query: RomQuery,
        private val hideUnsupportedSystems: () -> Boolean = { true },
        private val hideUnsupportedSystemsFlow: Flow<Boolean>? = null,
        private val refreshEvents: Flow<Unit>? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RomGridViewModel(
                repository,
                query,
                hideUnsupportedSystems,
                hideUnsupportedSystemsFlow,
                refreshEvents,
            ) as T
        }
    }
}

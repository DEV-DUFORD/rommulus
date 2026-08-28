package com.romm.androidtv.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin lifecycle wrapper around the platform-neutral [HomePresenter]
 * (Linux port Phase 4). All state-machine behavior lives in
 * `:shared:presentation`; this class only binds it to the lifecycle owner's
 * scope and forwards the public API so existing call sites (factory,
 * MainActivity, HomeScreen) compile unchanged.
 */
class HomeViewModel(
    repository: LibraryRepository,
    hideUnsupportedSystems: () -> Boolean = { true },
    hideUnsupportedSystemsFlow: Flow<Boolean>? = null,
    refreshEvents: Flow<Unit>? = null,
    onRetrySucceeded: () -> Unit = {},
) : ViewModel() {

    private val presenter = HomePresenter(
        scope = viewModelScope,
        repository = repository,
        hideUnsupportedSystems = hideUnsupportedSystems,
        hideUnsupportedSystemsFlow = hideUnsupportedSystemsFlow,
        refreshEvents = refreshEvents,
        onRetrySucceeded = onRetrySucceeded,
    )

    val uiState: StateFlow<HomeUiState> = presenter.uiState

    fun refresh() {
        presenter.refresh()
    }

    fun retryContinuePlaying() {
        presenter.retryContinuePlaying()
    }

    fun retryRecentlyAdded() {
        presenter.retryRecentlyAdded()
    }

    fun retryFavorites() {
        presenter.retryFavorites()
    }

    fun retryPlatforms() {
        presenter.retryPlatforms()
    }

    fun retryCollections() {
        presenter.retryCollections()
    }

    /** Simple factory since this app doesn't yet use a DI framework. */
    class Factory(
        private val repository: LibraryRepository,
        private val hideUnsupportedSystems: () -> Boolean = { true },
        private val hideUnsupportedSystemsFlow: Flow<Boolean>? = null,
        private val refreshEvents: Flow<Unit>? = null,
        private val onRetrySucceeded: () -> Unit = {},
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(
                repository,
                hideUnsupportedSystems,
                hideUnsupportedSystemsFlow,
                refreshEvents,
                onRetrySucceeded,
            ) as T
        }
    }
}

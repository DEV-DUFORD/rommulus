package com.romm.androidtv.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin lifecycle wrapper around the platform-neutral [RomDetailPresenter]
 * (Linux port Phase 4). All state-machine behavior lives in
 * `:shared:presentation`; this class only binds it to the lifecycle owner's
 * scope and forwards the public API so existing call sites (factory,
 * MainActivity, GameDetailScreen) compile unchanged.
 */
class RomDetailViewModel(
    private val repository: LibraryRepository,
    private val romId: Long,
    refreshEvents: Flow<Unit>? = null,
    private val onLibraryMutated: () -> Unit = {},
) : ViewModel() {

    private val presenter = RomDetailPresenter(
        scope = viewModelScope,
        repository = repository,
        romId = romId,
        refreshEvents = refreshEvents,
        onLibraryMutated = onLibraryMutated,
    )

    val uiState: StateFlow<RomDetailUiState> = presenter.uiState

    fun refresh() {
        presenter.refresh()
    }

    fun onFavoriteSelected() {
        presenter.onFavoriteSelected()
    }

    fun onCollectionPickerRequested() {
        presenter.onCollectionPickerRequested()
    }

    fun onCollectionSelected(collectionId: Long) {
        presenter.onCollectionSelected(collectionId)
    }

    fun onCreateCollectionRequested() {
        presenter.onCreateCollectionRequested()
    }

    fun onCollectionNameChanged(value: String) {
        presenter.onCollectionNameChanged(value)
    }

    fun onCreateCollectionSubmitted() {
        presenter.onCreateCollectionSubmitted()
    }

    fun onCreateCollectionCancelled() {
        presenter.onCreateCollectionCancelled()
    }

    fun onAlertDismissed() {
        presenter.onAlertDismissed()
    }

    fun onCollectionRetry() {
        presenter.onCollectionRetry()
    }

    fun onDialogDismissed() {
        presenter.onDialogDismissed()
    }

    /** Simple factory since this app doesn't yet use a DI framework. */
    class Factory(
        private val repository: LibraryRepository,
        private val romId: Long,
        private val refreshEvents: Flow<Unit>? = null,
        private val onLibraryMutated: () -> Unit = {},
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RomDetailViewModel(repository, romId, refreshEvents, onLibraryMutated) as T
        }
    }
}

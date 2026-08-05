package com.romm.androidtv.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.romm.androidtv.romm.RommApiError
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Public Game Detail state model (drives `GameDetailScreen`, UI_REFACTOR.md §7).
// ---------------------------------------------------------------------------

data class RomDetailUiState(
    val detail: SectionState<RomDetail>,
    val collections: CollectionLoadState,
    val favorite: FavoriteUiState,
    val collectionDialog: CollectionDialogState?,
    val alert: GameDetailAlert?,
)

sealed interface CollectionLoadState {
    data object Loading : CollectionLoadState
    data class Loaded(
        val allVisible: List<CollectionSummary>,
        val ownedWritable: List<CollectionSummary>,
        val favoriteCollection: CollectionSummary?,
    ) : CollectionLoadState
    data class Error(val error: RommApiError) : CollectionLoadState
}

sealed interface FavoriteUiState {
    data object Loading : FavoriteUiState
    data class Confirmed(val isFavorite: Boolean) : FavoriteUiState
    data class Updating(val previous: Boolean, val target: Boolean) : FavoriteUiState
}

sealed interface CollectionDialogState {
    data object List : CollectionDialogState
    data class Creating(
        val name: String,
        val validationError: String?,
        val submitting: Boolean,
    ) : CollectionDialogState
}

enum class FavoriteOperation { ADD, REMOVE }

sealed interface GameDetailAlert {
    data class FavoriteFailure(val operation: FavoriteOperation) : GameDetailAlert
    data class CollectionAddFailure(val collectionId: Long) : GameDetailAlert
    data class CreatedButAddFailed(val collectionId: Long) : GameDetailAlert
}

/**
 * Drives `GameDetailScreen` (UI_REFACTOR.md section 7). Owns a dedicated
 * immutable UI state and all the collection/favorite mutations the screen
 * exposes. The ROM detail is fetched independently of the owned-collection
 * lookup so a slow collection fetch never blocks cover/title/Play.
 *
 * **Stale protection**: [generation] guards the fetch coroutines while
 * [mutationToken] guards every mutation. Both are bumped on [refresh] so a
 * late, superseded response cannot overwrite newer state; the scope is also
 * cancelled on clear via [viewModelScope].
 */
class RomDetailViewModel(
    private val repository: LibraryRepository,
    private val romId: Long,
    refreshEvents: Flow<Unit>? = null,
    private val onLibraryMutated: () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(
        RomDetailUiState(
            detail = SectionState.Loading,
            collections = CollectionLoadState.Loading,
            favorite = FavoriteUiState.Loading,
            collectionDialog = null,
            alert = null,
        ),
    )
    val state: StateFlow<RomDetailUiState> = _state.asStateFlow()

    private var generation = 0
    private var mutationToken = 0
    private var detailJob: Job? = null
    private var collectionJob: Job? = null
    private val inFlightCollectionAdds = mutableSetOf<Long>()

    init {
        refresh()
        refreshEvents?.let { events ->
            viewModelScope.launch {
                events.collect { refresh() }
            }
        }
    }

    /** Refetches BOTH the ROM detail and the owned collections. */
    fun refresh() {
        generation++
        mutationToken++
        refreshDetail()
        refreshCollections()
    }

    // -----------------------------------------------------------------------
    // Loads
    // -----------------------------------------------------------------------

    private fun refreshDetail() {
        val capturedGeneration = generation
        detailJob?.cancel()
        _state.update { it.copy(detail = SectionState.Loading) }
        detailJob = viewModelScope.launch {
            val resultState = when (val result = repository.fetchRomDetail(romId)) {
                is LibraryResult.Success -> SectionState.Loaded(result.data)
                is LibraryResult.Failure -> SectionState.Error(result.error)
            }
            if (capturedGeneration == generation) {
                _state.update { it.copy(detail = resultState) }
            }
        }
    }

    private fun refreshCollections() {
        val capturedGeneration = generation
        collectionJob?.cancel()
        // Keep favorite in Loading until membership is known.
        _state.update {
            it.copy(collections = CollectionLoadState.Loading, favorite = FavoriteUiState.Loading)
        }
        collectionJob = viewModelScope.launch {
            when (val result = repository.fetchOwnedWritableCollections()) {
                is LibraryResult.Success -> {
                    val loaded = CollectionLoadState.Loaded(
                        allVisible = result.data,
                        ownedWritable = result.data.filter { !it.isFavorite && !it.isSmart && !it.isVirtual },
                        favoriteCollection = result.data.firstOrNull { it.isFavorite },
                    )
                    val confirmed = loaded.favoriteCollection?.romIds?.contains(romId) == true
                    if (capturedGeneration == generation) {
                        _state.update {
                            it.copy(collections = loaded, favorite = FavoriteUiState.Confirmed(confirmed))
                        }
                    }
                }
                is LibraryResult.Failure -> {
                    if (capturedGeneration == generation) {
                        _state.update { it.copy(collections = CollectionLoadState.Error(result.error)) }
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Favorite toggle
    // -----------------------------------------------------------------------

    fun onFavoriteSelected() {
        val current = _state.value
        if (current.favorite is FavoriteUiState.Loading || current.favorite is FavoriteUiState.Updating) return
        val collections = current.collections as? CollectionLoadState.Loaded ?: return
        val confirmed = (current.favorite as? FavoriteUiState.Confirmed)?.isFavorite ?: false
        val target = !confirmed
        val token = ++mutationToken
        _state.update { it.copy(favorite = FavoriteUiState.Updating(confirmed, target)) }

        viewModelScope.launch {
            if (target) {
                runFavoriteAdd(token, confirmed, collections)
            } else {
                runFavoriteRemove(token, confirmed, collections)
            }
        }
    }

    private suspend fun runFavoriteAdd(token: Int, previous: Boolean, collections: CollectionLoadState.Loaded) {
        var favorite = collections.favoriteCollection
        if (favorite == null) {
            when (val created = repository.createCollection("Favorites", isFavorite = true)) {
                is LibraryResult.Success -> {
                    if (token != mutationToken) return
                    favorite = created.data
                    _state.update { it.copy(collections = (it.collections as? CollectionLoadState.Loaded)?.applyCollection(created.data) ?: it.collections) }
                }
                is LibraryResult.Failure -> {
                    // Duplicate/conflict race: another request created the favorite. Refetch once.
                    if (created.httpCode == 409) {
                        val refetched = repository.fetchOwnedWritableCollections()
                        val discovered = (refetched as? LibraryResult.Success)?.data?.firstOrNull { it.isFavorite }
                        if (discovered != null) {
                            if (token != mutationToken) return
                            favorite = discovered
                            _state.update { it.copy(collections = (it.collections as? CollectionLoadState.Loaded)?.applyCollection(discovered) ?: it.collections) }
                        } else {
                            failFavorite(token, previous, FavoriteOperation.ADD)
                            return
                        }
                    } else {
                        failFavorite(token, previous, FavoriteOperation.ADD)
                        return
                    }
                }
            }
        }

        val favoriteId = favorite?.id ?: run {
            failFavorite(token, previous, FavoriteOperation.ADD)
            return
        }
        when (val added = repository.addRomToCollection(favoriteId, romId)) {
            is LibraryResult.Success -> {
                if (token != mutationToken) return
                _state.update {
                    it.copy(
                        collections = (it.collections as? CollectionLoadState.Loaded)?.applyCollection(added.data) ?: it.collections,
                        favorite = FavoriteUiState.Confirmed(true),
                    )
                }
                onLibraryMutated()
            }
            is LibraryResult.Failure -> failFavorite(token, previous, FavoriteOperation.ADD)
        }
    }

    private suspend fun runFavoriteRemove(token: Int, previous: Boolean, collections: CollectionLoadState.Loaded) {
        val favorite = collections.favoriteCollection ?: run {
            failFavorite(token, previous, FavoriteOperation.REMOVE)
            return
        }
        when (val removed = repository.removeRomFromCollection(favorite.id, romId)) {
            is LibraryResult.Success -> {
                if (token != mutationToken) return
                _state.update {
                    it.copy(
                        collections = (it.collections as? CollectionLoadState.Loaded)?.applyCollection(removed.data) ?: it.collections,
                        favorite = FavoriteUiState.Confirmed(false),
                    )
                }
                onLibraryMutated()
            }
            is LibraryResult.Failure -> failFavorite(token, previous, FavoriteOperation.REMOVE)
        }
    }

    private fun failFavorite(token: Int, previous: Boolean, operation: FavoriteOperation) {
        if (token != mutationToken) return
        _state.update {
            it.copy(favorite = FavoriteUiState.Confirmed(previous), alert = GameDetailAlert.FavoriteFailure(operation))
        }
    }

    // -----------------------------------------------------------------------
    // Collection picker
    // -----------------------------------------------------------------------

    fun onCollectionPickerRequested() {
        _state.update { it.copy(collectionDialog = CollectionDialogState.List) }
    }

    fun onCollectionSelected(collectionId: Long) {
        val current = _state.value
        val collections = current.collections as? CollectionLoadState.Loaded ?: return
        val collection = collections.ownedWritable.firstOrNull { it.id == collectionId } ?: return
        if (collection.romIds.contains(romId)) return // already a member; no network call
        if (!inFlightCollectionAdds.add(collectionId)) return // single-flight per row

        val token = ++mutationToken
        viewModelScope.launch {
            try {
                when (val added = repository.addRomToCollection(collectionId, romId)) {
                    is LibraryResult.Success -> {
                        if (token != mutationToken) return@launch
                        _state.update {
                            val c = it.collections as? CollectionLoadState.Loaded
                            if (c == null) it
                            else it.copy(collections = c.applyCollection(added.data), collectionDialog = null)
                        }
                        onLibraryMutated()
                    }
                    is LibraryResult.Failure -> {
                        if (token != mutationToken) return@launch
                        _state.update { it.copy(alert = GameDetailAlert.CollectionAddFailure(collectionId)) }
                    }
                }
            } finally {
                inFlightCollectionAdds.remove(collectionId)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Create collection
    // -----------------------------------------------------------------------

    fun onCreateCollectionRequested() {
        _state.update {
            it.copy(collectionDialog = CollectionDialogState.Creating(name = "", validationError = null, submitting = false))
        }
    }

    fun onCollectionNameChanged(value: String) {
        val dialog = _state.value.collectionDialog
        if (dialog is CollectionDialogState.Creating) {
            _state.update { it.copy(collectionDialog = dialog.copy(name = value, validationError = null)) }
        }
    }

    fun onCreateCollectionSubmitted() {
        val current = _state.value
        val dialog = current.collectionDialog as? CollectionDialogState.Creating ?: return
        if (dialog.submitting) return

        val trimmed = dialog.name.trim()
        val validationError = when {
            trimmed.isEmpty() -> "Please enter a collection name"
            trimmed.length > 400 -> "Collection name must be 400 characters or fewer"
            duplicateName(trimmed) -> "A collection with that name already exists"
            else -> null
        }
        if (validationError != null) {
            _state.update { it.copy(collectionDialog = dialog.copy(validationError = validationError, submitting = false)) }
            return
        }

        val token = ++mutationToken
        _state.update { it.copy(collectionDialog = dialog.copy(name = trimmed, submitting = true)) }
        viewModelScope.launch {
            when (val created = repository.createCollection(trimmed, isFavorite = false)) {
                is LibraryResult.Failure -> {
                    if (token != mutationToken) return@launch
                    val message = if (created.httpCode == 409) {
                        "A collection with that name already exists"
                    } else {
                        "Sorry, we are unable to create that collection right now, please try again later"
                    }
                    _state.update {
                        val d = it.collectionDialog as? CollectionDialogState.Creating
                        if (d == null) it else it.copy(collectionDialog = d.copy(validationError = message, submitting = false))
                    }
                }
                is LibraryResult.Success -> {
                    val newId = created.data.id
                    _state.update {
                        it.copy(collections = (it.collections as? CollectionLoadState.Loaded)?.applyCollection(created.data) ?: it.collections)
                    }
                    when (val added = repository.addRomToCollection(newId, romId)) {
                        is LibraryResult.Success -> {
                            if (token != mutationToken) return@launch
                            _state.update {
                                val c = it.collections as? CollectionLoadState.Loaded
                                if (c == null) it else it.copy(collections = c.applyCollection(added.data), collectionDialog = null)
                            }
                            onLibraryMutated()
                        }
                        is LibraryResult.Failure -> {
                            if (token != mutationToken) return@launch
                            // Do NOT delete the new collection; return to the list with it present.
                            _state.update {
                                it.copy(collectionDialog = CollectionDialogState.List, alert = GameDetailAlert.CreatedButAddFailed(newId))
                            }
                            onLibraryMutated()
                        }
                    }
                }
            }
        }
    }

    fun onCreateCollectionCancelled() {
        _state.update { it.copy(collectionDialog = CollectionDialogState.List) }
    }

    // -----------------------------------------------------------------------
    // Misc
    // -----------------------------------------------------------------------

    fun onAlertDismissed() {
        _state.update { it.copy(alert = null) }
    }

    fun onCollectionRetry() {
        _state.update { it.copy(collections = CollectionLoadState.Loading) }
        refreshCollections()
    }

    fun onDialogDismissed() {
        _state.update { it.copy(collectionDialog = null) }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun duplicateName(name: String): Boolean {
        val collections = _state.value.collections as? CollectionLoadState.Loaded ?: return false
        val lower = name.lowercase()
        val owned = collections.ownedWritable.map { it.name.lowercase() }
        val favorite = collections.favoriteCollection?.name?.lowercase()
        return owned.contains(lower) || (favorite != null && favorite == lower)
    }

    private fun CollectionLoadState.Loaded.applyCollection(c: CollectionSummary): CollectionLoadState.Loaded {
        val owned = if (ownedWritable.any { it.id == c.id }) {
            ownedWritable.map { if (it.id == c.id) c else it }
        } else {
            ownedWritable + c
        }
        val all = if (allVisible.any { it.id == c.id }) {
            allVisible.map { if (it.id == c.id) c else it }
        } else {
            allVisible + c
        }
        val favorite = if (c.isFavorite) c else favoriteCollection
        return copy(ownedWritable = owned, allVisible = all, favoriteCollection = favorite)
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

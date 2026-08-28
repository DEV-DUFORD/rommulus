package com.romm.androidtv.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives `GameDetailScreen` (UI_REFACTOR.md section 7). Owns a dedicated
 * immutable UI state and all the collection/favorite mutations the screen
 * exposes. The ROM detail is fetched independently of the owned-collection
 * lookup so a slow collection fetch never blocks cover/title/Play.
 *
 * **Stale protection**: [generation] guards the fetch coroutines while
 * [mutationToken] guards every mutation. Both are bumped on [refresh] so a
 * late, superseded response cannot overwrite newer state; the injected
 * [scope] is cancelled when the screen is disposed.
 *
 * Platform-neutral: all async work runs in the injected [scope] so the whole
 * presenter is exercisable by plain JVM unit tests (Linux port Phase 4).
 */
class RomDetailPresenter(
    private val scope: CoroutineScope,
    private val repository: LibraryRepository,
    private val romId: Long,
    refreshEvents: Flow<Unit>? = null,
    private val offlineDetail: (() -> RomDetail?)? = null,
    private val onLibraryMutated: () -> Unit = {},
) {

    private val _uiState = MutableStateFlow(
        RomDetailUiState(
            detail = SectionState.Loading,
            collections = CollectionLoadState.Loading,
            favorite = FavoriteUiState.Loading,
            collectionDialog = null,
            alert = null,
        ),
    )
    val uiState: StateFlow<RomDetailUiState> = _uiState.asStateFlow()

    private var generation = 0
    private var mutationToken = 0
    private var detailJob: Job? = null
    private var collectionJob: Job? = null
    private val inFlightCollectionMutations = mutableSetOf<Long>()

    /**
     * Set immediately before this presenter emits into the shared [onLibraryMutated] refresh
     * signal, so the very next event delivered back to this instance's own [refreshEvents]
     * collector — which is the *same* shared flow, wired by `MainActivity` so favorite/platform
     * shelves elsewhere in the app refresh too — is skipped. Without this, every successful
     * favorite toggle or collection add triggered a full `refresh()` of this screen's own detail
     * and collections (back to `SectionState.Loading`), even though the icon/list state here was
     * already updated in place: a visible full-screen flash/reset for a change that only needed
     * to flip one icon.
     */
    @Volatile private var suppressNextSelfRefresh = false

    init {
        refresh()
        refreshEvents?.let { events ->
            scope.launch {
                events.collect {
                    if (suppressNextSelfRefresh) {
                        suppressNextSelfRefresh = false
                    } else {
                        refresh()
                    }
                }
            }
        }
    }

    /** Notifies other screens (favorites/collection shelves) that library membership changed, without re-fetching this screen's own already-updated state. */
    private fun notifyLibraryMutated() {
        suppressNextSelfRefresh = true
        onLibraryMutated()
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
        _uiState.update { it.copy(detail = SectionState.Loading) }
        detailJob = scope.launch {
            val resultState = when (val result = repository.fetchRomDetail(romId)) {
                is LibraryResult.Success -> SectionState.Loaded(result.data)
                is LibraryResult.Failure -> offlineDetail?.invoke()?.let { SectionState.Loaded(it) }
                    ?: SectionState.Error(result.error)
            }
            if (capturedGeneration == generation) {
                _uiState.update { it.copy(detail = resultState) }
            }
        }
    }

    private fun refreshCollections() {
        val capturedGeneration = generation
        collectionJob?.cancel()
        // Keep favorite in Loading until membership is known.
        _uiState.update {
            it.copy(collections = CollectionLoadState.Loading, favorite = FavoriteUiState.Loading)
        }
        collectionJob = scope.launch {
            when (val result = repository.fetchOwnedWritableCollections()) {
                is LibraryResult.Success -> {
                    val loaded = CollectionLoadState.Loaded(
                        allVisible = result.data,
                        ownedWritable = result.data.filter { !it.isFavorite && !it.isSmart && !it.isVirtual },
                        favoriteCollection = result.data.firstOrNull { it.isFavorite },
                    )
                    val confirmed = loaded.favoriteCollection?.romIds?.contains(romId) == true
                    if (capturedGeneration == generation) {
                        _uiState.update {
                            it.copy(collections = loaded, favorite = FavoriteUiState.Confirmed(confirmed))
                        }
                    }
                }
                is LibraryResult.Failure -> {
                    if (capturedGeneration == generation) {
                        _uiState.update { it.copy(collections = CollectionLoadState.Error(result.error)) }
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Favorite toggle
    // -----------------------------------------------------------------------

    fun onFavoriteSelected() {
        val current = _uiState.value
        if (current.favorite is FavoriteUiState.Loading || current.favorite is FavoriteUiState.Updating) return
        val collections = current.collections as? CollectionLoadState.Loaded ?: return
        val confirmed = (current.favorite as? FavoriteUiState.Confirmed)?.isFavorite ?: false
        val target = !confirmed
        val token = ++mutationToken
        _uiState.update { it.copy(favorite = FavoriteUiState.Updating(confirmed, target)) }

        scope.launch {
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
                    _uiState.update { it.copy(collections = (it.collections as? CollectionLoadState.Loaded)?.applyCollection(created.data) ?: it.collections) }
                }
                is LibraryResult.Failure -> {
                    // Duplicate/conflict race: another request created the favorite. Refetch once.
                    if (created.httpCode == 409) {
                        val refetched = repository.fetchOwnedWritableCollections()
                        val discovered = (refetched as? LibraryResult.Success)?.data?.firstOrNull { it.isFavorite }
                        if (discovered != null) {
                            if (token != mutationToken) return
                            favorite = discovered
                            _uiState.update { it.copy(collections = (it.collections as? CollectionLoadState.Loaded)?.applyCollection(discovered) ?: it.collections) }
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
                _uiState.update {
                    it.copy(
                        collections = (it.collections as? CollectionLoadState.Loaded)?.applyCollection(added.data) ?: it.collections,
                        favorite = FavoriteUiState.Confirmed(true),
                    )
                }
                notifyLibraryMutated()
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
                _uiState.update {
                    it.copy(
                        collections = (it.collections as? CollectionLoadState.Loaded)?.applyCollection(removed.data) ?: it.collections,
                        favorite = FavoriteUiState.Confirmed(false),
                    )
                }
                notifyLibraryMutated()
            }
            is LibraryResult.Failure -> failFavorite(token, previous, FavoriteOperation.REMOVE)
        }
    }

    private fun failFavorite(token: Int, previous: Boolean, operation: FavoriteOperation) {
        if (token != mutationToken) return
        _uiState.update {
            it.copy(favorite = FavoriteUiState.Confirmed(previous), alert = GameDetailAlert.FavoriteFailure(operation))
        }
    }

    // -----------------------------------------------------------------------
    // Collection picker
    // -----------------------------------------------------------------------

    fun onCollectionPickerRequested() {
        _uiState.update { it.copy(collectionDialog = CollectionDialogState.List) }
    }

    /**
     * Toggles the ROM's membership in [collectionId]: adds it if absent, removes it if already
     * a member (the checkmark shown in [AddToCollectionDialog] denotes current membership, so
     * selecting a checked row is the natural "undo" gesture rather than a no-op).
     */
    fun onCollectionSelected(collectionId: Long) {
        val current = _uiState.value
        val collections = current.collections as? CollectionLoadState.Loaded ?: return
        val collection = collections.ownedWritable.firstOrNull { it.id == collectionId } ?: return
        val isMember = collection.romIds.contains(romId)
        if (!inFlightCollectionMutations.add(collectionId)) return // single-flight per row

        val token = ++mutationToken
        scope.launch {
            try {
                val result = if (isMember) {
                    repository.removeRomFromCollection(collectionId, romId)
                } else {
                    repository.addRomToCollection(collectionId, romId)
                }
                when (result) {
                    is LibraryResult.Success -> {
                        if (token != mutationToken) return@launch
                        _uiState.update {
                            val c = it.collections as? CollectionLoadState.Loaded
                            if (c == null) it
                            else it.copy(collections = c.applyCollection(result.data), collectionDialog = null)
                        }
                        notifyLibraryMutated()
                    }
                    is LibraryResult.Failure -> {
                        if (token != mutationToken) return@launch
                        _uiState.update {
                            it.copy(
                                alert = if (isMember) {
                                    GameDetailAlert.CollectionRemoveFailure(collectionId)
                                } else {
                                    GameDetailAlert.CollectionAddFailure(collectionId)
                                },
                            )
                        }
                    }
                }
            } finally {
                inFlightCollectionMutations.remove(collectionId)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Create collection
    // -----------------------------------------------------------------------

    fun onCreateCollectionRequested() {
        _uiState.update {
            it.copy(collectionDialog = CollectionDialogState.Creating(name = "", validationError = null, submitting = false))
        }
    }

    fun onCollectionNameChanged(value: String) {
        val dialog = _uiState.value.collectionDialog
        if (dialog is CollectionDialogState.Creating) {
            _uiState.update { it.copy(collectionDialog = dialog.copy(name = value, validationError = null)) }
        }
    }

    fun onCreateCollectionSubmitted() {
        val current = _uiState.value
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
            _uiState.update { it.copy(collectionDialog = dialog.copy(validationError = validationError, submitting = false)) }
            return
        }

        val token = ++mutationToken
        _uiState.update { it.copy(collectionDialog = dialog.copy(name = trimmed, submitting = true)) }
        scope.launch {
            when (val created = repository.createCollection(trimmed, isFavorite = false)) {
                is LibraryResult.Failure -> {
                    if (token != mutationToken) return@launch
                    val message = if (created.httpCode == 409) {
                        "A collection with that name already exists"
                    } else {
                        "Sorry, we are unable to create that collection right now, please try again later"
                    }
                    _uiState.update {
                        val d = it.collectionDialog as? CollectionDialogState.Creating
                        if (d == null) it else it.copy(collectionDialog = d.copy(validationError = message, submitting = false))
                    }
                }
                is LibraryResult.Success -> {
                    val newId = created.data.id
                    _uiState.update {
                        it.copy(collections = (it.collections as? CollectionLoadState.Loaded)?.applyCollection(created.data) ?: it.collections)
                    }
                    when (val added = repository.addRomToCollection(newId, romId)) {
                        is LibraryResult.Success -> {
                            if (token != mutationToken) return@launch
                            _uiState.update {
                                val c = it.collections as? CollectionLoadState.Loaded
                                if (c == null) it else it.copy(collections = c.applyCollection(added.data), collectionDialog = null)
                            }
                            notifyLibraryMutated()
                        }
                        is LibraryResult.Failure -> {
                            if (token != mutationToken) return@launch
                            // Do NOT delete the new collection; return to the list with it present.
                            _uiState.update {
                                it.copy(collectionDialog = CollectionDialogState.List, alert = GameDetailAlert.CreatedButAddFailed(newId))
                            }
                            notifyLibraryMutated()
                        }
                    }
                }
            }
        }
    }

    fun onCreateCollectionCancelled() {
        _uiState.update { it.copy(collectionDialog = CollectionDialogState.List) }
    }

    // -----------------------------------------------------------------------
    // Misc
    // -----------------------------------------------------------------------

    fun onAlertDismissed() {
        _uiState.update { it.copy(alert = null) }
    }

    fun onCollectionRetry() {
        _uiState.update { it.copy(collections = CollectionLoadState.Loading) }
        refreshCollections()
    }

    fun onDialogDismissed() {
        _uiState.update { it.copy(collectionDialog = null) }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun duplicateName(name: String): Boolean {
        val collections = _uiState.value.collections as? CollectionLoadState.Loaded ?: return false
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
}

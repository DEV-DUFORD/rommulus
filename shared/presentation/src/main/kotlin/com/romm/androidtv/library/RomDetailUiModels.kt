package com.romm.androidtv.library

import com.romm.androidtv.romm.RommApiError

// ---------------------------------------------------------------------------
// Public Game Detail state model (drives `GameDetailScreen`, UI_REFACTOR.md §7).
//
// Deliberately framework-free (no Compose, no ViewModel imports) so the UI
// state can be produced and asserted by plain JVM unit tests.
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
    data class CollectionRemoveFailure(val collectionId: Long) : GameDetailAlert
    data class CreatedButAddFailed(val collectionId: Long) : GameDetailAlert
}

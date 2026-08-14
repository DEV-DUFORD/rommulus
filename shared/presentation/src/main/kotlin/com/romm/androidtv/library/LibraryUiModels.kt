package com.romm.androidtv.library

import com.romm.androidtv.romm.RommApiError

/**
 * Pure UI state models for the library screens (Home + paginated ROM grid).
 *
 * Deliberately framework-free (no Compose, no ViewModel imports) so the UI
 * state can be produced and asserted by plain JVM unit tests.
 */

/** Loading/success/error/empty state for one independent section of the Home screen. */
sealed interface SectionState<out T> {
    object Loading : SectionState<Nothing>
    data class Loaded<T>(val data: T) : SectionState<T>
    data class Error(val error: RommApiError) : SectionState<Nothing>
}

data class HomeUiState(
    val continuePlaying: SectionState<List<LibraryRom>> = SectionState.Loading,
    val recentlyAdded: SectionState<List<LibraryRom>> = SectionState.Loading,
    val favorites: SectionState<List<LibraryRom>> = SectionState.Loading,
    val platforms: SectionState<List<PlatformSummary>> = SectionState.Loading,
    val collections: SectionState<List<CollectionSummary>> = SectionState.Loading,
)

/** Drives a paginated ROM grid (`PlatformDetailScreen`/`CollectionDetailScreen`, UI_REFACTOR.md section 7). */
data class RomGridUiState(
    val section: SectionState<List<LibraryRom>> = SectionState.Loading,
    val total: Int = 0,
    val isLoadingMore: Boolean = false,
    /**
     * Raw count of roms fetched from the server so far (before any client-side de-dup),
     * used as the next page's `offset`. Kept separate from the displayed list's size because
     * that list is de-duplicated by id (see [loadMore]'s doc) and can be smaller than the raw
     * fetch count.
     */
    val rawFetchedCount: Int = 0,
)

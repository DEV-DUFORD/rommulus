package com.romm.desktop.ui.screens.library

import com.romm.androidtv.library.PlatformSummary
import com.romm.androidtv.library.RomQuery
import com.romm.androidtv.library.SearchUiState
import com.romm.androidtv.library.SectionState
import com.romm.androidtv.romm.RommApiError
import com.romm.desktop.Screen

/**
 * Pure, framework-free logic shared by the desktop Home / RomGrid / Search
 * screens (Phase 6). Extracted out of the composables so it is unit-testable
 * without a Compose runtime.
 */

/** The four renderable shapes of a [SectionState] shelf. */
enum class SectionDisplayState { LOADING, CONTENT, ERROR, EMPTY }

/**
 * Maps a [SectionState] to its display shape. An empty [SectionState.Loaded]
 * maps to [SectionDisplayState.EMPTY] so callers can omit the shelf entirely
 * (Android Home parity: never render an empty row).
 */
fun <T> sectionDisplayState(state: SectionState<List<T>>): SectionDisplayState = when (state) {
    is SectionState.Loading -> SectionDisplayState.LOADING
    is SectionState.Error -> SectionDisplayState.ERROR
    is SectionState.Loaded -> if (state.data.isEmpty()) SectionDisplayState.EMPTY else SectionDisplayState.CONTENT
}

/**
 * The "should load more" predicate for scroll-end pagination (the pure core of
 * the `LoadMoreOnScrollEnd` composables): true once the last visible grid item
 * index is within [threshold] items of the end of the list. `itemCount == 0`
 * never triggers (nothing loaded yet).
 */
fun shouldLoadMoreOnScrollEnd(lastVisibleIndex: Int, itemCount: Int, threshold: Int = 6): Boolean =
    itemCount > 0 && lastVisibleIndex >= itemCount - threshold

/**
 * Builds the [RomQuery] for a grid screen from the coordinator's selection
 * state ([DesktopAppCoordinator.selectedPlatformId] / [DesktopAppCoordinator.selectedCollectionId]).
 * Platform selection wins when both are set (they should not be). Returns null
 * when neither is selected.
 */
fun romQueryForSelection(platformId: Long?, collectionId: Long?): RomQuery? = when {
    platformId != null -> RomQuery.ByPlatform(platformId)
    collectionId != null -> RomQuery.ByCollection(collectionId)
    else -> null
}

/**
 * The [Screen] a ROM detail opened from a grid with this [query] should return
 * to on Back — the value to pass as [com.romm.desktop.DesktopAppCoordinator.openGameDetail]'s
 * `parent` parameter.
 */
fun gridParentScreen(query: RomQuery): Screen = when (query) {
    is RomQuery.ByPlatform -> Screen.PLATFORM_DETAIL
    is RomQuery.ByCollection -> Screen.COLLECTION_DETAIL
    else -> Screen.HOME
}

/**
 * Builds the fallback chain for a platform tile: RomM's bundled glyph
 * candidates (SVG then ICO) first, followed by the metadata-provider logo
 * (e.g. an IGDB photo/wordmark).
 */
fun platformTileImageUrls(platform: PlatformSummary): List<String> =
    (platform.iconUrlCandidates + listOfNotNull(platform.iconUrl, platform.logoUrl))
        .filter(String::isNotBlank)
        .distinct()

fun platformTileImageUrl(platform: PlatformSummary): String? =
    platformTileImageUrls(platform).firstOrNull()

/** The result-count number to display for a [SearchUiState], mirroring the Android label rule:
 * hide-unsupported ON → visible count; OFF → server total. */
fun searchResultCount(state: SearchUiState): Int =
    if (state.hideUnsupportedSystems) state.roms.size else state.total

/** Human-readable error text, e.g. `SERVER_ERROR` → `server error`. */
fun errorMessage(error: RommApiError): String = error.name.lowercase().replace('_', ' ')

/** "1 result" / "42 results" — mirrors the Android search count label. */
fun resultCountLabel(count: Int): String = "$count result${if (count != 1) "s" else ""}"

package com.romm.androidtv.library

import com.romm.androidtv.romm.RommApiError

/** UI state emitted by [SearchPresenter]. */
data class SearchUiState(
    /** The current query text shown in the input field — always preserves exactly what the user typed, including trailing/leading spaces. */
    val query: String = "",
    /** Whether a network request is currently in flight. */
    val isLoading: Boolean = false,
    /** Accumulated (possibly filtered) search results across pages. */
    val roms: List<LibraryRom> = emptyList(),
    /** Total number of matching ROMs on the server (0 until first page loads). */
    val total: Int = 0,
    /** Error from the last failed request, or null if no error occurred. */
    val error: RommApiError? = null,
    /** Normalized (trimmed) term used for API calls and pagination. Null when idle. Decouples display text from request term so leading/trailing spaces are never lost in the TextField. */
    val activeQuery: String? = null,
    /** Cumulative count of unfiltered items received from the server across all pages.
     * Used exclusively for computing the correct server offset and termination;
     * does not change when [roms] is filtered by the hide-unsupported toggle. */
    val rawFetchedCount: Int = 0,
    /** Whether the hide-unsupported-systems filter was active during the most recent fetch.
     * Snapshotted once per operation so filtering and UI state stay consistent.
     * When true the result-count label always shows visible count; when false it shows server total. */
    val hideUnsupportedSystems: Boolean = true,
)

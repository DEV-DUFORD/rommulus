package com.romm.androidtv.library

/**
 * Domain models for the native browsing UI (UI_REFACTOR.md). These are
 * deliberately separate from [com.romm.androidtv.romm.RomInfo] et al., which
 * are scoped to single-ROM launch/staging, not list browsing.
 */

/** One entry in a `GET /api/platforms` listing. */
data class PlatformSummary(
    val id: Long,
    /** Prefer the server-computed `display_name` (falls back to custom_name, then name). */
    val displayName: String,
    val romCount: Int,
    /** Absolute external logo URL (e.g. IGDB CDN), or null if RomM has none on file. */
    val logoUrl: String?,
)

/** One entry in a `GET /api/roms` listing (a row in a Home shelf, or a search/platform result). */
data class LibraryRom(
    val id: Long,
    val title: String,
    val platformDisplayName: String,
    /** Absolute cover-art URL, already resolved against the RomM origin. Null if RomM has no cover on file. */
    val coverUrl: String?,
    /** ISO 8601 last-played timestamp for the current user, or null if never played. */
    val lastPlayedIso: String?,
    val nowPlaying: Boolean,
)

/** One entry in a `GET /api/collections` listing. */
data class CollectionSummary(
    val id: Long,
    val name: String,
    val romCount: Int,
    /** Absolute cover-art URL, already resolved against the RomM origin. Null if none available. */
    val coverUrl: String?,
)

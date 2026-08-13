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
    /**
     * Absolute URL for RomM's own bundled platform glyph, served at
     * `{origin}/assets/platforms/{slug}.svg` — the same icon set the webapp
     * shows in its Platforms grid. Preferred over [logoUrl] (an IGDB/etc.
     * metadata-provider image, which is often a photo or brand wordmark
     * rather than a small icon) since it's what users expect to see. Null
     * if [slug] is blank.
     */
    val iconUrl: String? = null,
    /**
     * Ordered fallback chain for RomM's bundled platform icon (SVG, then
     * ICO), mirroring the webapp's own resolution order — not every platform
     * has an SVG on file (e.g. Sega CD/Saturn/Master System serve only
     * `.ico`). [iconUrl] is always this list's first element when non-empty.
     */
    val iconUrlCandidates: List<String> = listOfNotNull(iconUrl),
    /** RomM's canonical platform slug (e.g. "gb", "genesis") — used to check native core support. Blank if the server did not return one. */
    val slug: String = "",
)

/** One entry in a `GET /api/roms` listing (a row in a Home shelf, or a search/platform result). */
data class LibraryRom(
    val id: Long,
    val title: String,
    val platformDisplayName: String,
    /** RomM's canonical platform slug (e.g. "gb", "genesis") — used to check native core support (LIBRETRO_REFACTOR.md section 13). */
    val platformSlug: String = "",
    /** Absolute cover-art URL, already resolved against the RomM origin. Null if RomM has no cover on file. */
    val coverUrl: String?,
    /** ISO 8601 last-played timestamp for the current user, or null if never played. */
    val lastPlayedIso: String?,
    val nowPlaying: Boolean,
)

/** One entry in a `GET /api/collections` listing, or the result of a collection mutation. */
data class CollectionSummary(
    val id: Long,
    val name: String,
    val romCount: Int,
    /** Absolute cover-art URL, already resolved against the RomM origin. Null if none available. */
    val coverUrl: String?,
    /** IDs of the roms currently in the collection (from RomM's `rom_ids`). */
    val romIds: Set<Long> = emptySet(),
    /** True if the collection is shared publicly (RomM's `is_public`). */
    val isPublic: Boolean = false,
    /** True if the current user has marked the collection as a favorite (RomM's `is_favorite`). */
    val isFavorite: Boolean = false,
    /** True for RomM's built-in virtual collections (e.g. "All" or "Favorites"). */
    val isVirtual: Boolean = false,
    /** True for smart collections whose membership is rule-driven rather than explicit. */
    val isSmart: Boolean = false,
    /** Server-side id of the collection's owner. */
    val userId: Long = 0L,
    /** Username of the collection's owner (RomM's `owner_username`). */
    val ownerUsername: String = "",
)

/**
 * One other rom entry that's a "sibling" (a different version of the same game —
 * e.g. a different disc, region, or revision — grouped server-side by shared
 * external metadata ID). Mirrors RomM's `SiblingRomSchema`. Powers the
 * "Choose Version" affordance on `GameDetailScreen`.
 */
data class SiblingRomInfo(
    val id: Long,
    val title: String,
    /**
     * The exact per-file name (tags like "(Disc 1)" kept, extension stripped). All siblings
     * in a group usually share the same [title] (the game's metadata name), so this is what
     * actually distinguishes one version from another in the "Choose Version" picker.
     */
    val fileName: String,
    /** True if this is the version the current user has marked as their default for the group. */
    val isMainSibling: Boolean,
)

/**
 * Full detail for a single ROM (`GET /api/roms/{id}`, the full `RomSchema` —
 * not the `SimpleRomSchema` used by list endpoints). Powers `GameDetailScreen`.
 */
data class RomDetail(
    val id: Long,
    val title: String,
    val platformDisplayName: String,
    /** RomM's canonical platform slug (e.g. "gb", "genesis") — used to check native core support (LIBRETRO_REFACTOR.md section 13). */
    val platformSlug: String = "",
    val summary: String?,
    val coverUrl: String?,
    /** Absolute screenshot URLs, already resolved against the RomM origin. */
    val screenshotUrls: List<String>,
    val genres: List<String>,
    val companies: List<String>,
    val gameModes: List<String>,
    val playerCount: String?,
    /** Epoch millis of first release, or null if unknown. */
    val firstReleaseDateEpochMillis: Long?,
    /** 0-100 scale, or null if unknown. */
    val averageRating: Float?,
    val regions: List<String>,
    val languages: List<String>,
    val fileSizeBytes: Long,
    val lastPlayedIso: String?,
    val nowPlaying: Boolean,
    /**
     * The exact per-file name (tags like "(Disc 1)" kept, extension stripped) for this specific
     * rom entry — distinguishes it from its [siblingRoms] in the "Choose Version" picker, since
     * they usually all share the same [title] (the game's metadata name).
     */
    val fileName: String = "",
    /**
     * Other versions of this same game (different disc/region/revision), if RomM has
     * grouped any siblings for it server-side. Empty for the vast majority of roms.
     */
    val siblingRoms: List<SiblingRomInfo> = emptyList(),
)

/** One page of a paginated `GET /api/roms` query, e.g. for a platform/collection detail grid. */
data class RomPage(
    val roms: List<LibraryRom>,
    val total: Int,
)

package com.romm.androidtv.library

import com.romm.androidtv.emulation.model.CoreManifest

/**
 * Whether [platformSlug] (a RomM platform slug, e.g. "gb", "genesis") has at
 * least one native core approved in [CoreManifest] (LIBRETRO_REFACTOR.md
 * section 13, Phase 6: "For unsupported systems ... show a clear native
 * 'not supported yet' message"). This is the same resolution
 * [com.romm.androidtv.romm.RomRepository.stageForLaunch] uses reactively
 * after a failed Play attempt — this function lets the library/game-detail
 * UI check the same thing proactively, before the user presses Play.
 */
fun isPlatformNativelySupported(platformSlug: String): Boolean =
    platformSlug.isNotBlank() && CoreManifest.approvedEntries().any { it.supportedSystems.contains(platformSlug) }

/**
 * Filters out ROMs whose platform has no approved native core, when [hide]
 * is true. Used by the opt-in "Hide unsupported-system games" Settings
 * toggle (off by default; LIBRETRO_REFACTOR.md section 13).
 */
fun List<LibraryRom>.filterUnsupportedIfHidden(hide: Boolean): List<LibraryRom> =
    if (hide) filter { isPlatformNativelySupported(it.platformSlug) } else this

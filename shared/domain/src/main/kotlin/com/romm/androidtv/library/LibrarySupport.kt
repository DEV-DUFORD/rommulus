package com.romm.androidtv.library

import com.romm.androidtv.emulation.model.CoreManifest
import com.romm.androidtv.emulation.model.ANDROID_CORE_ABIS

/**
 * Whether [platformSlug] (a RomM platform slug, e.g. "gb", "genesis") has at
 * least one native core approved in [CoreManifest] (LIBRETRO_REFACTOR.md
 * section 13, Phase 6: "For unsupported systems ... show a clear native
 * 'not supported yet' message"). This is the same resolution
 * [com.romm.androidtv.romm.RomRepository.stageForLaunch] uses reactively
 * after a failed Play attempt — this function lets the library/game-detail
 * UI check the same thing proactively, before the user presses Play.
 */
fun isPlatformNativelySupported(
    platformSlug: String,
    supportedAbis: Set<String> = ANDROID_CORE_ABIS,
): Boolean =
    platformSlug.isNotBlank() && CoreManifest.approvedEntries().any {
        it.supportedSystems.contains(platformSlug) && it.supportedAbis.any(supportedAbis::contains)
    }

/**
 * Filters out ROMs whose platform has no approved native core, when [hide]
 * is true. Used by the opt-in "Hide unsupported-system games" Settings
 * toggle (off by default; LIBRETRO_REFACTOR.md section 13).
 */
fun List<LibraryRom>.filterUnsupportedIfHidden(
    hide: Boolean,
    supportedAbis: Set<String> = ANDROID_CORE_ABIS,
): List<LibraryRom> =
    if (hide) filter { isPlatformNativelySupported(it.platformSlug, supportedAbis) } else this

/**
 * Filters out platforms that have no approved native core, when [hide] is true.
 * Used by the same Settings toggle to hide unsupported platform cards from the
 * Platforms grid on the Home screen. Named distinctly from the LibraryRom variant
 * to avoid JVM signature clashes (type-erased List<T> extensions).
 */
fun List<PlatformSummary>.filterUnsupportedPlatformsIfHidden(
    hide: Boolean,
    supportedAbis: Set<String> = ANDROID_CORE_ABIS,
): List<PlatformSummary> =
    if (hide) filter { isPlatformNativelySupported(it.slug, supportedAbis) } else this

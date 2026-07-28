package com.romm.androidtv.romm

import com.romm.androidtv.emulation.model.LaunchSpec

/**
 * Fetches canonical ROM metadata and staged content from RomM
 * (LIBRETRO_REFACTOR.md sections 6 and 10). No implementation exists yet:
 * this is a Phase 1 seam so later phases can depend on an interface instead
 * of reaching into `MainActivity` or raw OkHttp calls.
 *
 * Implementations must use RomM's native endpoints (`GET /api/roms/{id}`,
 * `GET /api/roms/{id}/content/{file_name}`, etc.) rather than constructing
 * server filesystem paths, and must never expose a partially-downloaded file.
 */
interface RomRepository {
    /** Fetches canonical metadata for one ROM ID. Throws or returns null on any ambiguity. */
    suspend fun fetchRomMetadata(romId: Long): RomMetadata?

    /**
     * Ensures the ROM's content is downloaded, validated, and staged in app-private
     * storage, returning a [LaunchSpec] ready to hand to the emulation process.
     */
    suspend fun stageForLaunch(romId: Long): LaunchSpec
}

/** Minimal canonical ROM metadata needed to select a core and stage content. */
data class RomMetadata(
    val romId: Long,
    val fileName: String,
    val platformSlug: String,
)

package com.romm.androidtv.emulation.model

import java.io.File

/**
 * Pure, testable implementation of the durable-save directory layout from
 * LIBRETRO_REFACTOR.md section 11.1:
 *
 * ```text
 * files/saves/<server-key>/<user-key>/<rom-id>/<rom-sha256>/autosave/<core-memory-id>.srm
 * ```
 *
 * Scoping the path by [romHash] (not just [romId]) is what makes "verify the
 * current ROM hash and exact post-`retro_load_game()` SRAM size" (section
 * 11.1) structurally true rather than merely a runtime check: a different
 * ROM hash for the same [romId] (a re-uploaded/replaced file, or a different
 * revision) naturally resolves to a completely different directory, so an
 * incompatible save can never even be looked up by accident, let alone
 * applied. This directory is entirely separate from [ContentCache][com.romm.androidtv.cache.ContentCache]'s
 * evictable tree — nothing here is ever a cache eviction candidate.
 */
object SavePathPolicy {

    /** Stable identifier for the only Libretro memory region this build persists. */
    const val SAVE_RAM_MEMORY_ID = "srm"

    /** Only slot name supported in the first release (LaunchSpec.saveSlot's default). */
    const val AUTOSAVE_SLOT = "autosave"

    /**
     * Returns the absolute path to the autosave SRAM file for one server +
     * user + ROM + verified-content-hash scope, under [filesDir] (an app's
     * `Context.filesDir`, passed in rather than a `Context` itself so this
     * stays a pure function testable without any Android framework
     * dependency).
     */
    fun autosaveSramPath(
        filesDir: File,
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        memoryId: String = SAVE_RAM_MEMORY_ID,
    ): String {
        require(serverKey.isNotBlank()) { "serverKey must not be blank" }
        require(userKey.isNotBlank()) { "userKey must not be blank" }
        require(romId > 0) { "romId must be a positive RomM ROM ID" }
        require(romHash.isNotBlank()) { "romHash must not be blank" }

        return File(
            filesDir,
            listOf(
                "saves",
                sanitizeSegment(serverKey),
                sanitizeSegment(userKey),
                romId.toString(),
                sanitizeSegment(romHash),
                AUTOSAVE_SLOT,
                "$memoryId.srm",
            ).joinToString(File.separator)
        ).absolutePath
    }

    /**
     * Replaces path-separator and parent-traversal characters that could
     * otherwise let a malicious or malformed server-provided string (a
     * username, a hash) escape the intended directory structure. None of
     * [serverKey]/[userKey]/[romHash] are ever attacker-controlled in
     * practice (they come from an authenticated session and a locally
     * verified SHA-256), but this makes that escape structurally impossible
     * rather than merely unlikely.
     *
     * Also the canonical key derivation for save-scope keys: replicas are
     * PERSISTED under sanitized keys, so any caller that queries a store or
     * path by a raw origin/username (e.g. the desktop save-status lookup)
     * must apply this too — an unsanitized query can never match.
     */
    fun sanitizeSegment(segment: String): String {
        val windows = File.separatorChar == '\\'
        return segment.map { c ->
            val forbiddenOnWindows = windows && (c < ' ' || c in "<>:\"|?*")
            if (c == '/' || c == '\\' || c == '\u0000' || forbiddenOnWindows) '_' else c
        }
            .joinToString("")
            .let { if (it == "." || it == "..") "_" else it }
            .let { if (windows) it.trimEnd(' ', '.') else it }
            .ifEmpty { "_" }
    }
}

package com.romm.androidtv.library.ui

/**
 * Pure-UI row model for one entry in the native version picker (game-detail
 * "Choose Version" flow — multi-disc/region/revision sibling roms). Carries
 * only what the screen needs to render; the caller (MainActivity) maps
 * [com.romm.androidtv.library.RomDetail] and its [com.romm.androidtv.library.SiblingRomInfo]
 * list into this.
 */
data class VersionPickerEntryUiModel(
    val romId: Long,
    /**
     * The exact per-file name for this version (e.g. "Ape Escape (Disc 1)") — shown as the
     * row's primary label since sibling versions usually all share the same metadata title.
     */
    val fileName: String,
    /** True when this is the version the game-detail screen was opened from. */
    val isCurrentVersion: Boolean,
    /** True when this is the version the current user has marked as their default for the group. */
    val isMainSibling: Boolean,
)

/** Pure-UI model for the version-picker screen's full state. */
data class VersionPickerUiModel(
    /** The base title shared across versions, shown as the screen's subtitle. */
    val gameTitle: String,
    val entries: List<VersionPickerEntryUiModel>,
)

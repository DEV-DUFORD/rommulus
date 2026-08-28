package com.romm.desktop.ui.screens.detail

import com.romm.androidtv.library.RomDetail

/**
 * Pure-UI row model for one entry in the desktop version picker ("Choose File" flow —
 * multi-disc/region/revision sibling ROMs). Desktop mirror of Android's
 * `VersionPickerEntryUiModel`; carries only what the overlay needs to render.
 */
data class VersionPickerEntryUiModel(
    val romId: Long,
    /**
     * The exact per-file name for this version (e.g. "Ape Escape (Disc 1)" — tags kept,
     * extension already stripped by the API layer) — the row's primary label, since sibling
     * versions usually all share the same metadata title.
     */
    val fileName: String,
    /** True when this is the version the game-detail screen was opened from. */
    val isCurrentVersion: Boolean,
    /** True when this is the version the current user has marked as their default for the group. */
    val isMainSibling: Boolean,
)

/** Pure-UI model for the desktop version picker's full state. */
data class VersionPickerUiModel(
    /** The base title shared across versions, shown under the "Choose Game File" heading. */
    val gameTitle: String,
    val entries: List<VersionPickerEntryUiModel>,
)

/**
 * Builds the version picker's entries from a loaded ROM and its siblings — 1:1 with Android's
 * `MainActivity.nativeLibraryOnChooseVersion`: the current ROM comes first (checked as the open
 * version), then each sibling in server order. Per-file names distinguish the versions; blank
 * names fall back to the (shared) title. When no sibling is marked as the group default, the
 * current version takes the "Default version" badge so the group always has exactly one.
 */
fun buildVersionPickerEntries(rom: RomDetail): List<VersionPickerEntryUiModel> = buildList {
    add(
        VersionPickerEntryUiModel(
            romId = rom.id,
            fileName = rom.fileName.ifBlank { rom.title },
            isCurrentVersion = true,
            isMainSibling = rom.siblingRoms.none { it.isMainSibling },
        ),
    )
    rom.siblingRoms.forEach { sibling ->
        add(
            VersionPickerEntryUiModel(
                romId = sibling.id,
                fileName = sibling.fileName.ifBlank { sibling.title },
                isCurrentVersion = false,
                isMainSibling = sibling.isMainSibling,
            ),
        )
    }
}

/** The "Choose File" button renders only when the ROM has sibling versions (Android parity). */
fun shouldShowChooseFileButton(rom: RomDetail): Boolean = rom.siblingRoms.isNotEmpty()

package com.romm.androidtv.library.ui

/**
 * Pure-UI row model for one entry in the native save picker (game-detail "Choose Save" flow).
 * Carries only what the screen needs to render; never reaches Room or the network directly —
 * the caller (MainActivity) maps [com.romm.androidtv.romm.ServerSaveInfo] rows into this.
 */
data class SavePickerEntryUiModel(
    val saveId: Long,
    /** Display file name, e.g. "autosave [2026-07-31_00-55-06].srm". */
    val fileName: String,
    /** Core/emulator badge, e.g. "sameboy". Null when unknown. */
    val coreId: String?,
    /** File size in human-readable form ("12 KB"), or null if unavailable. */
    val sizeText: String?,
    /** Relative or absolute timestamp text for display, or null if unavailable. */
    val updatedAtText: String?,
    /** True when this is the save currently adopted as this device's local autosave. */
    val isCurrentlyAdopted: Boolean,
    /**
     * Server content hash, carried through (not displayed) so the caller can pass it to
     * [com.romm.androidtv.romm.save.SaveSyncCoordinator.adoptChosenSave] on selection without
     * a second network round-trip. Null when the server didn't report one.
     */
    val contentHash: String? = null,
    /**
     * When non-null, this save belongs to a different game file of the same multi-disc game
     * (a sibling ROM). Shows a "From <file>" tag so the user knows loading it carries a save
     * over from another disc. Null for saves of the currently-launched game file.
     */
    val sourceFileLabel: String? = null,
)

/** Pure-UI model for the save-picker screen's full state. */
data class SavePickerUiModel(
    val romTitle: String,
    val entries: List<SavePickerEntryUiModel>,
)

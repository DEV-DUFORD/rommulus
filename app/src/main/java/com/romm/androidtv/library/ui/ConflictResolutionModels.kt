package com.romm.androidtv.library.ui

/**
 * Pure-UI metadata for one side of a save conflict or quarantine display.
 * Carries only what the screen needs to render; never reaches Room or the network.
 */
data class SaveConflictSide(
    /** "Local" or "Server" — determines which column this row belongs to. */
    val label: String,

    /** RomM save ID (server side) or synthetic local identifier. Null when unknown. */
    val saveId: Long?,

    /** Display file name (e.g. "autosave.srm"). */
    val fileName: String,

    /** SHA-256 content hash prefix for display; full hash is not shown to avoid clutter. */
    val hashPrefix: String?,

    /** File size in human-readable form ("12 KB") or null if unavailable. */
    val sizeText: String?,

    /** Core/provenance identifier (e.g. "sameboy"). Null when unknown. */
    val coreId: String?,

    /** Slot name (e.g. "autosave"). */
    val slot: String?,

    /** RomM ROM ID this save belongs to; null when unknown (never fabricated as 0). */
    val romId: Long?,

    /** Last write/update time as an ISO-8601 string for display; null if unavailable. */
    val updatedAtText: String?,
)

/**
 * Pure-UI model for a genuine RomM synchronization conflict: two valid, changed copies
 * of the same save file. The user must pick one to keep; the losing copy is preserved
 * before replacement (enforced by [ConflictResolutionAction] KDoc).
 */
data class SaveConflictUiModel(
    /** Explanatory title shown at the top of the screen. */
    val title: String = "Save Conflict",

    /** Brief explanation of why this conflict occurred. */
    val description: String,

    /** The locally-authored copy. */
    val local: SaveConflictSide,

    /** The server-authoritative copy. */
    val server: SaveConflictSide,
)

/**
 * Pure-UI model for a quarantined save: unknown/incompatible provenance or size mismatch.
 * This is structurally distinct from [SaveConflictUiModel]; the screen does not offer
 * destructive adoption—only preservation acknowledgment and a separate import path.
 */
data class SaveQuarantineUiModel(
    /** Explanatory title shown at the top of the screen. */
    val title: String = "Incompatible Save",

    /** Reason string from the quarantine decision (e.g. "size-mismatch", "unknown-provenance"). */
    val reason: String,

    /** Human-readable explanation of why this save was quarantined. */
    val description: String,

    /** Metadata about the quarantined file. */
    val quarantined: SaveConflictSide,

    /** Path on disk where the quarantined copy is preserved. */
    val quarantinedPath: String,
)

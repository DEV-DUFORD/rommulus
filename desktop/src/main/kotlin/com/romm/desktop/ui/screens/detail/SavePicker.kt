package com.romm.desktop.ui.screens.detail

import com.romm.androidtv.romm.ServerSaveInfo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure-UI row model for one entry in the desktop save picker (game-detail "Choose Save" flow —
 * Android parity with `SavePickerEntryUiModel`). Carries only what the overlay needs to render,
 * plus the server content hash so [com.romm.desktop.DesktopAppCoordinator.chooseSaveForLaunch]
 * can verify the adopted bytes without a second network round-trip.
 */
data class SavePickerEntryUiModel(
    val saveId: Long,
    /** Display file name, e.g. "autosave [2026-07-31_00-55-06].srm". */
    val fileName: String,
    /** Core/emulator badge, e.g. "gambatte". Null when unknown. */
    val coreId: String?,
    /** File size in human-readable form ("12 KB"), or null if unavailable. */
    val sizeText: String?,
    /** Relative or absolute timestamp text for display, or null if unavailable. */
    val updatedAtText: String?,
    /** True when this is the picker default: the newest save for the current ROM. */
    val isDefaultSelection: Boolean,
    /** Server content hash carried through (not displayed) for adoption verification. */
    val contentHash: String? = null,
)

/** Pure-UI model for the desktop save picker's full state. */
data class SavePickerUiModel(
    val romTitle: String,
    val entries: List<SavePickerEntryUiModel>,
)

/** The save picker overlay's render state (null in the screen = closed). */
sealed interface SavePickerState {
    data object Loading : SavePickerState
    data class Loaded(val model: SavePickerUiModel) : SavePickerState
    data class Error(val message: String) : SavePickerState
}

/**
 * Builds the save picker's entries from the server's listing — 1:1 with Android's
 * `MainActivity.nativeLibraryOnChooseSave`: every save for the ROM regardless of core (SRAM saves
 * are cross-core compatible for the same platform, so no core filter is applied), sorted newest
 * first by [ServerSaveInfo.updatedAt] (saves without a timestamp sort last), with the NEWEST save
 * marked as the default selection.
 */
fun buildSavePickerEntries(
    saves: List<ServerSaveInfo>,
    nowEpochMs: Long = System.currentTimeMillis(),
): List<SavePickerEntryUiModel> {
    val sorted = saves.sortedByDescending { it.updatedAt?.toEpochMilli() ?: Long.MIN_VALUE }
    val defaultId = sorted.firstOrNull()?.saveId
    return sorted.map { save ->
        SavePickerEntryUiModel(
            saveId = save.saveId,
            fileName = save.fileName,
            coreId = save.emulator,
            sizeText = formatSaveSize(save.fileSizeBytes),
            updatedAtText = formatSaveTimestamp(save.updatedAt, nowEpochMs),
            isDefaultSelection = save.saveId == defaultId,
            contentHash = save.contentHash,
        )
    }
}

/** The "Choose Save" button renders whenever the ROM may have server saves (Android parity — the
 *  picker itself shows a "No saves found for this game yet." empty state when the list is empty). */
fun shouldShowChooseSaveButton(hasSaves: Boolean): Boolean = hasSaves

/** Human-readable file size ("12 KB"), matching the detail screen's metadata chip formatting. */
fun formatSaveSize(sizeBytes: Long): String {
    if (sizeBytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = sizeBytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) "${value.toInt()} ${units[unitIndex]}" else "%.1f %s".format(value, units[unitIndex])
}

/** Relative timestamp for recent saves ("just now", "N min ago", "N h ago"), absolute date otherwise. */
fun formatSaveTimestamp(instant: Instant?, nowEpochMs: Long = System.currentTimeMillis()): String? {
    val millis = instant?.toEpochMilli() ?: return null
    val ageSeconds = ((nowEpochMs - millis) / 1000).coerceAtLeast(0)
    return when {
        ageSeconds < 60 -> "just now"
        ageSeconds < 3600 -> "${ageSeconds / 60} min ago"
        ageSeconds < 86_400 -> "${ageSeconds / 3600} h ago"
        else -> LocalDate.ofInstant(instant, ZoneId.systemDefault()).toString()
    }
}

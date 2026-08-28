package com.romm.androidtv.library

import com.romm.androidtv.romm.FirmwareInfo
import com.romm.androidtv.romm.FirmwareStagingOutcome

/** UI state of a BIOS configuration screen (SEGA CD / PlayStation). */
sealed interface BiosConfigurationState {
    data object Loading : BiosConfigurationState
    data class Loaded(
        val options: List<BiosConfigurationOption>,
        val selectedFirmwareId: Long?,
        val downloadingFirmwareId: Long? = null,
        val message: String? = null,
    ) : BiosConfigurationState
    data class Error(val message: String) : BiosConfigurationState
}

/** One selectable BIOS file on the server. */
data class BiosConfigurationOption(
    val firmware: FirmwareInfo,
    val displayName: String,
)

/** Result of fetching a BIOS catalog from the server. */
sealed interface BiosConfigurationCatalog {
    data class Success(
        val options: List<BiosConfigurationOption>,
        val selectedFirmwareId: Long?,
    ) : BiosConfigurationCatalog
    data object AuthExpired : BiosConfigurationCatalog
    data class Error(val message: String) : BiosConfigurationCatalog
}

/**
 * Platform-neutral seam over a console's BIOS manager. Android implements it
 * by wrapping `SegaCdBiosManager` / `PsxBiosManager`; plain JVM (Linux desktop,
 * tests) supplies its own implementation.
 */
interface BiosConfigurationProvider {
    val title: String
    val description: String
    val emptyMessage: String
    suspend fun fetchCatalog(): BiosConfigurationCatalog
    suspend fun select(firmware: FirmwareInfo): FirmwareStagingOutcome
}

package com.romm.androidtv.library

import com.romm.androidtv.romm.FirmwareStagingOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives a BIOS configuration screen (SEGA CD / PlayStation). Loads the
 * verified BIOS catalog from the server and stages the user's selection.
 *
 * Platform-neutral: all async work runs in the injected [scope] so the whole
 * presenter is exercisable by plain JVM unit tests (Linux port Phase 4). The
 * Android `SegaCdBiosManager` / `PsxBiosManager` stay in `:app` behind the
 * [BiosConfigurationProvider] seam.
 */
class BiosConfigurationPresenter(
    private val scope: CoroutineScope,
    private val provider: BiosConfigurationProvider,
) {
    val title: String = provider.title
    val description: String = provider.description
    val emptyMessage: String = provider.emptyMessage
    private val _state = MutableStateFlow<BiosConfigurationState>(BiosConfigurationState.Loading)
    val uiState: StateFlow<BiosConfigurationState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = BiosConfigurationState.Loading
        scope.launch {
            _state.value = when (val catalog = provider.fetchCatalog()) {
                is BiosConfigurationCatalog.Success -> BiosConfigurationState.Loaded(
                    options = catalog.options,
                    selectedFirmwareId = catalog.selectedFirmwareId,
                )
                BiosConfigurationCatalog.AuthExpired -> BiosConfigurationState.Error(
                    "Session expired; log in again to load BIOS files.",
                )
                is BiosConfigurationCatalog.Error -> BiosConfigurationState.Error(
                    "Couldn't load BIOS files (${catalog.message.lowercase().replace('_', ' ')}).",
                )
            }
        }
    }

    fun select(option: BiosConfigurationOption) {
        val loaded = _state.value as? BiosConfigurationState.Loaded ?: return
        if (loaded.downloadingFirmwareId != null) return
        _state.value = loaded.copy(downloadingFirmwareId = option.firmware.firmwareId, message = null)
        scope.launch {
            _state.value = when (val outcome = provider.select(option.firmware)) {
                is FirmwareStagingOutcome.Success -> loaded.copy(
                    selectedFirmwareId = option.firmware.firmwareId,
                    downloadingFirmwareId = null,
                    message = "${option.displayName} BIOS selected",
                )
                else -> loaded.copy(
                    downloadingFirmwareId = null,
                    message = firmwareErrorMessage(outcome),
                )
            }
        }
    }

    private fun firmwareErrorMessage(outcome: FirmwareStagingOutcome): String = when (outcome) {
        FirmwareStagingOutcome.AuthExpired -> "Session expired; log in again."
        is FirmwareStagingOutcome.InsufficientSpace -> "Not enough storage to download this BIOS."
        is FirmwareStagingOutcome.CorruptedDownload -> "BIOS download failed verification."
        is FirmwareStagingOutcome.Missing -> "This BIOS is no longer available on the server."
        is FirmwareStagingOutcome.NetworkError -> "BIOS download failed (${outcome.message})."
        is FirmwareStagingOutcome.Success -> ""
    }
}

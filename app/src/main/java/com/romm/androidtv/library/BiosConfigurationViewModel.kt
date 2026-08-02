package com.romm.androidtv.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.romm.androidtv.romm.FirmwareStagingOutcome
import com.romm.androidtv.romm.FirmwareInfo
import com.romm.androidtv.romm.PsxBiosManager
import com.romm.androidtv.romm.SegaCdBiosManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

data class BiosConfigurationOption(
    val firmware: FirmwareInfo,
    val displayName: String,
)

private sealed interface BiosConfigurationCatalog {
    data class Success(
        val options: List<BiosConfigurationOption>,
        val selectedFirmwareId: Long?,
    ) : BiosConfigurationCatalog
    data object AuthExpired : BiosConfigurationCatalog
    data class Error(val message: String) : BiosConfigurationCatalog
}

private interface BiosConfigurationProvider {
    val title: String
    val description: String
    val emptyMessage: String
    suspend fun fetchCatalog(): BiosConfigurationCatalog
    suspend fun select(firmware: FirmwareInfo): FirmwareStagingOutcome
}

class BiosConfigurationViewModel private constructor(
    private val provider: BiosConfigurationProvider,
) : ViewModel() {
    val title: String = provider.title
    val description: String = provider.description
    val emptyMessage: String = provider.emptyMessage
    private val _state = MutableStateFlow<BiosConfigurationState>(BiosConfigurationState.Loading)
    val state: StateFlow<BiosConfigurationState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = BiosConfigurationState.Loading
        viewModelScope.launch {
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
        viewModelScope.launch {
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

    class Factory private constructor(
        private val provider: BiosConfigurationProvider,
    ) : ViewModelProvider.Factory {
        constructor(biosManager: SegaCdBiosManager) : this(SegaCdProvider(biosManager))
        constructor(biosManager: PsxBiosManager) : this(PsxProvider(biosManager))

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BiosConfigurationViewModel(provider) as T
    }
}

private class SegaCdProvider(
    private val manager: SegaCdBiosManager,
) : BiosConfigurationProvider {
    override val title = "Sega CD BIOS"
    override val description = "Choose a verified BIOS from your RomM server. USA is used by default."
    override val emptyMessage =
        "No Sega CD BIOS files are available. Ask your server administrator to upload one."

    override suspend fun fetchCatalog(): BiosConfigurationCatalog = when (val result = manager.fetchCatalog()) {
        is SegaCdBiosManager.Catalog.Success -> BiosConfigurationCatalog.Success(
            result.options.map {
                BiosConfigurationOption(it.firmware, it.region?.displayName ?: "Unverified BIOS")
            },
            result.selectedFirmwareId,
        )
        SegaCdBiosManager.Catalog.AuthExpired -> BiosConfigurationCatalog.AuthExpired
        is SegaCdBiosManager.Catalog.Error -> BiosConfigurationCatalog.Error(result.message)
    }

    override suspend fun select(firmware: FirmwareInfo) = manager.select(firmware)
}

private class PsxProvider(
    private val manager: PsxBiosManager,
) : BiosConfigurationProvider {
    override val title = "PlayStation BIOS"
    override val description = "Choose a BIOS from your RomM server. USA SCPH-5501 is used by default."
    override val emptyMessage =
        "No PlayStation BIOS files are available. Ask your server administrator to upload one."

    override suspend fun fetchCatalog(): BiosConfigurationCatalog = when (val result = manager.fetchCatalog()) {
        is PsxBiosManager.Catalog.Success -> BiosConfigurationCatalog.Success(
            result.options.map {
                BiosConfigurationOption(it.firmware, it.region?.displayName ?: "Unverified BIOS")
            },
            result.selectedFirmwareId,
        )
        PsxBiosManager.Catalog.AuthExpired -> BiosConfigurationCatalog.AuthExpired
        is PsxBiosManager.Catalog.Error -> BiosConfigurationCatalog.Error(result.message)
    }

    override suspend fun select(firmware: FirmwareInfo) = manager.select(firmware)
}

package com.romm.androidtv.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.romm.androidtv.romm.FirmwareInfo
import com.romm.androidtv.romm.FirmwareStagingOutcome
import com.romm.androidtv.romm.PsxBiosManager
import com.romm.androidtv.romm.SegaCdBiosManager
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin lifecycle wrapper around the platform-neutral [BiosConfigurationPresenter]
 * (Linux port Phase 4). All state-machine behavior lives in
 * `:shared:presentation`; this class only binds it to the lifecycle owner's
 * scope and forwards the public API so existing call sites (factory,
 * BiosConfigurationScreen) compile unchanged. The Android BIOS managers stay
 * here behind the shared [BiosConfigurationProvider] seam.
 */
class BiosConfigurationViewModel private constructor(
    provider: BiosConfigurationProvider,
) : ViewModel() {
    private val presenter = BiosConfigurationPresenter(scope = viewModelScope, provider = provider)

    val title: String = presenter.title
    val description: String = presenter.description
    val emptyMessage: String = presenter.emptyMessage
    val state: StateFlow<BiosConfigurationState> = presenter.uiState

    fun refresh() {
        presenter.refresh()
    }

    fun select(option: BiosConfigurationOption) {
        presenter.select(option)
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

    override suspend fun select(firmware: FirmwareInfo): FirmwareStagingOutcome = manager.select(firmware)
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

    override suspend fun select(firmware: FirmwareInfo): FirmwareStagingOutcome = manager.select(firmware)
}

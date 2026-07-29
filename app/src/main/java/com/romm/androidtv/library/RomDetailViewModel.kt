package com.romm.androidtv.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Drives `GameDetailScreen` (UI_REFACTOR.md section 7). */
class RomDetailViewModel(
    private val repository: LibraryRepository,
    private val romId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow<SectionState<RomDetail>>(SectionState.Loading)
    val state: StateFlow<SectionState<RomDetail>> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = SectionState.Loading
        viewModelScope.launch {
            _state.value = when (val result = repository.fetchRomDetail(romId)) {
                is LibraryResult.Success -> SectionState.Loaded(result.data)
                is LibraryResult.Failure -> SectionState.Error(result.error)
            }
        }
    }

    /** Simple factory since this app doesn't yet use a DI framework. */
    class Factory(
        private val repository: LibraryRepository,
        private val romId: Long,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RomDetailViewModel(repository, romId) as T
        }
    }
}

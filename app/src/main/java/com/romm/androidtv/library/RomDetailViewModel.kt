package com.romm.androidtv.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Drives `GameDetailScreen` (UI_REFACTOR.md section 7). */
class RomDetailViewModel(
    private val repository: LibraryRepository,
    private val romId: Long,
    refreshEvents: Flow<Unit>? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<SectionState<RomDetail>>(SectionState.Loading)
    val state: StateFlow<SectionState<RomDetail>> = _state.asStateFlow()
    private var refreshJob: Job? = null
    private var generation = 0

    init {
        refresh()
        refreshEvents?.let { events ->
            viewModelScope.launch {
                events.collect { refresh() }
            }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        generation++
        val capturedGeneration = generation
        _state.value = SectionState.Loading
        refreshJob = viewModelScope.launch {
            val resultState = when (val result = repository.fetchRomDetail(romId)) {
                is LibraryResult.Success -> SectionState.Loaded(result.data)
                is LibraryResult.Failure -> SectionState.Error(result.error)
            }
            if (capturedGeneration == generation) {
                _state.value = resultState
            }
        }
    }

    /** Simple factory since this app doesn't yet use a DI framework. */
    class Factory(
        private val repository: LibraryRepository,
        private val romId: Long,
        private val refreshEvents: Flow<Unit>? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RomDetailViewModel(repository, romId, refreshEvents) as T
        }
    }
}
